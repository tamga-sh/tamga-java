package sh.tamga.sdk.model;

/**
 * {@code ValidationCode.java}
 *
 * <p><b>STUB -- scaffolding only.</b> No implementation yet.
 *
 * <p>Intended contents once implemented: an enum modeling all 24 wire values of {@code meta.code}
 * from the Tamga API protocol specification §2, in the server's exact priority-evaluation order,
 * with a Jackson {@code @JsonEnumDefaultValue}-annotated {@code UNKNOWN} fallback member so any
 * future server-added code deserializes leniently instead of throwing:
 *
 * <pre>
 * VALID, SUSPENDED, EXPIRED, OVERDUE, PRODUCT_SCOPE_MISMATCH, POLICY_SCOPE_MISMATCH,
 * USER_SCOPE_MISMATCH, ENVIRONMENT_SCOPE_MISMATCH, TOO_MANY_MACHINES, TOO_MANY_CORES,
 * TOO_MUCH_MEMORY, TOO_MUCH_DISK, TOO_MANY_PROCESSES, TOO_MANY_USES, NOT_FOUND, BANNED,
 * ENTITLEMENTS_MISSING, TOO_MANY_USERS, HEARTBEAT_DEAD, HEARTBEAT_NOT_STARTED,
 * FINGERPRINT_SCOPE_MISMATCH, COMPONENTS_SCOPE_MISMATCH, CHECKSUM_SCOPE_MISMATCH,
 * VERSION_SCOPE_MISMATCH, UNKNOWN
 * </pre>
 *
 * <p>Only 14 of the 24 values are actually reachable against the server today. Model all 24 for
 * forward-compatibility, but doc-comment the unreachable ones so callers don't build UX assuming
 * they'll ever see them (see the Tamga API protocol specification's "Known Server-Side Gaps"
 * item 4):
 *
 * <ul>
 *   <li><b>Reachable today (✅):</b> {@code VALID} through {@code TOO_MANY_USES} (14 values).
 *   <li><b>Never emitted (⛔):</b> {@code NOT_FOUND} (the handler returns a bare HTTP 404
 *       instead), plus {@code BANNED}, {@code ENTITLEMENTS_MISSING}, {@code TOO_MANY_USERS},
 *       {@code HEARTBEAT_DEAD}, {@code HEARTBEAT_NOT_STARTED}, {@code
 *       FINGERPRINT_SCOPE_MISMATCH}, {@code COMPONENTS_SCOPE_MISMATCH}, {@code
 *       CHECKSUM_SCOPE_MISMATCH}, {@code VERSION_SCOPE_MISMATCH} -- declared in the enum but never
 *       wired into any validation path yet.
 * </ul>
 */
public enum ValidationCode {
  // Intentionally empty. Implementation deferred to a future session.
}
