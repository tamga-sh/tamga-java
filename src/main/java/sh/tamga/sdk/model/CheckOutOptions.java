package sh.tamga.sdk.model;

/**
 * Options for checking out an offline {@code .lic} or {@code .machine} certificate.
 *
 * <p>The endpoint has two variants. {@code GET} returns the raw PEM as
 * {@code application/octet-stream}; {@code POST} returns a JSON:API resource whose
 * {@code attributes.certificate} holds the same PEM. Both yield the same certificate, so
 * {@link #usingPost()} is a transport preference rather than a behavioural one.
 */
public final class CheckOutOptions {

  /** The largest time-to-live the server accepts, in seconds -- one year. */
  public static final int MAX_TTL_SECONDS = 31_536_000;

  private final Integer ttl;
  private final boolean encrypt;
  private final boolean usePost;

  private CheckOutOptions(Integer ttl, boolean encrypt, boolean usePost) {
    this.ttl = ttl;
    this.encrypt = encrypt;
    this.usePost = usePost;
  }

  /** Returns options accepting the server's default time-to-live, unencrypted, over GET. */
  public static CheckOutOptions defaults() {
    return new CheckOutOptions(null, false, false);
  }

  /**
   * Returns a copy requesting the given time-to-live in seconds.
   *
   * @throws IllegalArgumentException if the value is outside {@code 0 < ttl <= }
   *     {@link #MAX_TTL_SECONDS}. Validated client-side so an obviously bad value fails before a
   *     round trip rather than as a 422.
   */
  public CheckOutOptions withTtl(int seconds) {
    if (seconds <= 0 || seconds > MAX_TTL_SECONDS) {
      throw new IllegalArgumentException(
          "ttl must be greater than 0 and at most " + MAX_TTL_SECONDS + " seconds, got " + seconds);
    }
    return new CheckOutOptions(seconds, encrypt, usePost);
  }

  /** Returns a copy requesting an encrypted certificate. */
  public CheckOutOptions withEncrypt(boolean value) {
    return new CheckOutOptions(ttl, value, usePost);
  }

  /** Returns a copy using the JSON:API POST variant instead of the raw GET variant. */
  public CheckOutOptions withUsePost(boolean value) {
    return new CheckOutOptions(ttl, encrypt, value);
  }

  /** Returns the requested time-to-live in seconds, or {@code null} for the server default. */
  public Integer ttl() {
    return ttl;
  }

  /** Returns whether an encrypted certificate was requested. */
  public boolean encrypt() {
    return encrypt;
  }

  /** Returns whether the POST variant should be used. */
  public boolean usingPost() {
    return usePost;
  }
}
