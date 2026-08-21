package sh.tamga.sdk.checkout;

import sh.tamga.sdk.error.TamgaCheckoutException;
import sh.tamga.sdk.model.LicenseScheme;

/**
 * The parsed {@code alg} identifier of a {@code .machine} file.
 *
 * <p>The server builds it as {@code "<encoding>+<signing-suffix>+v2"} ({@code
 * machine_file_alg_str}, {@code src/shared/crypto/machine_file.rs}), so exactly eight strings are
 * reachable: {@code base64} or {@code aes-256-gcm}, crossed with {@code ed25519}, {@code
 * ecdsa-p256}, {@code rsa-sha256} or {@code rsa-pss-sha256}.
 *
 * <p>Both an encoding prefix ({@code aes-256-gcm}) and two signing suffixes ({@code ecdsa-p256},
 * {@code rsa-pss-sha256}) contain hyphens, and only the two {@code +} separators are structural --
 * so the encoding is everything before the FIRST {@code +}, the version marker everything after
 * the LAST, and the signing suffix is what is left in between. A substring {@code contains()} test
 * is not a parse: it accepts {@code base64+ed25519+v3} and {@code xbase64+ed25519+v2junk} just as
 * happily as the real thing, which is how this SDK came to "support" v2 without ever checking for
 * it.
 *
 * <p>The {@code +v2} marker is mandatory and there is no fallback. A v1 file carried no {@code
 * meta.exp} inside the signed payload (so it never expired) and derived its AES key by zero-padding
 * the license key instead of HKDF. Accepting one silently reinstates both weaknesses.
 *
 * <p>The signing suffix is a CROSS-CHECK against the caller-supplied {@link LicenseScheme}, never a
 * verifier selector: {@code RSA_2048_PKCS1_SIGN} and {@code RSA_2048_JWT_RS256} both serialize to
 * {@code rsa-sha256} server-side, so {@code alg} cannot identify the scheme even in principle --
 * and it is not covered by the signature, so it is attacker-editable in any case.
 */
final class MachineFileAlg {

  private static final String VERSION_MARKER = "v2";
  private static final String ENCODING_PLAIN = "base64";
  private static final String ENCODING_AES_256_GCM = "aes-256-gcm";

  private static final String SUFFIX_ED25519 = "ed25519";
  private static final String SUFFIX_ECDSA_P256 = "ecdsa-p256";
  private static final String SUFFIX_RSA_SHA256 = "rsa-sha256";
  private static final String SUFFIX_RSA_PSS_SHA256 = "rsa-pss-sha256";

  private final boolean encrypted;

  private MachineFileAlg(boolean encrypted) {
    this.encrypted = encrypted;
  }

  /** Whether the {@code enc} payload is AES-256-GCM ciphertext rather than plain base64 JSON. */
  boolean isEncrypted() {
    return encrypted;
  }

  /**
   * Parses and validates {@code alg} against the caller-supplied scheme.
   *
   * @throws TamgaCheckoutException.UnsupportedAlgorithmException if the string is not
   *     {@code <encoding>+<signing-suffix>+v2}, if the encoding or version marker is not one this
   *     SDK implements, or if the signing suffix contradicts {@code scheme}.
   * @throws TamgaCheckoutException.SchemeNotSupportedException if {@code scheme} is {@link
   *     LicenseScheme#RSA_2048_JWT_RS256}. Unreachable through {@link MachineFile}, which refuses
   *     that scheme before calling here -- but this method is the one place that must never map it
   *     onto {@code rsa-sha256}, so it throws rather than relying on its only caller.
   */
  static MachineFileAlg parse(String alg, LicenseScheme scheme) {
    int firstPlus = alg.indexOf('+');
    int lastPlus = alg.lastIndexOf('+');
    // firstPlus == 0 would mean an empty encoding; lastPlus == firstPlus means there is only one
    // separator, so either the signing suffix or the version marker is missing entirely.
    if (firstPlus <= 0 || lastPlus <= firstPlus || lastPlus == alg.length() - 1) {
      throw reject(alg, "expected '<encoding>+<signing-suffix>+" + VERSION_MARKER + "'");
    }

    String version = alg.substring(lastPlus + 1);
    if (!VERSION_MARKER.equals(version)) {
      throw reject(alg, "only file format '" + VERSION_MARKER + "' is supported; a pre-v2 file "
          + "carries no signed expiry and derives its encryption key without HKDF, so it must be "
          + "re-issued rather than accepted");
    }

    String encoding = alg.substring(0, firstPlus);
    boolean encrypted;
    if (ENCODING_PLAIN.equals(encoding)) {
      encrypted = false;
    } else if (ENCODING_AES_256_GCM.equals(encoding)) {
      encrypted = true;
    } else {
      throw reject(alg, "unknown payload encoding '" + encoding + "'");
    }

    String signingSuffix = alg.substring(firstPlus + 1, lastPlus);
    String expected = signingSuffixFor(scheme);
    if (!expected.equals(signingSuffix)) {
      // The scheme the caller passed governs; this only catches a file that was issued under a
      // different scheme than the license the caller is holding says it uses.
      throw reject(alg, "declares signing suffix '" + signingSuffix + "' but the license scheme "
          + scheme + " signs with '" + expected + "'");
    }

    return new MachineFileAlg(encrypted);
  }

  private static String signingSuffixFor(LicenseScheme scheme) {
    switch (scheme) {
      case NONE:
      case ED25519_SIGN:
        return SUFFIX_ED25519;
      case ECDSA_P256_SIGN:
        return SUFFIX_ECDSA_P256;
      case RSA_2048_PKCS1_SIGN:
        return SUFFIX_RSA_SHA256;
      case RSA_2048_PKCS1_PSS_SIGN:
        return SUFFIX_RSA_PSS_SHA256;
      case RSA_2048_JWT_RS256:
        // Unreachable: MachineFile.verify rejects this scheme before it gets here. Kept explicit
        // rather than falling into the default branch so it can never be quietly mapped onto
        // "rsa-sha256" -- the suffix the server emits for BOTH RSA_2048_PKCS1_SIGN and
        // RSA_2048_JWT_RS256, which is exactly the confusion this whole cross-check exists to
        // avoid.
        throw new TamgaCheckoutException.SchemeNotSupportedException(
            "RSA_2048_JWT_RS256 is never implemented for machine files.");
      default:
        throw new TamgaCheckoutException.UnsupportedAlgorithmException(
            "Unrecognized license scheme: " + scheme);
    }
  }

  private static TamgaCheckoutException.UnsupportedAlgorithmException reject(String alg,
      String reason) {
    return new TamgaCheckoutException.UnsupportedAlgorithmException(
        "Unsupported machine file algorithm '" + alg + "': " + reason + ".");
  }
}
