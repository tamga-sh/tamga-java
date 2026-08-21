package sh.tamga.sdk.checkout;

import java.util.Arrays;
import sh.tamga.sdk.crypto.AesGcm;
import sh.tamga.sdk.error.TamgaCheckoutException;

/**
 * AES-256-GCM open logic for {@link LicenseFile} and {@link MachineFile}. Both derive {@code key}
 * with HKDF-SHA256 ({@link sh.tamga.sdk.crypto.Hkdf}), differing only in the salt/{@code info} they
 * pass it (see each type's own remarks), so the derived key is the only thing callers supply here.
 *
 * <p><b>The two file types do NOT share a ciphertext framing</b>, despite sharing the PEM envelope,
 * the {@code {enc, sig, alg}} certificate shape and the {@code aes-256-gcm+...+v2} prefix:
 *
 * <ul>
 *   <li>A {@code .lic} file's {@code enc} is {@code base64(nonce || ciphertext || tag)} -- one
 *       blob, sliced by {@link #decrypt}. ({@code license_file.rs}'s {@code aes256gcm_encrypt}.)
 *   <li>A {@code .machine} file's {@code enc} is {@code "<nonce_b64>.<ciphertext_b64>"} -- two
 *       independently base64-encoded halves joined by a literal {@code '.'}, read by
 *       {@link #decryptDotSeparated}. ({@code machine_file.rs} delegates to {@code
 *       FieldEncryption::encrypt}.)
 * </ul>
 *
 * <p>They are genuinely different formats and neither can be read by the other's reader. Verified
 * against the server; note that {@code machine_file.rs}'s own doc comment claims the single-blob
 * form and is stale, which is what led every SDK in this family to implement it that way.
 */
final class EncryptedPayloadDecryptor {

  private EncryptedPayloadDecryptor() {
  }

  /** Opens a {@code .lic} file's single-blob {@code nonce || ciphertext || tag} payload. */
  static byte[] decrypt(byte[] payloadBytes, byte[] key, String context) {
    int minLength = AesGcm.NONCE_LENGTH + AesGcm.TAG_LENGTH;
    if (payloadBytes.length < minLength) {
      throw new TamgaCheckoutException.OfflineFileFormatException(
          context + " payload too short: expected at least " + minLength + " bytes, got "
              + payloadBytes.length + ".");
    }

    byte[] nonce = Arrays.copyOfRange(payloadBytes, 0, AesGcm.NONCE_LENGTH);
    int tagStart = payloadBytes.length - AesGcm.TAG_LENGTH;
    byte[] tag = Arrays.copyOfRange(payloadBytes, tagStart, payloadBytes.length);
    byte[] ciphertext = Arrays.copyOfRange(payloadBytes, AesGcm.NONCE_LENGTH, tagStart);

    return open(key, nonce, ciphertext, tag, context);
  }

  /**
   * Opens a {@code .machine} file's {@code "<nonce_b64>.<ciphertext_b64>"} payload.
   *
   * <p>Takes the {@code enc} STRING rather than decoded bytes on purpose: the two halves are
   * base64-encoded independently, so there is no single decode of the whole field that produces
   * anything meaningful -- and the server's own decoder rejects a nonce that is not exactly
   * {@link AesGcm#NONCE_LENGTH} bytes, so this one does too rather than silently truncating.
   *
   * <p>Callers must verify the signature over the whole {@code enc} string BEFORE calling this.
   * Nothing here should ever be reached for bytes an attacker still controls.
   */
  static byte[] decryptDotSeparated(String enc, byte[] key, String context) {
    int separator = enc.indexOf('.');
    if (separator <= 0 || separator != enc.lastIndexOf('.') || separator == enc.length() - 1) {
      throw new TamgaCheckoutException.OfflineFileFormatException(
          context + " 'enc' must be exactly \"<nonce_b64>.<ciphertext_b64>\" -- two "
              + "independently base64-encoded halves joined by a single '.'.");
    }

    byte[] nonce = Base64Codec.decodeOrThrow(enc.substring(0, separator),
        context + " nonce is not valid base64.");
    byte[] ciphertextAndTag = Base64Codec.decodeOrThrow(enc.substring(separator + 1),
        context + " ciphertext is not valid base64.");

    if (nonce.length != AesGcm.NONCE_LENGTH) {
      throw new TamgaCheckoutException.OfflineFileFormatException(
          context + " nonce must be " + AesGcm.NONCE_LENGTH + " bytes, got " + nonce.length + ".");
    }
    if (ciphertextAndTag.length < AesGcm.TAG_LENGTH) {
      throw new TamgaCheckoutException.OfflineFileFormatException(
          context + " ciphertext too short: expected at least " + AesGcm.TAG_LENGTH
              + " bytes for the authentication tag, got " + ciphertextAndTag.length + ".");
    }

    int tagStart = ciphertextAndTag.length - AesGcm.TAG_LENGTH;
    byte[] tag = Arrays.copyOfRange(ciphertextAndTag, tagStart, ciphertextAndTag.length);
    byte[] ciphertext = Arrays.copyOfRange(ciphertextAndTag, 0, tagStart);

    return open(key, nonce, ciphertext, tag, context);
  }

  private static byte[] open(byte[] key, byte[] nonce, byte[] ciphertext, byte[] tag,
      String context) {
    try {
      return AesGcm.open(key, nonce, ciphertext, tag);
    } catch (AesGcm.AesGcmException e) {
      throw new TamgaCheckoutException.DecryptionException(
          context + " failed to decrypt -- verify the license key (and fingerprint, for machine "
              + "files) are correct, or the file may be corrupted.");
    }
  }
}
