package sh.tamga.sdk;

/**
 * {@code TamgaClient.java}
 *
 * <p><b>STUB -- scaffolding only.</b> This class has no methods and no business logic; it exists
 * so the module layout is syntactically valid and the eventual public API surface has a home. Do
 * not write code against it. Offline verification is fully implemented elsewhere and does not go
 * through this class -- see {@link sh.tamga.sdk.checkout.LicenseFile}, {@link
 * sh.tamga.sdk.checkout.MachineFile} and {@link sh.tamga.sdk.proof.OfflineProof}.
 *
 * <p>Intended contents once implemented (do not build networking/crypto here directly -- see this
 * repository's {@code CLAUDE.md} "Crypto Architecture" section; delegate HTTP transport to {@link
 * Transport} and cryptographic verification to {@code sh.tamga.sdk.crypto} via the {@code
 * sh.tamga.sdk.checkout}/{@code sh.tamga.sdk.proof} composition layers):
 *
 * <ul>
 *   <li>A {@code Builder} requiring {@code accountId} (String) and {@code baseUrl}/{@code host};
 *       construction fails fast ({@code IllegalStateException}) if either is missing -- matches
 *       the Tamga API protocol specification's "no mode where the account segment can be
 *       omitted".
 *   <li>Base URL assembly: {@code https://<host>/v1/accounts/{account_id}/...}, with defensive
 *       URL-encoding of {@code account_id}.
 *   <li>One method per server endpoint documented in the Tamga API protocol specification,
 *       grouped by resource:
 *       <ul>
 *         <li>License validation: {@code validateByKey}, {@code validateById}, {@code
 *             quickValidate} (§D)
 *         <li>License check-in: {@code checkIn} (§E)
 *         <li>License checkout: {@code checkOutLicense} / {@code checkOutLicenseRaw} (§F)
 *         <li>Machine management: {@code createMachine}, {@code activateMachine}, {@code
 *             pingHeartbeat}, {@code resetHeartbeat} (§H)
 *         <li>Machine checkout: {@code checkOutMachine} / {@code checkOutMachineRaw} (§G)
 *         <li>Machine offline proof: {@code generateOfflineProof} (§I)
 *         <li>Components &amp; processes: {@code createComponent}, {@code listComponents}, {@code
 *             createProcess}, {@code pingProcess} (§J)
 *         <li>Entitlements: {@code listEntitlements}, {@code getEntitlement}, {@code
 *             hasEntitlement} (§K)
 *       </ul>
 * </ul>
 *
 * <p>Every method must always send {@code Authorization: License <key>} -- the primary auth
 * transport for this embedded SDK. Send it unconditionally rather than only where the server is
 * observed to check it.
 */
public final class TamgaClient {

  private TamgaClient() {
    // Intentionally empty. Implementation deferred to a future session.
  }
}
