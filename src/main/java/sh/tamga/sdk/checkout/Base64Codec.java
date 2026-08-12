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
    try {
      return Base64.getDecoder().decode(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
