package sh.tamga.sdk.model;

/**
 * The four {@code x-ratelimit-*} headers the server attaches to a response while rate limiting is
 * active.
 *
 * <p>Every value is a non-negative count of requests or seconds, or {@link #ABSENT} when the header
 * was missing, empty, or not a non-negative integer. Absence is ordinary, not an error: the
 * middleware returns early when no limiter was built, which happens whenever the Redis pool could
 * not be created -- rate limiting is then disabled outright and none of the four headers is
 * written. Treat {@link #ABSENT} as "unknown", never as zero.
 *
 * <p><b>{@link #resetAt()} is an absolute Unix timestamp in seconds, not a delay.</b> The server
 * computes it as "now plus the bucket's remaining TTL", so sleeping for that number of seconds
 * parks the caller for the fifty-odd years since the epoch instead of the second or so until the
 * bucket refills. Subtract the current epoch second from it to get a duration.
 *
 * <p>The budget is bucketed per caller IP <em>and</em> per matched route pattern, so
 * {@link #remaining()} describes the budget for the one route just called, not an account-wide
 * allowance. {@link #window()} is that bucket's length in seconds and is currently always
 * {@code 1}, which makes {@link #limit()} a per-second figure whose value is the configured burst
 * capacity.
 *
 * <p>This is advisory telemetry for a caller that wants to slow itself down before it is throttled.
 * It does not replace the transport's own 429 handling, which retries the safe calls with backoff
 * regardless of what these headers say.
 */
public final class RateLimitInfo {

  /** Value of a field whose header was absent or unusable. Distinct from a genuine {@code 0}. */
  public static final long ABSENT = -1L;

  private static final RateLimitInfo NONE =
      new RateLimitInfo(ABSENT, ABSENT, ABSENT, ABSENT);

  private final long limit;
  private final long remaining;
  private final long resetAt;
  private final long window;

  private RateLimitInfo(long limit, long remaining, long resetAt, long window) {
    this.limit = limit;
    this.remaining = remaining;
    this.resetAt = resetAt;
    this.window = window;
  }

  /**
   * Returns the instance whose every field is {@link #ABSENT}.
   *
   * <p>What a response carries when rate limiting is disabled server-side, and what the four-value
   * {@link ResponseMetadata} constructor supplies for callers written before these headers were
   * modeled.
   */
  public static RateLimitInfo absent() {
    return NONE;
  }

  /**
   * Builds a rate-limit view from the four raw header values, any of which may be {@code null}.
   *
   * <p>Arguments are in header order: {@code x-ratelimit-limit}, {@code -remaining},
   * {@code -reset}, {@code -window}. An unparseable or negative value folds into {@link #ABSENT}
   * rather than throwing -- these headers are diagnostics, and a broken or hostile proxy
   * rewriting one must not turn a usable response into a client-side failure.
   */
  public static RateLimitInfo fromHeaders(String limit, String remaining, String reset,
      String window) {
    return new RateLimitInfo(parse(limit), parse(remaining), parse(reset), parse(window));
  }

  private static long parse(String value) {
    if (value == null) {
      return ABSENT;
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return ABSENT;
    }
    try {
      long parsed = Long.parseLong(trimmed);
      return parsed < 0 ? ABSENT : parsed;
    } catch (NumberFormatException ignored) {
      return ABSENT;
    }
  }

  /** Returns whether any of the four headers was present and usable. */
  public boolean isPresent() {
    return limit != ABSENT || remaining != ABSENT || resetAt != ABSENT || window != ABSENT;
  }

  /** Returns {@code x-ratelimit-limit}: requests allowed per window, or {@link #ABSENT}. */
  public long limit() {
    return limit;
  }

  /**
   * Returns {@code x-ratelimit-remaining}: requests left in the current window, or {@link #ABSENT}.
   *
   * <p>{@code 0} is a real answer meaning the bucket is empty, and is why {@link #ABSENT} is
   * {@code -1} rather than zero.
   */
  public long remaining() {
    return remaining;
  }

  /**
   * Returns {@code x-ratelimit-reset} as a Unix timestamp in seconds, or {@link #ABSENT}.
   *
   * <p>Absolute, not a delay -- see this class's note.
   */
  public long resetAt() {
    return resetAt;
  }

  /** Returns {@code x-ratelimit-window}: the bucket length in seconds, or {@link #ABSENT}. */
  public long window() {
    return window;
  }
}
