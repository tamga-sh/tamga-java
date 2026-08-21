package sh.tamga.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import sh.tamga.sdk.error.TamgaApiException;
import sh.tamga.sdk.error.TamgaError;
import sh.tamga.sdk.error.TamgaTransportException;
import sh.tamga.sdk.model.ResponseMetadata;
import sh.tamga.sdk.model.TamgaJsonMapper;

/**
 * The HTTP layer beneath {@link TamgaClient}: URL assembly, auth, headers, rate-limit retry, and
 * error mapping.
 *
 * <p>Callers do not use this type directly -- build a {@link TamgaClient} instead. It is public so
 * its behaviour is documented in one place, but every request method is package-private.
 *
 * <p><b>Path segments are never concatenated.</b> Every caller-supplied id is added through
 * {@link HttpUrl.Builder#addPathSegment}, which percent-encodes it, so an id containing a slash or
 * a dot-segment cannot escape its position in the path.
 *
 * <p><b>Rate limiting is handled here, not by the caller.</b> HTTP 429 is live server-side, and the
 * calls an embedded licensing client makes on a timer -- validate, heartbeat ping, check-in -- sit
 * inside the server's tight per-IP budget. Without backoff one throttled request becomes a
 * sustained burst that keeps the bucket empty and never recovers.
 */
public final class Transport {

  /**
   * The {@code Tamga-Version} value sent unless overridden. The server falls back to this same
   * value when the header is absent, but this SDK always sends it explicitly so a server-side API
   * revision cannot silently reshape responses underneath a released SDK.
   */
  static final String DEFAULT_API_VERSION = "1.8";

  /** Default number of retries for a rate-limited request. */
  static final int DEFAULT_MAX_RETRIES = 3;

  /** Upper bound applied to a server-supplied {@code Retry-After}, in seconds. */
  static final int MAX_RETRY_AFTER_SECONDS = 60;

  /** Largest exponent used for backoff, so the delay plateaus at 32 seconds plus jitter. */
  static final int MAX_BACKOFF_SHIFT = 5;

  /** Maximum accepted length of a sanitized {@code Tamga-Version} value. */
  static final int MAX_API_VERSION_LENGTH = 32;

  /**
   * Ceiling on how many bytes of a response body will be read into memory.
   *
   * <p>Without a cap, a compromised or hostile endpoint can drive the embedding application into
   * an {@code OutOfMemoryError} simply by answering with a very large or chunked body. The call
   * timeout bounds how long a response may take, not how large it may be, and a fast connection
   * delivers a great deal inside the default 45-second call timeout. This applies to error bodies
   * too, which are read before any credential has necessarily been accepted.
   *
   * <p>32 MiB is far above any legitimate response: the largest thing this API returns is a
   * checkout certificate measured in kilobytes.
   */
  static final long MAX_RESPONSE_BYTES = 32L * 1024L * 1024L;

  private static final String CONTENT_TYPE_JSON_API = "application/vnd.api+json";
  private static final MediaType MEDIA_TYPE_JSON_API = MediaType.parse(CONTENT_TYPE_JSON_API);

  /**
   * The {@code POST} paths safe to repeat after a 429: effectively idempotent, and precisely the
   * calls a client makes on a timer.
   *
   * <p>Creates are deliberately absent. Retrying {@code POST /machines} risks a second activation
   * burning a second seat, and only the caller knows whether that is acceptable.
   *
   * <p>Matching is by suffix, not substring, so each heartbeat action is listed in its own right.
   * {@code /actions/ping-heartbeat} does not end with {@code /actions/ping} -- that suffix only
   * matches a process ping -- and leaving it out meant a throttled heartbeat was dropped silently
   * and the machine went on to read {@code DEAD} (which is a staleness report, not a cull -- see
   * {@link HeartbeatScheduler}). Both heartbeat writes are bare
   * {@code SET last_heartbeat_at = NOW()} updates: repeating one cannot burn a seat or double
   * anything, and the rate limiter buckets per route pattern, so a whole fleet shares one budget
   * on exactly these paths and 429s them for each other.
   */
  private static final List<String> RETRYABLE_POST_SUFFIXES = Collections.unmodifiableList(
      Arrays.asList("/actions/validate", "/actions/validate-key", "/actions/check-in",
          "/actions/check-out", "/actions/ping", "/actions/ping-heartbeat",
          "/actions/reset-heartbeat"));

