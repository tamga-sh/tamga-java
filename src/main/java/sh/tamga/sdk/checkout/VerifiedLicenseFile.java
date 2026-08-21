package sh.tamga.sdk.checkout;

import sh.tamga.sdk.model.License;
import sh.tamga.sdk.model.LicenseFileClaims;
import sh.tamga.sdk.model.SigningKey;

/**
 * A {@code .lic} file that verified, together with the key it verified under.
 *
 * <p>Returned by the {@link SigningKeySet}-aware entry points on {@link LicenseFile}. The
 * single-key entry points return a bare {@link License} and are unchanged.
 */
public final class VerifiedLicenseFile {

  private final License license;
  private final LicenseFileClaims claims;
  private final SigningKey key;

  VerifiedLicenseFile(License license, LicenseFileClaims claims, SigningKey key) {
    this.license = license;
    this.claims = claims;
    this.key = key;
  }

  /** The license the file describes. */
  public License license() {
    return license;
  }

  /** The signed {@code iat}/{@code exp}/{@code jti}/{@code kid} claims. */
  public LicenseFileClaims claims() {
    return claims;
  }

  /**
   * The key the signature verified under.
   *
   * <p>Worth inspecting: {@link SigningKey#isRetired()} means the file is authentic and was issued
   * before the account's last rotation. Nothing is wrong with it, but whatever hands these out is
   * due a fresh checkout.
   */
  public SigningKey key() {
    return key;
  }
}
