package sh.tamga.sdk.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * The policy attached to a license, modeling the subset of the {@code policies} resource this SDK
 * needs.
 *
 * <p><b>{@code max_memory} and {@code max_disk} are deliberately absent.</b> The server's GET
 * response omits both even though validation enforces them, so no client can introspect those two
 * limits -- it can only observe {@link ValidationCode#TOO_MUCH_MEMORY} or
 * {@link ValidationCode#TOO_MUCH_DISK} after the fact. They are not modeled at all rather than
 * modeled as perpetually-null fields.
 *
 * <p><b>Three strategy fields are exposed as raw strings.</b> Freshly created policies really do
 * report {@code "DENY_ACCESS"} for {@code overage_strategy} and {@code "NO_RESURRECTION"} for
 * {@code heartbeat_resurrection_strategy} -- neither is a real variant, and the server itself
 * treats both as the permissive "none" case. Comparing the raw value against an enum constant
 * therefore yields a false negative, and {@code "DENY_ACCESS"} in particular reads as though it
 * denies everything, which is not what happens. Always read them through
 * {@link #effectiveOverageStrategy()} and {@link #effectiveResurrectionStrategy()}.
 */
public final class Policy {

  private final String id;
  private final String name;
  private final String productId;
  private final String scheme;
  private final Integer maxMachines;
  private final Integer maxCores;
  private final Integer maxProcesses;
  private final Integer maxUsers;
  private final Integer maxUses;
  private final Long duration;
  private final Integer heartbeatDuration;
  private final Integer checkInIntervalCount;
  private final CheckInInterval checkInInterval;
  private final String overageStrategyRaw;
  private final String heartbeatCullStrategyRaw;
  private final String heartbeatResurrectionStrategyRaw;
  private final String machineUniquenessStrategy;
  private final String expirationStrategy;
  private final String expirationBasis;
  private final String renewalBasis;
  private final String authenticationStrategy;
  private final boolean requireCheckIn;
  private final boolean requireHeartbeat;
  private final boolean usePool;
  private final boolean encrypted;
  private final boolean floating;
  private final boolean strict;
  private final boolean protectedPolicy;
  private final Instant created;
  private final Instant updated;
  private final Map<String, Object> metadata;

  private Policy(String id, JsonNode attrs) {
    this.id = id;
    this.name = WireNodes.text(attrs, "name");
    this.productId = WireNodes.text(attrs, "product_id");
    this.scheme = WireNodes.text(attrs, "scheme");
    this.maxMachines = WireNodes.integer(attrs, "max_machines");
    this.maxCores = WireNodes.integer(attrs, "max_cores");
    this.maxProcesses = WireNodes.integer(attrs, "max_processes");
    this.maxUsers = WireNodes.integer(attrs, "max_users");
    this.maxUses = WireNodes.integer(attrs, "max_uses");
    this.duration = WireNodes.longValue(attrs, "duration");
    this.heartbeatDuration = WireNodes.integer(attrs, "heartbeat_duration");
    this.checkInIntervalCount = WireNodes.integer(attrs, "check_in_interval_count");
    this.checkInInterval = CheckInInterval.fromWireValue(WireNodes.text(attrs,
        "check_in_interval"));
    this.overageStrategyRaw = WireNodes.text(attrs, "overage_strategy");
    this.heartbeatCullStrategyRaw = WireNodes.text(attrs, "heartbeat_cull_strategy");
    this.heartbeatResurrectionStrategyRaw =
        WireNodes.text(attrs, "heartbeat_resurrection_strategy");
    this.machineUniquenessStrategy = WireNodes.text(attrs, "machine_uniqueness_strategy");
    this.expirationStrategy = WireNodes.text(attrs, "expiration_strategy");
    this.expirationBasis = WireNodes.text(attrs, "expiration_basis");
    this.renewalBasis = WireNodes.text(attrs, "renewal_basis");
    this.authenticationStrategy = WireNodes.text(attrs, "authentication_strategy");
    this.requireCheckIn = WireNodes.bool(attrs, "require_check_in");
    this.requireHeartbeat = WireNodes.bool(attrs, "require_heartbeat");
    this.usePool = WireNodes.bool(attrs, "use_pool");
    this.encrypted = WireNodes.bool(attrs, "encrypted");
    this.floating = WireNodes.bool(attrs, "floating");
    this.strict = WireNodes.bool(attrs, "strict");
    this.protectedPolicy = WireNodes.bool(attrs, "protected");
    this.created = WireNodes.instant(attrs, "created");
    this.updated = WireNodes.instant(attrs, "updated");
    this.metadata = WireNodes.objectMap(attrs, "metadata");
  }

  /** Decodes a single {@code {id, type, attributes}} policy resource node. */
  public static Policy fromResourceNode(JsonNode resource) {
    if (resource == null || resource.isNull()) {
      return null;
    }
    return new Policy(WireNodes.text(resource, "id"), resource.path("attributes"));
  }

  /** Returns the policy's unique id. */
  public String id() {
    return id;
  }

  /** Returns the policy's display name. */
  public String name() {
    return name;
  }

  /** Returns the id of the product this policy belongs to. */
  public String productId() {
    return productId;
  }

  /** Returns the key/checkout signing scheme configured on this policy, as a raw wire string. */
  public String scheme() {
    return scheme;
  }

  /** Returns the machine limit, or {@code null} when unlimited. */
  public Integer maxMachines() {
    return maxMachines;
  }

  /** Returns the core limit, or {@code null} when unlimited. */
  public Integer maxCores() {
    return maxCores;
  }

  /** Returns the process limit, or {@code null} when unlimited. */
  public Integer maxProcesses() {
    return maxProcesses;
  }

  /** Returns the user limit, or {@code null} when unlimited. */
  public Integer maxUsers() {
    return maxUsers;
  }

  /** Returns the use limit, or {@code null} when unlimited. Uses ignore the overage strategy. */
  public Integer maxUses() {
    return maxUses;
  }

  /** Returns the license duration in seconds, or {@code null} when perpetual. */
  public Long duration() {
    return duration;
  }

  /**
   * Returns {@code policy.heartbeat_duration}, the machine heartbeat window in seconds. When set
   * this <b>is</b> the effective window; the server falls back to 600 seconds only when it is
   * null. Note that {@link sh.tamga.sdk.HeartbeatScheduler}'s default interval is derived from
   * that 600s fallback, so a policy with a shorter window needs an explicit interval.
   */
  public Integer heartbeatDuration() {
    return heartbeatDuration;
  }

  /** Returns how many {@link #checkInInterval()} units make up one check-in period. */
  public Integer checkInIntervalCount() {
    return checkInIntervalCount;
  }

  /** Returns the check-in cadence unit. */
  public CheckInInterval checkInInterval() {
    return checkInInterval;
  }

  /**
   * Returns the raw {@code overage_strategy} string. Prefer
   * {@link #effectiveOverageStrategy()}.
   */
  public String overageStrategyRaw() {
    return overageStrategyRaw;
  }

  /** Returns the raw {@code heartbeat_cull_strategy} string. */
  public String heartbeatCullStrategyRaw() {
    return heartbeatCullStrategyRaw;
  }

  /** Returns the raw {@code heartbeat_resurrection_strategy} string. */
  public String heartbeatResurrectionStrategyRaw() {
    return heartbeatResurrectionStrategyRaw;
  }

  /** Returns the machine-uniqueness strategy as a raw wire string. */
  public String machineUniquenessStrategy() {
    return machineUniquenessStrategy;
  }

  /** Returns the expiration strategy. Free text server-side; see {@link ExpirationStrategy}. */
  public String expirationStrategy() {
    return expirationStrategy;
  }

  /** Returns the expiration basis as a raw wire string. */
  public String expirationBasis() {
    return expirationBasis;
  }

  /** Returns the renewal basis. Free text server-side; see {@link RenewalBasis}. */
  public String renewalBasis() {
    return renewalBasis;
  }

  /** Returns the authentication strategy. Free text; see {@link AuthenticationStrategy}. */
  public String authenticationStrategy() {
    return authenticationStrategy;
  }

  /** Returns whether licences under this policy must periodically check in. */
  public boolean requireCheckIn() {
    return requireCheckIn;
  }

  /** Returns whether machines under this policy must send heartbeats. */
  public boolean requireHeartbeat() {
    return requireHeartbeat;
  }

  /** Returns whether this policy draws keys from a pool. */
  public boolean usePool() {
    return usePool;
  }

  /** Returns whether checkout files under this policy are encrypted. */
  public boolean encrypted() {
    return encrypted;
  }

  /** Returns whether licences under this policy are floating. */
  public boolean floating() {
    return floating;
  }

  /** Returns whether licences under this policy are strict. */
  public boolean strict() {
    return strict;
  }

  /** Returns whether this policy is protected. */
  public boolean isProtected() {
    return protectedPolicy;
  }

  /** Returns when the policy was created, or {@code null}. */
  public Instant created() {
    return created;
  }

  /** Returns when the policy was last updated, or {@code null}. */
  public Instant updated() {
    return updated;
  }

  /** Returns an unmodifiable view of arbitrary metadata, or {@code null}. */
  public Map<String, Object> metadata() {
    return metadata == null ? null : Collections.unmodifiableMap(metadata);
  }

  /**
   * Normalizes {@link #overageStrategyRaw()} to a real variant, falling back to
   * {@link OverageStrategy#NO_OVERAGE} for anything unrecognized -- which includes the real-world
   * policy-create default {@code "DENY_ACCESS"}, a string the server itself treats as no overage.
   */
  public OverageStrategy effectiveOverageStrategy() {
    return OverageStrategy.fromWireValue(overageStrategyRaw);
  }

  /**
   * Normalizes {@link #heartbeatResurrectionStrategyRaw()} to a real variant, falling back to
   * {@link HeartbeatResurrectionStrategy#NO_REVIVE} for anything unrecognized -- which includes the
   * real-world policy-create default {@code "NO_RESURRECTION"}.
   */
  public HeartbeatResurrectionStrategy effectiveResurrectionStrategy() {
    return HeartbeatResurrectionStrategy.fromWireValue(heartbeatResurrectionStrategyRaw);
  }

  /**
   * Normalizes {@link #heartbeatCullStrategyRaw()} to a real variant, falling back to
   * {@link HeartbeatCullStrategy#DEACTIVATE_DEAD}, the server's own default.
   */
  public HeartbeatCullStrategy effectiveCullStrategy() {
    return HeartbeatCullStrategy.fromWireValue(heartbeatCullStrategyRaw);
  }

  /**
   * How far past a numeric limit a license may go before validation fails. Never applies to
   * {@code uses}, which the server always compares strictly.
   */
  public enum OverageStrategy {
    /** Enforces the limit strictly: {@code count <= max}. */
    NO_OVERAGE("NO_OVERAGE", 1.0d),
    /** Allows up to 125% of the limit. */
    ALLOW_1_25X_OVERAGE("ALLOW_1_25X_OVERAGE", 1.25d),
    /** Allows up to 150% of the limit. */
    ALLOW_1_5X_OVERAGE("ALLOW_1_5X_OVERAGE", 1.5d),
    /** Allows up to 200% of the limit. */
    ALLOW_2X_OVERAGE("ALLOW_2X_OVERAGE", 2.0d),
    /** Skips limit enforcement entirely. */
    ALWAYS_ALLOW_OVERAGE("ALWAYS_ALLOW_OVERAGE", Double.POSITIVE_INFINITY);

    private final String wireValue;
    private final double multiplier;

    OverageStrategy(String wireValue, double multiplier) {
      this.wireValue = wireValue;
      this.multiplier = multiplier;
    }

    /** Maps a wire string to a variant, falling back to {@link #NO_OVERAGE}. */
    public static OverageStrategy fromWireValue(String raw) {
      if (raw != null) {
        for (OverageStrategy strategy : values()) {
          if (strategy.wireValue.equals(raw)) {
            return strategy;
          }
        }
      }
      return NO_OVERAGE;
    }

    /**
     * Reports whether {@code count} is permitted against {@code max} under this strategy, mirroring
     * the server's own floating-point comparison so a client-side pre-check reaches the identical
     * verdict.
     */
    public boolean allows(long count, long max) {
      if (this == ALWAYS_ALLOW_OVERAGE) {
        return true;
      }
      if (this == NO_OVERAGE) {
        return count <= max;
      }
      return (double) count <= (double) max * multiplier;
    }
  }

  /**
   * What the server's cull job does to a machine row once its heartbeat window elapses --
   * <b>only when {@code require_heartbeat} is set</b>.
   *
   * <p>That column defaults to {@code false}, and the cull job early-returns on a policy that
   * leaves it there, so on a default policy this strategy never runs and no machine is ever
   * culled. A machine reporting {@link HeartbeatStatus#DEAD} is therefore not evidence that
   * anything happened to its row: see {@link HeartbeatStatus#DEAD}.
   */
  public enum HeartbeatCullStrategy {
    /**
     * Deletes the machine row once its window elapses. The server's default -- but inert unless
     * the policy also sets {@code require_heartbeat}.
     */
    DEACTIVATE_DEAD("DEACTIVATE_DEAD"),
    /** Keeps the machine row in place after its window elapses. */
    KEEP_DEAD("KEEP_DEAD");

    private final String wireValue;

    HeartbeatCullStrategy(String wireValue) {
      this.wireValue = wireValue;
    }

    /** Maps a wire string to a variant, falling back to {@link #DEACTIVATE_DEAD}. */
    public static HeartbeatCullStrategy fromWireValue(String raw) {
      if (raw != null) {
        for (HeartbeatCullStrategy strategy : values()) {
          if (strategy.wireValue.equals(raw)) {
            return strategy;
          }
        }
      }
      return DEACTIVATE_DEAD;
    }
  }

  /**
   * The grace window the server's cull job honours after a machine's heartbeat window elapses,
   * during which it revives the row rather than applying the cull strategy.
   *
   * <p>This bounds the <b>cull job</b>, not the ping endpoint. A client ping to a machine
   * reporting {@link HeartbeatStatus#DEAD} always succeeds and always revives it -- the write is a
   * bare {@code SET last_heartbeat_at = NOW()} with no resurrection check -- so nothing here is a
   * reason to stop pinging a {@code DEAD} machine.
   */
  public enum HeartbeatResurrectionStrategy {
    /** No grace window. */
    NO_REVIVE("NO_REVIVE"),
    /** One minute of grace. */
    REVIVE_1_MINUTE("1_MINUTE_REVIVE"),
    /** Two minutes of grace. */
    REVIVE_2_MINUTE("2_MINUTE_REVIVE"),
    /** Five minutes of grace. */
    REVIVE_5_MINUTE("5_MINUTE_REVIVE"),
    /** Ten minutes of grace. */
    REVIVE_10_MINUTE("10_MINUTE_REVIVE"),
    /** Fifteen minutes of grace. */
    REVIVE_15_MINUTE("15_MINUTE_REVIVE"),
    /** Always revive, with no time bound. */
    ALWAYS_REVIVE("ALWAYS_REVIVE");

    private final String wireValue;

    HeartbeatResurrectionStrategy(String wireValue) {
      this.wireValue = wireValue;
    }

    /** Maps a wire string to a variant, falling back to {@link #NO_REVIVE}. */
    public static HeartbeatResurrectionStrategy fromWireValue(String raw) {
      if (raw != null) {
        for (HeartbeatResurrectionStrategy strategy : values()) {
          if (strategy.wireValue.equals(raw)) {
            return strategy;
          }
        }
      }
      return NO_REVIVE;
    }

    /** Returns this strategy's wire value. */
    public String wireValue() {
      return wireValue;
    }
  }

  /**
   * The check-in cadence unit.
   *
   * <p>Its wire values are <b>lowercase</b> ({@code day}/{@code week}/{@code month}/{@code year}),
   * the single casing exception among the protocol's otherwise uppercase enums.
   */
  public enum CheckInInterval {
    /** Wire value {@code day}. */
    DAY("day"),
    /** Wire value {@code week}. */
    WEEK("week"),
    /** Wire value {@code month}. */
    MONTH("month"),
    /** Wire value {@code year}. */
    YEAR("year");

    private final String wireValue;

    CheckInInterval(String wireValue) {
      this.wireValue = wireValue;
    }

    /** Maps a lowercase wire string to a variant, or {@code null} when absent or unrecognized. */
    public static CheckInInterval fromWireValue(String raw) {
      if (raw != null) {
        for (CheckInInterval interval : values()) {
          if (interval.wireValue.equals(raw)) {
            return interval;
          }
        }
      }
      return null;
    }

    /** Returns this interval's lowercase wire value. */
    public String wireValue() {
      return wireValue;
    }
  }

  /**
   * Documented values for {@code expiration_strategy}. The server branches on a literal string
   * match rather than validating against a closed set, so any other value is legal on the wire --
   * which is why {@link Policy#expirationStrategy()} returns a raw {@code String}.
   */
  public static final class ExpirationStrategy {
    /** The server's default: access is restricted once the license expires. */
    public static final String RESTRICT_ACCESS = "RESTRICT_ACCESS";
    /** Access is maintained past expiry. */
    public static final String MAINTAIN_ACCESS = "MAINTAIN_ACCESS";
    /** Access is allowed past expiry. */
    public static final String ALLOW_ACCESS = "ALLOW_ACCESS";
    /**
     * Access is revoked at expiry, and this is the only one of the four that changes
     * <b>authentication</b> rather than just the validation verdict: an expired license under
     * {@code REVOKE_ACCESS} is refused at the front door with
     * {@code 401 LICENSE_EXPIRED}, so no endpoint answers at all. Under the other three an
     * expired license still authenticates and validate reports {@code EXPIRED}.
     */
    public static final String REVOKE_ACCESS = "REVOKE_ACCESS";

    private ExpirationStrategy() {
    }
  }

  /** Documented values for {@code renewal_basis}. Free text server-side, as above. */
  public static final class RenewalBasis {
    /** The server's default: renew from the current expiry. */
    public static final String FROM_EXPIRY = "FROM_EXPIRY";
    /** Renew from the moment of renewal. */
    public static final String FROM_NOW = "FROM_NOW";

    private RenewalBasis() {
    }
  }

  /** Documented values for {@code authentication_strategy}. Free text server-side, as above. */
  public static final class AuthenticationStrategy {
    /**
     * The server's default, and the reason license-key authentication is <b>off unless someone
     * turned it on</b>: the column defaults to this value and it refuses
     * {@code Authorization: License <key>} with {@code 401 LICENSE_NOT_ALLOWED}.
     */
    public static final String TOKEN = "TOKEN";
    /** License-key authentication. One of the two values that permit it. */
    public static final String LICENSE = "LICENSE";
    /** Either token or license-key authentication. The other value that permits it. */
    public static final String MIXED = "MIXED";
    /**
     * No strategy configured. Behaves exactly like {@link #TOKEN} at the authentication gate --
     * license-key credentials are refused with {@code 401 LICENSE_NOT_ALLOWED}. It does not mean
     * "no authentication required".
     */
    public static final String NONE = "NONE";

    private AuthenticationStrategy() {
    }
  }
}
