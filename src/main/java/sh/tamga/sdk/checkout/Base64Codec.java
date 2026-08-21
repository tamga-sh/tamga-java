package sh.tamga.sdk.checkout;

import java.util.Base64;
import sh.tamga.sdk.error.TamgaCheckoutException;

/**
 * Shared base64-decode helpers for {@link LicenseFile} and {@link MachineFile}: lenient in what
 * they REPORT (a {@code null} return rather than an exception), strict in what they ACCEPT.
 *
 * <p><b>The strictness is load-bearing -- never swap {@link java.util.Base64#getDecoder()} for
 * {@code getMimeDecoder()}.</b> A machine file's encrypted {@code enc} is
 * {@code "<nonce_b64>.<ciphertext_b64>"}, and a 12-byte nonce always encodes to exactly 16 unpadded
 * characters, so the two halves concatenated stay 4-aligned. A decoder that skips out-of-alphabet
 * characters would therefore drop the {@code '.'} and silently reconstruct
 * {@code nonce || ciphertext || tag} byte-for-byte -- reviving the single-blob misreading this SDK
 * shipped for two years, and softening the nonce-length and tag-length guards built around it.
 * {@code MachineFileTest.base64DecodingIsStrictSoJunkInsideEncIsRejectedNotIgnored} pins this.
 */
final class Base64Codec {

  private Base64Codec() {
  }

  static byte[] decodeOrThrow(String value, String errorMessage) {
    byte[] decoded = decodeOrNull(value);
    if (decoded == null) {
      throw new TamgaCheckoutException.OfflineFileFormatException(errorMessage);
    }
    return decoded;
  }

  static byte[] decodeOrNull(String value) {
    if (value == null) {
      // Base64.getDecoder().decode(null) throws NullPointerException, not
      // IllegalArgumentException -- guard explicitly rather than relying on
      // callers to pre-validate. Defense in depth: LicenseFile/MachineFile's
      // own parse() methods also reject a null enc/sig/alg immediately, so
      // this path is normally unreachable, but a shared utility should not
      // depend on every future caller getting that right.
      return null;
    }
    try {
      return Base64.getDecoder().decode(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
