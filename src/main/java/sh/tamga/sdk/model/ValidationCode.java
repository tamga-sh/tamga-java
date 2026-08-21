package sh.tamga.sdk.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/**
 * The license validation result code returned as {@code meta.code} by all three validation
 * endpoints.
 *
 * <p>{@code code} is stable and is what callers should branch on. The sibling {@code detail} field
 * is human-readable text whose wording may change between server versions -- never match on it.
 *
 * <p>All 24 wire values are modeled for schema completeness, but <b>only 16 are reachable</b>
 * against the server today. Each constant below is marked reachable or unreachable; do not build
 * product behaviour around an unreachable one. Unknown future values decode to {@link #UNKNOWN}
 * rather than throwing, so a server-side addition can never break a released SDK.
 */
public enum ValidationCode {

  /** All checks passed. Reachable. */
  VALID,
  /** {@code license.suspended} is true. Reachable. */
  SUSPENDED,
  /** The license expiry is in the past. Reachable. */
  EXPIRED,
  /** Check-in is required and the window elapsed. Reachable. */
  OVERDUE,
  /** {@code scope.product} was set and did not match. Reachable. */
  PRODUCT_SCOPE_MISMATCH,
  /** {@code scope.policy} was set and did not match. Reachable. */
  POLICY_SCOPE_MISMATCH,
  /** {@code scope.user} was set and did not match. Reachable. */
  USER_SCOPE_MISMATCH,
  /** {@code scope.environment} was set and did not match. Reachable. */
  ENVIRONMENT_SCOPE_MISMATCH,
  /** Machine count exceeded the policy limit, as adjusted by the overage strategy. Reachable. */
  TOO_MANY_MACHINES,
  /** Core count exceeded {@code policy.max_cores}. Reachable. */
  TOO_MANY_CORES,
  /** Memory exceeded {@code policy.max_memory}. Reachable. */
  TOO_MUCH_MEMORY,
  /** Disk exceeded {@code policy.max_disk}. Reachable. */
  TOO_MUCH_DISK,
  /** Process count exceeded {@code policy.max_processes}. Reachable. */
  TOO_MANY_PROCESSES,
  /**
   * Uses reached {@code max_uses}. Reachable. The comparison is a strict {@code >=} and overage
   * strategies never apply to uses, unlike every other limit above.
   */
  TOO_MANY_USES,

  /**
   * Unreachable: the handler returns a bare HTTP 404 rather than emitting this code. Declared for
   * schema completeness only.
   */
  NOT_FOUND,
  /** Unreachable: declared in the server's enum, never emitted. */
  BANNED,
  /**
   * {@code scope.entitlements} was set and the license does not hold every code in it. Reachable.
   *
   * <p>The comparison is over entitlement <b>codes</b>, case-insensitively and de-duplicated, and
   * is satisfied by policy-inherited entitlements as well as directly attached ones. An empty list
   * asserts nothing and can never produce this code.
   */
  ENTITLEMENTS_MISSING,
  /** Unreachable: declared in the server's enum, never emitted. */
  TOO_MANY_USERS,
  /** Unreachable: declared in the server's enum, never emitted. */
  HEARTBEAT_DEAD,
  /** Unreachable: declared in the server's enum, never emitted. */
  HEARTBEAT_NOT_STARTED,
  /**
   * {@code scope.fingerprint} was set and no machine on the license carries it. Reachable.
   *
   * <p>Matches against <b>any</b> machine row of the license, whatever its heartbeat status.
   */
  FINGERPRINT_SCOPE_MISMATCH,
  /** Unreachable: declared in the server's enum, never emitted. */
  COMPONENTS_SCOPE_MISMATCH,
  /**
   * Unreachable: setting {@code scope.checksum} no longer produces a mismatch verdict -- the
   * server rejects the whole validate call with {@code 422 SCOPE_NOT_SUPPORTED} before any check
   * runs. {@link Scope} therefore no longer sends the field.
   */
  CHECKSUM_SCOPE_MISMATCH,
  /**
   * Unreachable: setting {@code scope.version} rejects the whole validate call with
   * {@code 422 SCOPE_NOT_SUPPORTED} -- see {@link #CHECKSUM_SCOPE_MISMATCH}.
   */
  VERSION_SCOPE_MISMATCH,

