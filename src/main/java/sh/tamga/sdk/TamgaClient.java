package sh.tamga.sdk;

/**
 * {@code TamgaClient.java}
 *
 * <p><b>STUB -- scaffolding only.</b> No business logic is implemented yet; this file exists so
 * the module layout is syntactically valid and the eventual public API surface has a home. See
 * {@code docs/plans/tamga-java.plan.md} Section C onward for the full task breakdown this class
 * will eventually satisfy.
 *
 * <p>Intended contents once implemented (do not build networking/crypto here directly -- see this
 * repository's {@code CLAUDE.md} crypto-boundary rule; delegate to {@link Transport} and {@code
 * sh.tamga.sdk.internal.jni.TamgaNative}):
 *
 * <ul>
 *   <li>A {@code Builder} requiring {@code accountId} (String) and {@code baseUrl}/{@code host};
 *       construction fails fast ({@code IllegalStateException}) if either is missing -- matches
 *       {@code docs/sdk.md}'s "no mode where the account segment can be omitted".
 *   <li>Base URL assembly: {@code https://<host>/v1/accounts/{account_id}/...}, with defensive
 *       URL-encoding of {@code account_id}.
 *   <li>One method per server endpoint documented in {@code docs/sdk.md}, grouped by resource:
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
 * <p>Every method must always send {@code Authorization: License <key>} (the primary transport
 * for this embedded SDK) even though no auth is currently enforced server-side on the validation
 * endpoints -- see {@code docs/sdk.md}'s "Known Server-Side Gaps" item 3.
 */
public final class TamgaClient {

  private TamgaClient() {
    // Intentionally empty. Implementation deferred to a future session per
    // docs/plans/tamga-java.plan.md Sections C-L.
  }
}
