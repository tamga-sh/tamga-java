package sh.tamga.sdk.model;

/**
 * {@code Machine.java}
 *
 * <p><b>STUB -- scaffolding only.</b> No implementation yet.
 *
 * <p>Intended contents once implemented: an immutable resource model matching the JSON:API
 * {@code machines} resource shape from {@code docs/sdk.md} §5 -- {@code fingerprint}, {@code
 * name}, {@code ip}, {@code hostname}, {@code platform}, {@code cores}, {@code memory}, {@code
 * disk}, {@code metadata}, {@code heartbeatStatus} ({@link HeartbeatStatus}, not yet scaffolded --
 * see §H), and timestamps. Deserialized by both {@code TamgaClient}'s machine-management responses
 * (§H) and {@code sh.tamga.sdk.checkout.MachineFile#getData()} (§G), which parses an embedded
 * {@code {"data": <Machine>}} payload out of a verified/decrypted offline machine file -- both
 * paths must deserialize to the exact same type.
 */
public final class Machine {

  private Machine() {
    // Intentionally empty. Implementation deferred to a future session per
    // docs/plans/tamga-java.plan.md Section H.
  }
}
