package sh.tamga.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Objects;
import java.util.Random;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh.tamga.sdk.error.TamgaApiException;
import sh.tamga.sdk.model.CreateMachineOptions;

/**
 * Rate-limit retry policy.
 *
 * <p>Which requests get retried is a correctness property, not a tuning knob: retrying a machine
 * create can burn a second seat, so that exclusion is asserted directly rather than inferred.
 *
 * <p>Tests that would otherwise sleep either send {@code Retry-After: 0} or assert against
 * {@link Transport#retryDelayMillis} as a pure function, so the suite stays fast and deterministic.
 */
class RateLimitTest {

  private MockWebServer server;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
  }

  @AfterEach
  void tearDown() throws IOException {
    server.close();
  }

  private TamgaClient client(int maxRetries) {
    return TamgaClient.builder("acct-123")
        .host(server.url("/").toString())
        .auth(AuthTransport.licenseKey("k"))
        .maxRetries(maxRetries)
        .jitter(new Random(1))
        .build();
  }

  private static MockResponse throttled() {
    return new MockResponse.Builder().code(429).addHeader("Retry-After", "0").build();
  }

  private static MockResponse validationSuccess() {
    return new MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "application/vnd.api+json")
        .body("{\"data\":{\"id\":\"lic-1\",\"type\":\"licenses\",\"attributes\":{}},"
            + "\"meta\":{\"ts\":\"2026-08-20T00:00:00Z\",\"valid\":true,\"detail\":\"is valid\","
            + "\"code\":\"VALID\"}}")
        .build();
  }

  @Test
  void throttledValidationIsRetriedThenSucceeds() {
    server.enqueue(throttled());
    server.enqueue(validationSuccess());

    assertThat(client(3).validateByKey("K").valid()).isTrue();
    assertThat(server.getRequestCount()).isEqualTo(2);
  }

  @Test
  void machineCreationIsNeverRetried() {
    server.enqueue(throttled());
    server.enqueue(new MockResponse.Builder().code(200)
        .body("{\"data\":{\"id\":\"m-1\",\"type\":\"machines\",\"attributes\":{}}}").build());

    // Retrying a create risks a second activation burning a second seat, so the 429 must surface
    // to the caller rather than being papered over.
    assertThatThrownBy(() -> client(3).createMachine(CreateMachineOptions.of("fp-1", "lic-1")))
        .isInstanceOf(TamgaApiException.class)
        .satisfies(t -> assertThat(((TamgaApiException) t).httpStatus()).isEqualTo(429));
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @Test
  void retryingIsDisabledWhenTheBudgetIsZero() {
    server.enqueue(throttled());

    assertThatThrownBy(() -> client(0).validateByKey("K"))
        .isInstanceOf(TamgaApiException.class)
        .satisfies(t -> assertThat(((TamgaApiException) t).httpStatus()).isEqualTo(429));
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @Test
  void theRetryBudgetIsFinite() {
    for (int i = 0; i < 5; i++) {
      server.enqueue(throttled());
    }

    assertThatThrownBy(() -> client(2).validateByKey("K"))
        .isInstanceOf(TamgaApiException.class);
    // One original attempt plus exactly two retries.
    assertThat(server.getRequestCount()).isEqualTo(3);
  }

  @Test
  void retriedRequestResendsItsBody() throws Exception {
    server.enqueue(throttled());
    server.enqueue(validationSuccess());

    client(3).validateByKey("SECRET-KEY");

    server.takeRequest();
    String retriedBody = bodyOf(server.takeRequest());
    // A streamed body would have been consumed by the first attempt, leaving the retry to send
    // an empty payload that the server would reject for an entirely unrelated reason.
    assertThat(retriedBody).contains("SECRET-KEY");
  }

  @Test
  void everyGetRequestIsRetryable() {
    assertThat(Transport.isRetryable("GET", "/licenses/lic-1/entitlements")).isTrue();
    assertThat(Transport.isRetryable("GET", "/anything/at/all")).isTrue();
  }

  @Test
  void onlyTheSevenSafePostActionsAreRetryable() {
    assertThat(Transport.isRetryable("POST", "/licenses/actions/validate-key")).isTrue();
    assertThat(Transport.isRetryable("POST", "/licenses/lic-1/actions/validate")).isTrue();
    assertThat(Transport.isRetryable("POST", "/licenses/lic-1/actions/check-in")).isTrue();
    assertThat(Transport.isRetryable("POST", "/licenses/lic-1/actions/check-out")).isTrue();
    assertThat(Transport.isRetryable("POST", "/processes/p-1/actions/ping")).isTrue();
    assertThat(Transport.isRetryable("POST", "/machines/m-1/actions/ping-heartbeat")).isTrue();
    assertThat(Transport.isRetryable("POST", "/machines/m-1/actions/reset-heartbeat")).isTrue();

    assertThat(Transport.isRetryable("POST", "/machines")).isFalse();
    assertThat(Transport.isRetryable("POST", "/components")).isFalse();
    assertThat(Transport.isRetryable("POST", "/processes")).isFalse();
    assertThat(Transport.isRetryable("DELETE", "/machines/m-1")).isFalse();
  }

  @Test
  void heartbeatWritesAreRetryableInTheirOwnRight() {
    // Both are bare `SET last_heartbeat_at = NOW()` updates -- repeating one cannot burn a seat.
    // Dropping a throttled heartbeat is what is actually dangerous: the machine goes on to be
    // culled, and the rate limiter buckets per route pattern, so a whole fleet 429s itself on
    // exactly this path.
    assertThat(Transport.isRetryable("POST", "/machines/m-1/actions/ping-heartbeat")).isTrue();
    assertThat(Transport.isRetryable("POST", "/machines/m-1/actions/reset-heartbeat")).isTrue();
  }

  @Test
  void matchingIsBySuffixNotSubstring() {
    // Each retryable action is listed in its own right precisely because "ping-heartbeat" does not
    // end with "/actions/ping". Nothing else that merely contains one of them matches.
    assertThat(Transport.isRetryable("POST", "/machines/m-1/actions/check-out-something"))
        .isFalse();
    assertThat(Transport.isRetryable("POST", "/machines/m-1/actions/generate-offline-proof"))
        .isFalse();
  }

  @Test
  void anAbsurdRetryAfterIsCapped() {
    long delay = Transport.retryDelayMillis(0, 100_000, new Random(1));

    assertThat(delay).isEqualTo(Transport.MAX_RETRY_AFTER_SECONDS * 1000L);
  }

  @Test
  void serverSuppliedRetryAfterIsHonoured() {
    assertThat(Transport.retryDelayMillis(0, 5, new Random(1))).isEqualTo(5_000L);
  }

  @Test
  void backoffGrowsWhenTheServerSaysNothing() {
    Random jitter = new Random(1);
    long first = Transport.retryDelayMillis(0, -1, jitter);
    long second = Transport.retryDelayMillis(1, -1, jitter);
    long third = Transport.retryDelayMillis(2, -1, jitter);

    assertThat(first).isBetween(1_000L, 2_000L);
    assertThat(second).isBetween(2_000L, 3_000L);
    assertThat(third).isBetween(4_000L, 5_000L);
  }

  @Test
  void backoffPlateausRatherThanGrowingWithoutBound() {
    long capped = Transport.retryDelayMillis(50, -1, new Random(1));

    // 2^5 seconds plus at most a second of jitter, no matter how high the attempt count goes.
    assertThat(capped).isBetween(32_000L, 33_000L);
  }

  @Test
  void backoffCarriesJitterSoFleetDoesNotResynchronize() {
    long a = Transport.retryDelayMillis(3, -1, new Random(1));
    long b = Transport.retryDelayMillis(3, -1, new Random(2));

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void anUnusableRetryAfterFallsBackToLocalBackoff() {
    // The HTTP-date form is deliberately not parsed: misreading a date as a duration would be far
    // worse than backing off locally.
    assertThat(Transport.parseRetryAfterSeconds("Wed, 21 Oct 2026 07:28:00 GMT")).isEqualTo(-1);
    assertThat(Transport.parseRetryAfterSeconds("-5")).isEqualTo(-1);
    assertThat(Transport.parseRetryAfterSeconds("")).isEqualTo(-1);
    assertThat(Transport.parseRetryAfterSeconds(null)).isEqualTo(-1);
    assertThat(Transport.parseRetryAfterSeconds(" 7 ")).isEqualTo(7);
  }

  /**
   * Reads a recorded request's body.
   *
   * <p>{@code getBody()} is nullable -- a request without a body has none -- so the null check is
   * real rather than ceremonial: a test asserting on body content against a bodyless request
   * should fail loudly here, not with an opaque dereference further down.
   */
  private static String bodyOf(RecordedRequest request) {
    return Objects.requireNonNull(request.getBody(), "recorded request had no body").utf8();
  }
}
