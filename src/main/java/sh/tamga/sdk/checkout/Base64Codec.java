package sh.tamga.sdk.checkout;

import java.util.Base64;
import sh.tamga.sdk.error.TamgaCheckoutException;

/** Shared lenient-base64-decode helpers for {@link LicenseFile} and {@link MachineFile}. */
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