  private final OkHttpClient httpClient;
  private final HttpUrl baseUrl;
  private final String accountId;
  private final String apiVersion;
  private final String otp;
  private final String userAgent;
  private final AuthTransport auth;
  private final int maxRetries;
  /** Effective body-size ceiling. Overridable only so tests can exercise the cap cheaply. */
  private final long maxResponseBytes;
  /**
   * Jitter source. {@code null} in production so each call reads
   * {@link ThreadLocalRandom#current()} on its own thread -- a {@code ThreadLocalRandom} instance
   * must never be cached and shared, which is exactly what a field would do. Tests inject a seeded
   * {@link Random} to make backoff deterministic.
   */
  private final Random jitter;

  @SuppressWarnings("checkstyle:ParameterNumber")
  Transport(OkHttpClient httpClient, HttpUrl baseUrl, String accountId, String apiVersion,
      String otp, String userAgent, AuthTransport auth, int maxRetries, Random jitter,
      long maxResponseBytes) {
    this.maxResponseBytes = maxResponseBytes;
    this.httpClient = httpClient;
    this.baseUrl = baseUrl;
    this.accountId = accountId;
    this.apiVersion = apiVersion;
    this.otp = otp;
    this.userAgent = userAgent;
    this.auth = auth;
    this.maxRetries = maxRetries;
    this.jitter = jitter;
  }

  /**
   * Filters a {@code Tamga-Version} value to the server's accepted character set and length.
   *
   * <p>Mirrors the server's own filter-then-truncate order exactly: disallowed characters are
   * dropped rather than replaced, and only then is the result truncated. Truncating first would
   * produce a different string for the same input.
   */
  static String sanitizeVersion(String version) {
    if (version == null) {
      return "";
    }
    StringBuilder out = new StringBuilder(Math.min(version.length(), MAX_API_VERSION_LENGTH));
    for (int i = 0; i < version.length() && out.length() < MAX_API_VERSION_LENGTH; i++) {
      char c = version.charAt(i);
      boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
          || c == '.' || c == '-';
      if (allowed) {
        out.append(c);
      }
    }
    return out.toString();
  }

