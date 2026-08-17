package sh.tamga.sdk.model;

/**
 * {@code Policy.java}
 *
 * <p><b>STUB -- scaffolding only.</b> No implementation yet.
 *
 * <p>Intended contents once implemented: the policy-derived behavior reference from the Tamga API
 * protocol specification §10 -- {@code overageStrategy}, {@code heartbeatCullStrategy}, {@code
 * heartbeatResurrectionStrategy}, {@code checkInInterval}, {@code requireCheckIn} (boolean),
 * {@code maxMachines}/{@code maxCores}/{@code maxProcesses} (integers), plus the free-text
 * strategy fields ({@code expirationStrategy}, {@code renewalBasis}, {@code
 * authenticationStrategy}).
 *
 * <p><b>Gotcha to preserve when this is implemented:</b> the server's {@code GET} response for a
 * policy OMITS {@code max_memory} and {@code max_disk} even though both are enforced during
 * validation -- an SDK cannot introspect these two limits client-side, only observe {@code
 * TOO_MUCH_MEMORY}/{@code TOO_MUCH_DISK} if validation fails. Do not add fields for them expecting
 * a value to ever arrive.
 *
 * <p>See also: freshly-created policies default {@code overage_strategy} to the literal string
 * {@code "DENY_ACCESS"} and {@code heartbeat_resurrection_strategy} to {@code "NO_RESURRECTION"} --
 * neither is a real enum variant. The deserializers for those two fields must fall back
 * leniently (to {@code NO_OVERAGE}/{@code NO_REVIVE} semantics respectively) rather than throwing.
 * See the Tamga API protocol specification's "Known Server-Side Gaps" item 9.
 */
public final class Policy {

  private Policy() {
    // Intentionally empty. Implementation deferred to a future session.
  }
}
