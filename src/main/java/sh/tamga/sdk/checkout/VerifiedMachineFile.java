package sh.tamga.sdk.checkout;

import sh.tamga.sdk.model.LicenseFileClaims;
import sh.tamga.sdk.model.Machine;
import sh.tamga.sdk.model.SigningKey;

/**
 * A {@code .machine} file that verified, together with the key it verified under -- see {@link
 * VerifiedLicenseFile}, which is the same shape for the license format.
 */
public final class VerifiedMachineFile {

  private final Machine machine;
  private final LicenseFileClaims claims;
  private final SigningKey key;

  VerifiedMachineFile(Machine machine, LicenseFileClaims claims, SigningKey key) {
    this.machine = machine;
    this.claims = claims;
    this.key = key;
  }

  /** The machine the file describes. */
  public Machine machine() {
    return machine;
  }

  /** The signed {@code iat}/{@code exp}/{@code jti}/{@code kid} claims. */
  public LicenseFileClaims claims() {
    return claims;
  }

  /**
   * The key the signature verified under -- always an Ed25519 key, since that is all a key set can
   * hold. {@link SigningKey#isRetired()} says the file predates the account's last rotation.
   */
  public SigningKey key() {
    return key;
  }
}
