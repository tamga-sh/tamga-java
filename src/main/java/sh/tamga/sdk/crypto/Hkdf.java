package sh.tamga.sdk.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HKDF-SHA256 (RFC 5869), hand-rolled over {@code javax.crypto.Mac("HmacSHA256")} -- the JDK has
 * no standard HKDF API at this module's Java 11 baseline.
 *
 * <p>Used only by machine-file checkout to derive the AES-256-GCM key -- see {@link NaiveKey} for
 * license-file checkout's deliberately different, non-KDF derivation. The two are never
 * interchangeable.
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
