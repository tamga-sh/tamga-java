package sh.tamga.sdk.model;

/**
 * The key/checkout signing algorithm configured on a license's policy. {@link #NONE} means the
 * policy has no scheme set -- a legacy plain key string, unsigned.
 */
public enum LicenseScheme {
  /** No scheme configured -- legacy plain key string, unsigned. */
  NONE(""),
  /**
   * Wire value {@code ED25519_SIGN}. Also the sole scheme used for license checkout ({@link
   * sh.tamga.sdk.checkout.LicenseFile}), independent of this field.
   */
  ED25519_SIGN("ED25519_SIGN"),
  /** Wire value {@code RSA_2048_PKCS1_SIGN}. */
  RSA_2048_PKCS1_SIGN("RSA_2048_PKCS1_SIGN"),
  /** Wire value {@code RSA_2048_PKCS1_PSS_SIGN}. */
  RSA_2048_PKCS1_PSS_SIGN("RSA_2048_PKCS1_PSS_SIGN"),
  /** Wire value {@code ECDSA_P256_SIGN}. */
  ECDSA_P256_SIGN("ECDSA_P256_SIGN"),
  /**
   * Wire value {@code RSA_2048_JWT_RS256}. Explicitly rejected server-side for machine files
   * ({@code 422 SCHEME_NOT_SUPPORTED}) -- {@code MachineFile} must throw rather than attempt
   * JWT/RS256 verification.
   */
  RSA_2048_JWT_RS256("RSA_2048_JWT_RS256");

  private final String wireValue;

  LicenseScheme(String wireValue) {
    this.wireValue = wireValue;
  }

  /** An empty string or missing value maps to {@link #NONE} (legacy unsigned key). */
  public static LicenseScheme fromWireValue(String wireValue) {
    if (wireValue == null || wireValue.isEmpty()) {
      return NONE;
    }
    for (LicenseScheme scheme : values()) {
      if (scheme.wireValue.equals(wireValue)) {
        return scheme;
      }
    }
    return NONE;
  }
}
