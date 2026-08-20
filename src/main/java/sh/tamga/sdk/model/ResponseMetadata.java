package sh.tamga.sdk.model;

/**
 * Diagnostic response headers carried alongside every successful response and every API error.
 *
 * <p>Missing headers are the empty string rather than an error -- this is support/debugging
 * metadata, not something correctness depends on.
 *
 * <p>Deliberately not modeled: {@code Tamga-Environment} (a planned feature no server code path
 * reads yet) and {@code X-RateLimit-*} (present in the server's CORS allowlist only, never set by
 * a handler).
 */
public final class ResponseMetadata {

  private final String tamgaVersion;
  private final String tamgaEdition;
  private final String tamgaMode;
  private final String requestId;

  /** Creates a metadata bag from four already-extracted header values. */
  public ResponseMetadata(String tamgaVersion, String tamgaEdition, String tamgaMode,
      String requestId) {
    this.tamgaVersion = tamgaVersion == null ? "" : tamgaVersion;
    this.tamgaEdition = tamgaEdition == null ? "" : tamgaEdition;
    this.tamgaMode = tamgaMode == null ? "" : tamgaMode;
    this.requestId = requestId == null ? "" : requestId;
  }

  /** Returns the {@code Tamga-Version} the server echoed back. */
  public String tamgaVersion() {
    return tamgaVersion;
  }

  /** Returns the {@code Tamga-Edition} header -- {@code "EE"} or {@code "CE"}. */
  public String tamgaEdition() {
    return tamgaEdition;
  }

  /** Returns the {@code Tamga-Mode} header -- {@code "singleplayer"} or {@code "multiplayer"}. */
  public String tamgaMode() {
    return tamgaMode;
  }

  /** Returns {@code X-Request-Id}. Log this: it correlates a client error with server-side logs. */
  public String requestId() {
    return requestId;
  }
}