  /** Reports whether a request is safe to repeat after a 429. */
  static boolean isRetryable(String method, String path) {
    if ("GET".equals(method)) {
      return true;
    }
    if (!"POST".equals(method)) {
      return false;
    }
    for (String suffix : RETRYABLE_POST_SUFFIXES) {
      if (path.endsWith(suffix)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Reads {@code Retry-After} as delta-seconds, returning {@code -1} when absent or unusable.
   *
   * <p>The HTTP-date form is ignored deliberately. The server sends seconds, and misreading a date
   * as a duration would be far worse than falling back to local backoff.
   */
  static int parseRetryAfterSeconds(String headerValue) {
    if (headerValue == null) {
      return -1;
    }
    String trimmed = headerValue.trim();
    if (trimmed.isEmpty()) {
      return -1;
    }
    try {
      int seconds = Integer.parseInt(trimmed);
      return seconds < 0 ? -1 : seconds;
    } catch (NumberFormatException ignored) {
      return -1;
    }
  }

  /**
   * Returns how long to wait before the retry numbered {@code attempt}, zero-based.
   *
   * <p>Prefers the server's {@code Retry-After} -- it knows when the bucket refills -- but caps it,
   * so a misconfigured or hostile proxy cannot park the caller for an hour on one header. Otherwise
   * exponential backoff with jitter, because a fleet that all retries on the same schedule
   * reconverges into the spike it was backing off from.
   */
  static long retryDelayMillis(int attempt, int retryAfterSeconds, Random jitter) {
    if (retryAfterSeconds >= 0) {
      return Math.min(retryAfterSeconds, MAX_RETRY_AFTER_SECONDS) * 1000L;
    }
    int shift = Math.min(attempt, MAX_BACKOFF_SHIFT);
    long base = (1L << shift) * 1000L;
    return base + jitter.nextInt(1000);
  }

  /** Returns a URL builder already positioned at {@code /v1/accounts/{accountId}}. */
  private HttpUrl.Builder accountUrl() {
    return baseUrl.newBuilder()
        .addPathSegment("v1")
        .addPathSegment("accounts")
        .addPathSegment(accountId);
  }

  /**
   * Builds a request URL from already-split path segments.
   *
   * <p>Segments are added individually so each is percent-encoded on its own. Never build a path by
   * string concatenation: a caller-supplied id containing {@code /} or {@code ..} would otherwise
   * change which endpoint is called.
   *
   * <p>{@code accountScoped} chooses the prefix. Almost every route lives under
   * {@code /v1/accounts/{accountId}}, and building that prefix unconditionally is exactly why no
   * SDK in this fleet could reach {@code /v1/health}, which sits at the origin root. The flag is
   * how a route escapes the account prefix without any caller being able to construct an arbitrary
   * URL: the segments still come from this class's own callers, never from the network.
   */
  private HttpUrl.Builder urlFor(List<String> segments, Map<String, String> query,
      boolean accountScoped) {
    HttpUrl.Builder builder = accountScoped ? accountUrl() : baseUrl.newBuilder();
    for (String segment : segments) {
      builder.addPathSegment(segment);
    }
    if (query != null) {
      for (Map.Entry<String, String> entry : query.entrySet()) {
        if (entry.getValue() != null) {
          builder.addQueryParameter(entry.getKey(), entry.getValue());
        }
      }
    }
    return builder;
  }

  /** Joins segments into the leading-slash path form {@link #isRetryable} matches against. */
  private static String pathOf(List<String> segments) {
    StringBuilder path = new StringBuilder();
    for (String segment : segments) {
      path.append('/').append(segment);
    }
    return path.toString();
  }

  JsonNode getJson(List<String> segments, Map<String, String> query) {
    return jsonResponse(send("GET", segments, query, null, CONTENT_TYPE_JSON_API, true));
  }

  /**
   * Issues a {@code GET} against a path that is <b>not</b> under {@code /v1/accounts/{accountId}},
   * accepting plain JSON.
   *
   * <p>Exactly one route needs this today: {@code GET /v1/health}, which is public, bypasses the
   * host-header check, and answers a bare {@code {status, version, uptime_secs}} object rather
   * than a JSON:API document. Credentials are still attached, per this SDK's rule that they are
   * sent even where the server does not require them.
   */
  JsonNode getRootJson(List<String> segments, Map<String, String> query) {
    return jsonResponse(send("GET", segments, query, null, "application/json", false));
  }

  /**
   * Issues a {@code GET} whose {@code 204 No Content} answer is a meaningful outcome rather than
   * an anomaly, returning {@code null} for it.
   *
   * <p>{@link #getJson} folds an empty body into an empty object, which is right for a response
   * that should have had content and is exactly wrong for the upgrade check, where {@code 204} is
   * the answer. Distinguishing them here is what lets a caller tell "no release was offered" from
   * "a release arrived that would not decode" -- the second must be an error, and a shared
   * empty-object return would have made the two identical.
   */
  JsonNode getJsonOrNoContent(List<String> segments, Map<String, String> query) {
    try (Response response = send("GET", segments, query, null, CONTENT_TYPE_JSON_API, true)) {
      throwIfError(response);
      return response.code() == 204 ? null : decodeJson(bodyBytes(response));
    }
  }

  JsonNode postJson(List<String> segments, Object body) {
    return jsonResponse(send("POST", segments, null, body, CONTENT_TYPE_JSON_API, true));
  }

  /**
   * Issues a {@code PATCH} with a JSON:API body.
   *
   * <p>Not retried after a {@code 429}: {@link #isRetryable} covers {@code GET} and a fixed list of
   * {@code POST} action suffixes, and adding a third verb to that list is a decision about
   * idempotence that belongs with the endpoint, not the transport. The one {@code PATCH} this SDK
   * sends merges with {@code COALESCE} server-side and would in fact be safe to repeat, which is
   * not a good enough reason to widen a rule that currently cannot let a create through.
   */
  JsonNode patchJson(List<String> segments, Object body) {
    return jsonResponse(send("PATCH", segments, null, body, CONTENT_TYPE_JSON_API, true));
  }

  void deleteNoContent(List<String> segments) {
    try (Response response = send("DELETE", segments, null, null, CONTENT_TYPE_JSON_API, true)) {
      throwIfError(response);
    }
  }

  String getText(List<String> segments, Map<String, String> query) {
    try (Response response =
        send("GET", segments, query, null, "application/octet-stream", true)) {
      throwIfError(response);
      return new String(bodyBytes(response), StandardCharsets.UTF_8);
    }
  }

  private JsonNode jsonResponse(Response response) {
    try (Response open = response) {
      throwIfError(open);
      return decodeJson(bodyBytes(open));
    }
  }

  private static JsonNode decodeJson(byte[] bytes) {
    if (bytes.length == 0) {
      return TamgaJsonMapper.instance().createObjectNode();
    }
    try {
      return TamgaJsonMapper.instance().readTree(bytes);
    } catch (IOException e) {
      throw new TamgaTransportException("Server returned a body that is not valid JSON.", e);
    }
  }

  @SuppressWarnings("checkstyle:ParameterNumber")
  private Response send(String method, List<String> segments, Map<String, String> query,
      Object body, String accept, boolean accountScoped) {
    byte[] encodedBody = encodeBody(body);
    String path = pathOf(segments);
    boolean retryable = isRetryable(method, path);

    for (int attempt = 0; ; attempt++) {
      Request request = buildRequest(method, segments, query, encodedBody, accept, accountScoped);
      Response response;
      try {
        response = httpClient.newCall(request).execute();
      } catch (IOException e) {
        throw new TamgaTransportException("Request to " + path + " failed.", e);
      }

      if (response.code() != 429 || !retryable || attempt >= maxRetries) {
        return response;
      }

      long delay = retryDelayMillis(attempt, parseRetryAfterSeconds(response.header("Retry-After")),
          jitter == null ? ThreadLocalRandom.current() : jitter);
      // Close before sleeping so the connection returns to the pool rather than being held for
      // the whole backoff.
      response.close();
      try {
        Thread.sleep(delay);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new TamgaTransportException("Interrupted while backing off from a rate limit.", e);
      }
    }
  }

  @SuppressWarnings("checkstyle:ParameterNumber")
  private Request buildRequest(String method, List<String> segments, Map<String, String> query,
      byte[] encodedBody, String accept, boolean accountScoped) {
    Request.Builder requestBuilder = new Request.Builder();

    // Content-Type is set only when there is a body, so a bodyless POST does not advertise a
    // payload it is not sending.
    RequestBody requestBody = null;
    if (encodedBody != null) {
      requestBody = RequestBody.create(encodedBody, MEDIA_TYPE_JSON_API);
    } else if ("POST".equals(method)) {
      requestBody = RequestBody.create(new byte[0], (MediaType) null);
    }

    requestBuilder.method(method, requestBody);
    requestBuilder.header("Accept", accept);
    requestBuilder.header("User-Agent", userAgent);
    requestBuilder.header("Tamga-Version", sanitizeVersion(apiVersion));
    if (otp != null && !otp.isEmpty()) {
      requestBuilder.header("Tamga-OTP", otp);
    }

    HttpUrl.Builder urlBuilder = urlFor(segments, query, accountScoped);
    auth.apply(urlBuilder, requestBuilder);
    return requestBuilder.url(urlBuilder.build()).build();
  }

  private static byte[] encodeBody(Object body) {
    if (body == null) {
      return null;
    }
    try {
      return TamgaJsonMapper.instance().writeValueAsBytes(body);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new TamgaTransportException("Failed to encode the request body.", e);
    }
  }

  /**
   * Reads a response body into memory, refusing to read more than {@link #MAX_RESPONSE_BYTES}.
   *
   * <p>Deliberately not {@code body().bytes()}, which is unbounded. A declared
   * {@code Content-Length} over the cap is rejected before a single byte is read; a body with no
   * declared length, or a lying one, is cut off mid-stream once the cap is passed.
   */
  private byte[] bodyBytes(Response response) {
    // Response.body is non-null in OkHttp 5 -- an absent body reads as zero bytes.
    ResponseBody body = response.body();
    long declaredLength = body.contentLength();
    if (declaredLength > maxResponseBytes) {
      throw new TamgaTransportException("Server declared a response body of " + declaredLength
          + " bytes, above this client's " + maxResponseBytes + " byte limit.");
    }
    try (InputStream stream = body.byteStream()) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] chunk = new byte[8192];
      long total = 0;
      int read;
      while ((read = stream.read(chunk)) != -1) {
        total += read;
        if (total > maxResponseBytes) {
          throw new TamgaTransportException("Server response body exceeded this client's "
              + maxResponseBytes + " byte limit.");
        }
        out.write(chunk, 0, read);
      }
      return out.toByteArray();
    } catch (IOException e) {
      throw new TamgaTransportException("Failed to read the response body.", e);
    }
  }

  /**
   * Converts a non-2xx response into the most specific {@link TamgaApiException} available.
   *
   * <p>The body is untrusted input from the network. An unreadable body, a non-JSON:API body, and
   * an empty {@code errors} array all degrade to a synthetic {@code UNKNOWN} error rather than
   * throwing a parse failure that would mask the real HTTP status.
   */
  private void throwIfError(Response response) {
    if (response.isSuccessful()) {
      return;
    }
    ResponseMetadata metadata = metadataOf(response);
    TamgaError error;
    try {
      byte[] bytes = bodyBytes(response);
      if (bytes.length == 0) {
        error = TamgaError.fromErrorDocument(null, "Server returned an empty error body.");
      } else {
        JsonNode document = TamgaJsonMapper.instance().readTree(bytes);
        error = TamgaError.fromErrorDocument(document,
            "Server returned a non-JSON:API error body.");
      }
    } catch (IOException | TamgaTransportException e) {
      error = TamgaError.fromErrorDocument(null, "Server error body could not be read.");
    }
    throw TamgaApiException.from(error, response.code(), metadata);
  }

  private static ResponseMetadata metadataOf(Response response) {
    return new ResponseMetadata(response.header("Tamga-Version"), response.header("Tamga-Edition"),
        response.header("Tamga-Mode"), response.header("X-Request-Id"));
  }
}
