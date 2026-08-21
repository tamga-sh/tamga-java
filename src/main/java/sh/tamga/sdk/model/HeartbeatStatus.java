package sh.tamga.sdk.model;

/**
 * A machine's heartbeat state. The window is the policy's {@code heartbeat_duration} when that
 * field is set, and 600s (10 min) only when it is null. GOTCHA:
 * {@link sh.tamga.sdk.HeartbeatScheduler}'s default ping interval is derived from that 600s
 * fallback, so a policy with a shorter window needs an explicitly configured interval or the
 * machine will read {@link #DEAD} between pings.
 */
public enum HeartbeatStatus {
  /** Wire value {@code NOT_STARTED} -- never pinged. */
  NOT_STARTED("NOT_STARTED"),
  /** Wire value {@code ALIVE} -- pinged within the window. */
  ALIVE("ALIVE"),
  /**
   * Wire value {@code DEAD} -- the window elapsed with no ping. <b>Nothing more.</b>
   *
   * <p>It does not mean the machine was culled, deleted or deactivated. The server derives this
   * purely from {@code last_heartbeat_at} against the window and never consults the policy's
   * {@code require_heartbeat}, which defaults to {@code false} and is what the cull job requires
   * before it removes anything -- so on a default policy the row is never culled and reports
   * {@code DEAD} indefinitely, seat still consumed. A ping to a {@code DEAD} machine succeeds and
   * revives it. Keep pinging; treat a 404 from the ping, not this status, as the row-is-gone
   * signal.
   */
  DEAD("DEAD"),
  /**
   * Wire value {@code RESURRECTED} -- a new ping arrived after a death event was already
   * recorded, within the resurrection grace window.
   */
  RESURRECTED("RESURRECTED");

  private final String wireValue;

  HeartbeatStatus(String wireValue) {
    this.wireValue = wireValue;
  }

  /**
   * An unrecognized or missing value maps to {@link #NOT_STARTED} rather than throwing --
   * decoding must never crash on a status this SDK doesn't yet know about.
   */
  public static HeartbeatStatus fromWireValue(String wireValue) {
    if (wireValue == null) {
      return NOT_STARTED;
    }
    for (HeartbeatStatus status : values()) {
      if (status.wireValue.equals(wireValue)) {
        return status;
      }
    }
    return NOT_STARTED;
  }
}
