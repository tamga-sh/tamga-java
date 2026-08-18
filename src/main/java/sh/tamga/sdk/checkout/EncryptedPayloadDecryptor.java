package sh.tamga.sdk.checkout;

import java.util.Arrays;
import sh.tamga.sdk.crypto.AesGcm;
import sh.tamga.sdk.error.TamgaCheckoutException;

/**
 * Shared {@code nonce || ciphertext || tag} slicing + AES-256-GCM open logic for {@link
 * LicenseFile} and {@link MachineFile}. Both derive {@code key} with HKDF-SHA256 ({@link
 * sh.tamga.sdk.crypto.Hkdf}); they differ only in the salt/{@code info} they pass it (see each
 * type's own remarks), so the derived key is the only thing callers supply here.
 */
final class EncryptedPayloadDecryptor {

  private EncryptedPayloadDecryptor() {
  }

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

    try {
      return AesGcm.open(key, nonce, ciphertext, tag);
    } catch (AesGcm.AesGcmException e) {
      throw new TamgaCheckoutException.DecryptionException(
          context + " failed to decrypt -- verify the license key (and fingerprint, for machine "
              + "files) are correct, or the file may be corrupted.");
    }
  }
}
