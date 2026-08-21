package sh.tamga.sdk.model;

/**
 * Diagnostic response headers the server sets on every successful response and every API error.
 *
 * <p>Missing headers are the empty string rather than an error -- this is support/debugging
 * metadata, not something correctness depends on. In this SDK a metadata bag reaches the caller
 * through {@link sh.tamga.sdk.error.TamgaApiException#responseMetadata()}; the success path returns
 * the decoded resource alone.
 *
 * <p>The {@code x-ratelimit-*} headers are modeled separately, on {@link #rateLimit()}. The Javadoc
 * here previously called them "present in the server's CORS allowlist only, never set by a
 * handler", which was wrong on both counts: the rate-limit middleware writes
 * {@code x-ratelimit-limit}, {@code -remaining}, {@code -reset} <em>and</em> {@code -window} onto
 * the response it is about to return, and the CORS expose list is what additionally lets a browser
 * read them.
 *
 * <p>Deliberately not modeled: {@code Tamga-Environment}. That one really is unread -- the only
 * mention of it server-side is a comment marking where a future environment id would be taken
 * from.
 */
public final class ResponseMetadata {

  private final String tamgaVersion;
  private final String tamgaEdition;
  private final String tamgaMode;
  private final String requestId;
  private final RateLimitInfo rateLimit;

  /**
   * Creates a metadata bag from four already-extracted header values, with no rate-limit view.
   *
   * <p>Kept exactly as it was so code compiled against an earlier release keeps working; it now
   * delegates with {@link RateLimitInfo#absent()}, which is also what a response from a server with
   * rate limiting disabled would carry.
   */
  public ResponseMetadata(String tamgaVersion, String tamgaEdition, String tamgaMode,
      String requestId) {
    this(tamgaVersion, tamgaEdition, tamgaMode, requestId, RateLimitInfo.absent());
  }

  /** Creates a metadata bag from the four diagnostic headers plus the rate-limit view. */
  public ResponseMetadata(String tamgaVersion, String tamgaEdition, String tamgaMode,
      String requestId, RateLimitInfo rateLimit) {
    this.tamgaVersion = tamgaVersion == null ? "" : tamgaVersion;
    this.tamgaEdition = tamgaEdition == null ? "" : tamgaEdition;
    this.tamgaMode = tamgaMode == null ? "" : tamgaMode;
    this.requestId = requestId == null ? "" : requestId;
    this.rateLimit = rateLimit == null ? RateLimitInfo.absent() : rateLimit;
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

  /**
   * Returns the {@code x-ratelimit-*} view. Never {@code null} -- check
   * {@link RateLimitInfo#isPresent()}.
   */
  public RateLimitInfo rateLimit() {
    return rateLimit;
  }
}