  /**
   * Fallback for any code this SDK release does not recognize. Never sent by the server under this
   * name -- it exists so that a value added server-side after this SDK shipped decodes cleanly
   * instead of throwing.
   */
  @JsonEnumDefaultValue
  UNKNOWN;

  /**
   * Maps a raw wire string to a constant, falling back to {@link #UNKNOWN} for anything
   * unrecognized. Provided for callers decoding {@code meta.code} outside Jackson.
   */
  public static ValidationCode fromWireValue(String wireValue) {
    if (wireValue == null) {
      return UNKNOWN;
    }
    for (ValidationCode code : values()) {
      if (code.name().equals(wireValue)) {
        return code;
      }
    }
    return UNKNOWN;
  }

  /**
   * Maps a create-time limit error code from {@code POST /machines} to the equivalent validation
   * code, or {@code null} when the code is not one of them.
   *
   * <p>Machine creation enforces the policy's machine, core, memory and disk limits and rejects
   * with {@code 422 MACHINE_LIMIT_EXCEEDED} / {@code CORE_LIMIT_EXCEEDED} /
   * {@code MEMORY_LIMIT_EXCEEDED} / {@code DISK_LIMIT_EXCEEDED}. Validation reports the same four
   * conditions under different names, so an over-limit activation would otherwise surface as two
   * unrelated failure vocabularies depending on the policy's overage strategy. This mapping is
   * what lets {@code TamgaClient.activateMachine} report both as the same outcome.
   *
   * @param errorCode a {@code TamgaApiException.code()} value; {@code null} is tolerated
   */
  public static ValidationCode fromMachineLimitErrorCode(String errorCode) {
    if (errorCode == null) {
      return null;
    }
    switch (errorCode) {
      case "MACHINE_LIMIT_EXCEEDED":
        return TOO_MANY_MACHINES;
      case "CORE_LIMIT_EXCEEDED":
        return TOO_MANY_CORES;
      case "MEMORY_LIMIT_EXCEEDED":
        return TOO_MUCH_MEMORY;
      case "DISK_LIMIT_EXCEEDED":
        return TOO_MUCH_DISK;
      default:
        return null;
    }
  }

  /**
   * Returns whether this code is one of the 16 the server can actually emit today. Useful for
   * assertions and diagnostics; product logic should switch on the constant itself.
   */
  public boolean reachable() {
    switch (this) {
      case ENTITLEMENTS_MISSING:
      case FINGERPRINT_SCOPE_MISMATCH:
      case VALID:
      case SUSPENDED:
      case EXPIRED:
      case OVERDUE:
      case PRODUCT_SCOPE_MISMATCH:
      case POLICY_SCOPE_MISMATCH:
      case USER_SCOPE_MISMATCH:
      case ENVIRONMENT_SCOPE_MISMATCH:
      case TOO_MANY_MACHINES:
      case TOO_MANY_CORES:
      case TOO_MUCH_MEMORY:
      case TOO_MUCH_DISK:
      case TOO_MANY_PROCESSES:
      case TOO_MANY_USES:
        return true;
      default:
        return false;
    }
  }

  /**
   * Returns whether this code indicates the license exceeded a policy limit, which is the set that
   * triggers {@code TamgaClient.activateMachine}'s rollback of a just-created machine.
   */
  public boolean overLimit() {
    switch (this) {
      case TOO_MANY_MACHINES:
      case TOO_MANY_CORES:
      case TOO_MUCH_MEMORY:
      case TOO_MUCH_DISK:
      case TOO_MANY_PROCESSES:
        return true;
      default:
        return false;
    }
  }
}
