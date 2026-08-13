package sh.tamga.sdk.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HKDF-SHA256 (RFC 5869), hand-rolled over {@code javax.crypto.Mac("HmacSHA256")} -- the JDK has
 * no standard HKDF API at this module's Java 11 baseline.
 *
 * <p>Used by both license-file and machine-file checkout to derive the AES-256-GCM key, with
 * different, non-interchangeable salt/info parameters per format -- license-file checkout no
 * longer uses a non-KDF derivation (that was the pre-v2 design; the old NaiveKey class is
 * removed).
 */
public final class Hkdf {

  private static final String HMAC_SHA256 = "HmacSHA256";
  private static final int HASH_LENGTH = 32;

  /**
   * CRITICAL: this literal salt is load-bearing -- a typo here silently breaks every machine-file
   * decrypt. Must match the server's own derivation exactly.
   */
  private static final byte[] MACHINE_FILE_KEY_SALT =
      "tamga:machine-file-key-v1".getBytes(StandardCharsets.UTF_8);

  private static final int MACHINE_FILE_KEY_LENGTH = 32;

  /**
   * CRITICAL: load-bearing literals, same as the machine-file salt above.
   *
   * <p>Before file format v2 the license-file key was not derived at all -- it was the license
   * key's raw UTF-8 bytes zero-padded to 32, which meant an attacker holding a stolen {@code .lic}
   * was attacking the license key's own entropy rather than a 256-bit key space. The
   * {@code NaiveKey} class that implemented it has been removed rather than deprecated: leaving it
   * public would let a caller silently keep using the weaker derivation.
   */
  private static final byte[] LICENSE_FILE_KEY_SALT =
      "tamga:license-file-key-v1".getBytes(StandardCharsets.UTF_8);

  private static final byte[] LICENSE_FILE_KEY_INFO =
      "license-file".getBytes(StandardCharsets.UTF_8);

  private Hkdf() {
  }

  /**
   * Derives the machine-file AES-256-GCM key: HKDF-SHA256 with {@code ikm} = the license key's raw
   * UTF-8 bytes, {@code salt} = {@code "tamga:machine-file-key-v1"}, {@code info} = the machine
   * fingerprint's raw UTF-8 bytes, 32-byte output.
   */
  public static byte[] deriveMachineFileKey(String licenseKey, String fingerprint) {
    byte[] inputKeyingMaterial = licenseKey.getBytes(StandardCharsets.UTF_8);
    byte[] info = fingerprint.getBytes(StandardCharsets.UTF_8);
    return derive(inputKeyingMaterial, MACHINE_FILE_KEY_SALT, info, MACHINE_FILE_KEY_LENGTH);
  }

  /**
   * Derives the license-file AES-256-GCM key: HKDF-SHA256 with {@code ikm} = the license key's raw
   * UTF-8 bytes, {@code salt} = {@code "tamga:license-file-key-v1"}, {@code info} =
   * {@code "license-file"}, 32-byte output.
   *
   * <p>No fingerprint is involved -- a license file is not bound to a machine.
   */
  public static byte[] deriveLicenseFileKey(String licenseKey) {
    byte[] inputKeyingMaterial = licenseKey.getBytes(StandardCharsets.UTF_8);
    return derive(
        inputKeyingMaterial, LICENSE_FILE_KEY_SALT, LICENSE_FILE_KEY_INFO, MACHINE_FILE_KEY_LENGTH);
  }

  /**
   * General-purpose RFC 5869 HKDF-SHA256: extract then expand. Package-private for direct testing.
   */
  static byte[] derive(byte[] inputKeyingMaterial, byte[] salt, byte[] info, int outputLength) {
    byte[] pseudoRandomKey = hmac(salt, inputKeyingMaterial);
    return expand(pseudoRandomKey, info, outputLength);
  }

  private static byte[] expand(byte[] pseudoRandomKey, byte[] info, int outputLength) {
    int iterations = (outputLength + HASH_LENGTH - 1) / HASH_LENGTH;
    if (iterations > 255) {
      // RFC 5869 §2.3: output length is capped at 255 * HashLen.
      throw new IllegalArgumentException("HKDF output length too large: " + outputLength);
    }
    byte[] output = new byte[outputLength];
    byte[] previousBlock = new byte[0];
    int written = 0;
    for (int counter = 1; counter <= iterations; counter++) {
      byte[] input = concat(previousBlock, info, new byte[] {(byte) counter});
      previousBlock = hmac(pseudoRandomKey, input);
      int toCopy = Math.min(HASH_LENGTH, outputLength - written);
      System.arraycopy(previousBlock, 0, output, written, toCopy);
      written += toCopy;
    }
    return output;
  }

  private static byte[] hmac(byte[] key, byte[] data) {
    try {
      Mac mac = Mac.getInstance(HMAC_SHA256);
      // SecretKeySpec rejects a zero-length key array outright, but RFC
      // 5869's extract step legitimately allows an absent/empty salt --
      // "If salt is not provided, it is set to a string of HashLen zeros."
      byte[] macKey = key.length == 0 ? new byte[HASH_LENGTH] : key;
      mac.init(new SecretKeySpec(macKey, HMAC_SHA256));
      return mac.doFinal(data);
    } catch (GeneralSecurityException e) {
      // HmacSHA256 is a mandatory JCA algorithm on every JDK distribution --
      // this is not a reachable failure mode for untrusted input, only a
      // broken JVM installation.
      throw new IllegalStateException("HmacSHA256 unexpectedly unavailable.", e);
    }
  }

  private static byte[] concat(byte[]... parts) {
    int total = 0;
    for (byte[] part : parts) {
      total += part.length;
    }
    byte[] out = new byte[total];
    int position = 0;
    for (byte[] part : parts) {
      System.arraycopy(part, 0, out, position, part.length);
      position += part.length;
    }
    return out;
  }
}
