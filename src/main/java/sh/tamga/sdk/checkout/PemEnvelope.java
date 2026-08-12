package sh.tamga.sdk.checkout;

import sh.tamga.sdk.error.TamgaCheckoutException;

/** Shared PEM-envelope stripping for {@link LicenseFile} and {@link MachineFile}. */
final class PemEnvelope {

  private PemEnvelope() {
  }

  static String strip(String pem, String beginMarker, String endMarker) {
    String trimmed = pem.strip();
    if (!trimmed.startsWith(beginMarker)) {
      throw new TamgaCheckoutException.OfflineFileFormatException(
          "Missing '" + beginMarker + "' marker.");
    }
    if (!trimmed.endsWith(endMarker)) {
      throw new TamgaCheckoutException.OfflineFileFormatException(
          "Missing '" + endMarker + "' marker.");
    }

    // SECURITY: startsWith/endsWith only guarantee the trimmed string is at
    // least as long as each marker individually -- a short, attacker-crafted
    // string can satisfy both independently while being shorter than
    // beginMarker.length() + endMarker.length() (the two markers "overlap").
    // Without this guard, the substring below computes a negative-length
    // range and throws StringIndexOutOfBoundsException instead of the
    // documented TamgaCheckoutException, breaking callers that only catch
    // that type for untrusted .lic/.machine input. Same class of bug as a
    // HIGH finding already fixed in tamga-dotnet's equivalent
    // PemEnvelope.Strip during that repo's mandatory security review.
    if (trimmed.length() < beginMarker.length() + endMarker.length()) {
      throw new TamgaCheckoutException.OfflineFileFormatException(
          "Body between '" + beginMarker + "' and '" + endMarker + "' is malformed or too short.");
    }

    String body = trimmed.substring(beginMarker.length(), trimmed.length() - endMarker.length());
    return body.chars().filter(c -> !Character.isWhitespace(c))
        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
        .toString();
  }
}
