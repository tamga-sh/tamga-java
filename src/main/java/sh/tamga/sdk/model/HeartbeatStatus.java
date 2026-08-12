package sh.tamga.sdk.model;

/**
 * A machine's heartbeat state. GOTCHA: the 600s (10 min) heartbeat window is hardcoded
 * server-side, NOT driven by {@code policy.heartbeat_duration} -- a future heartbeat-scheduler
 * helper must not derive its ping interval from that field.
 */
public enum HeartbeatStatus {
  /** Wire value {@code NOT_STARTED} -- never pinged. */
  NOT_STARTED("NOT_STARTED"),
  /** Wire value {@code ALIVE} -- pinged within the window. */
  ALIVE("ALIVE"),
  /** Wire value {@code DEAD} -- window elapsed with no ping. */
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
