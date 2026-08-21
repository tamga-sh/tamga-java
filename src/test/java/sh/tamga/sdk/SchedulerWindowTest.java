package sh.tamga.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.Duration;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import sh.tamga.sdk.error.TamgaApiException;
import sh.tamga.sdk.model.Policy;
import sh.tamga.sdk.model.TamgaJsonMapper;

/**
 * Sizing the machine ping interval from the policy window, and disposing of a process registration
 * the server will never reap on its own.
 */
class SchedulerWindowTest {

  private MockWebServer server;
  private TamgaClient client;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
    client = TamgaClient.builder("acct-123")
        .host(server.url("/").toString())
        .auth(AuthTransport.licenseKey("k"))
        .build();
  }

  @AfterEach
  void tearDown() throws IOException {
    server.close();
  }

  private static Policy policyWith(String attributes) throws IOException {
    JsonNode node = TamgaJsonMapper.instance()
        .readTree("{\"id\":\"pol-1\",\"attributes\":" + attributes + "}");
    return Policy.fromResourceNode(node);
  }

  @Test
  void intervalForWindowIsThirdOfIt() {
    assertThat(HeartbeatScheduler.intervalForWindow(Duration.ofSeconds(600)))
        .isEqualTo(HeartbeatScheduler.DEFAULT_INTERVAL);
    assertThat(HeartbeatScheduler.intervalForWindow(Duration.ofSeconds(90)))
        .isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  void intervalForWindowFallsBackRatherThanReturningZero() {
    // A zero interval would busy-loop the timer, which is worse than pinging at the default rate.
    assertThat(HeartbeatScheduler.intervalForWindow(null))
        .isEqualTo(HeartbeatScheduler.DEFAULT_INTERVAL);
    assertThat(HeartbeatScheduler.intervalForWindow(Duration.ZERO))
        .isEqualTo(HeartbeatScheduler.DEFAULT_INTERVAL);
    assertThat(HeartbeatScheduler.intervalForWindow(Duration.ofSeconds(-5)))
        .isEqualTo(HeartbeatScheduler.DEFAULT_INTERVAL);
  }

  @Test
  void schedulerBuiltFromShortPolicyWindowPingsFasterThanTheDefault() throws Exception {
    // The whole point of M4: a policy with heartbeat_duration below 600 needs a faster ping than
    // DEFAULT_INTERVAL, and before this the SDK had no way to learn the window at all.
    Policy policy = policyWith("{\"heartbeat_duration\":90}");

    HeartbeatScheduler scheduler = HeartbeatScheduler.builder(client, "mach-1")
        .policy(policy)
        .build();

    assertThat(scheduler.running()).isFalse();
    assertThat(HeartbeatScheduler.intervalForWindow(policy.effectiveHeartbeatWindow()))
        .isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  void policyWithNoWindowKeepsTheSixHundredSecondFallback() throws Exception {
    Policy policy = policyWith("{}");

    assertThat(policy.effectiveHeartbeatWindow()).isEqualTo(HeartbeatScheduler.WINDOW);
    assertThat(HeartbeatScheduler.intervalForWindow(policy.effectiveHeartbeatWindow()))
        .isEqualTo(HeartbeatScheduler.DEFAULT_INTERVAL);
  }

  @Test
  void nullPolicyLeavesExplicitIntervalAlone() {
    // A failed policy read must not silently reset an interval the caller already chose.
    HeartbeatScheduler scheduler = HeartbeatScheduler.builder(client, "mach-1")
        .interval(Duration.ofSeconds(7))
        .policy(null)
        .build();

    assertThat(scheduler).isNotNull();
    assertThat(scheduler.running()).isFalse();
  }

  @Test
  void windowBuilderSizesTheIntervalWithoutPolicy() {
    HeartbeatScheduler scheduler = HeartbeatScheduler.builder(client, "mach-1")
        .window(Duration.ofSeconds(300))
        .build();

    assertThat(scheduler.running()).isFalse();
  }

  // --- The one-second interval floor, against the server's actual liveness rule ---------------
  //
  // The floor and the /3 divisor interact, and neither constant mentions the other. On a short
  // enough policy window the floor binds and the divisor's advertised "two consecutive losses"
  // stops holding. The table below is the single place that interaction is stated for this SDK,
  // so it is read rather than re-derived from two constants.
  //
  // The server's rule is NOT `age > window`. From tamga-api's
  // features/machines/model.rs::heartbeat_status_within (cited via tamga-js, which verified it
  // against chrono 0.4 -- this repo has no server checkout to re-read):
  //
  //     let age_secs = (Utc::now() - hb_ts).num_seconds();
  //     let within_window = age_secs <= window_secs;
  //
  // num_seconds() returns WHOLE seconds and truncates -- Duration::milliseconds(1999)
  // .num_seconds() == 1 -- so a machine first reads DEAD once its age reaches
  // (window_secs + 1) seconds. Every window carries one free second on top of its nominal value.
  // Restating that pessimistically as "DEAD once age passes the window" is what makes a 1s window
  // look unserveable at a 1s ping when it in fact has a full second of slack.

  /** The first millisecond age at which a read reports DEAD, on truncated whole seconds. */
  private static long deadAtAgeMillis(long windowSecs) {
    return (windowSecs + 1) * 1000L;
  }

  /**
   * Consecutive pings that can be lost before a read sees DEAD, for a scheduler ticking every
   * {@code intervalMillis}. After {@code m} misses the age reaches {@code (m + 1) * interval}.
   * {@code -1} means the window is not held even when no ping is lost at all.
   */
  private static long lossesTolerated(long windowSecs, long intervalMillis) {
    long deadAt = deadAtAgeMillis(windowSecs);
    // Ceiling division that stays correct for a negative dividend, which a negative window gives.
    // Math.ceilDiv is Java 18+ and this module compiles against the Java 11 API.
    return -Math.floorDiv(-deadAt, intervalMillis) - 2;
  }

  @Test
  void truncationGivesEveryWindowOneFreeSecondWhichIsWhatMakesOneSecondServeable() {
    assertThat(deadAtAgeMillis(1)).isEqualTo(2000);
    assertThat(deadAtAgeMillis(2)).isEqualTo(3000);
    assertThat(deadAtAgeMillis(600)).isEqualTo(601_000);

    // The pessimistic reading -- DEAD the instant age passes the nominal window -- would put a
    // 1s window's deadline at 1000ms and make the 1s floor a boundary case. It is 2000ms, so the
    // floor has twice the margin it is accused of lacking.
    assertThat(deadAtAgeMillis(1)).isGreaterThan(1000);
  }

  @ParameterizedTest(name = "heartbeat_duration {0} pings every {1}ms and survives {2} losses")
  @CsvSource({
      // heartbeat_duration, interval the scheduler actually uses (ms), consecutive losses tolerated
      "600, 200000,  2", // the 600s fallback window: the divisor governs, the floor is irrelevant
      "3,     1000,  2", // the first window where floor and divisor agree exactly (3s / 3 == 1s)
      "2,     1000,  1", // the floor binds: the divisor's promise degrades from 2 losses to 1
      "1,     1000,  0", // it binds hardest: steady state still holds, but with no spare ping
      "0,   200000, -1", // see windowZeroIsTheOneWindowTheFloorCannotHold below
  })
  void heartbeatDurationSizesTheIntervalAndFixesTheLossBudget(
      long duration, long expectedIntervalMillis, long expectedLosses) throws Exception {
    Policy policy = policyWith("{\"heartbeat_duration\":" + duration + "}");
    Duration expected = Duration.ofMillis(expectedIntervalMillis);

    // Pin the interval end to end -- policy attribute through to the value the timer is armed
    // with -- not just the static helper, so a regression anywhere on that path shows up here.
    assertThat(HeartbeatScheduler.intervalForWindow(policy.effectiveHeartbeatWindow()))
        .isEqualTo(expected);
    assertThat(HeartbeatScheduler.builder(client, "mach-1").policy(policy).build().interval())
        .isEqualTo(expected);

    assertThat(lossesTolerated(duration, expectedIntervalMillis)).isEqualTo(expectedLosses);
  }

  @Test
  void windowZeroIsTheOneWindowTheFloorCannotHold() throws Exception {
    // Not the 1s window -- the 0s one, which is the opposite of what a first reading predicts.
    // `heartbeat_duration` carries no CHECK constraint server-side, so 0 really is storable, and
    // truncation grants it exactly 1000ms of grace. Java's intervalForWindow treats a
    // non-positive window as "unspecified" and falls back to DEFAULT_INTERVAL, so the age has
    // long passed 1000ms before the first ping is even due.
    Policy policy = policyWith("{\"heartbeat_duration\":0}");

    assertThat(policy.effectiveHeartbeatWindow()).isEqualTo(Duration.ZERO);
    assertThat(HeartbeatScheduler.intervalForWindow(Duration.ZERO))
        .isEqualTo(HeartbeatScheduler.DEFAULT_INTERVAL);
    assertThat(deadAtAgeMillis(0)).isEqualTo(1000);
    assertThat(lossesTolerated(0, HeartbeatScheduler.DEFAULT_INTERVAL.toMillis())).isEqualTo(-1);

    // Deriving from a policy defaults the window BEFORE dividing, rather than dividing a zero and
    // letting the floor catch the result. Those two routes agree on the verdict and differ by 200x
    // in what reaching it costs: intervalForWindow(0) == intervalForWindow(WINDOW) == 200s, or 18
    // requests an hour, against the 3600 an hour a 1s-floored ping would send -- from every
    // machine that policy licenses, forever, to achieve nothing. That is the same self-inflicted
    // denial of service the floor exists to prevent, arrived at from the other side. tamga-go and
    // tamga-python take this route too; tamga-js floors to a 1000ms ping here, which is the
    // divergence, and this is the cheap side of it.
    assertThat(HeartbeatScheduler.intervalForWindow(Duration.ZERO))
        .isEqualTo(HeartbeatScheduler.intervalForWindow(HeartbeatScheduler.WINDOW));
    assertThat(lossesTolerated(0, HeartbeatScheduler.MINIMUM_INTERVAL.toMillis())).isEqualTo(-1);

    // ⚠️ STANDING CAVEAT -- this is the assertion that fails first if the premise ever moves.
    // A ~333ms ping WOULD hold window 0, because it keeps the age at 0 whole seconds. This SDK
    // deliberately does not chase that, and after the floor it can no longer be made to: buying
    // one nonsensical policy value would mean pinning the SDK's request rate to num_seconds()
    // truncation, a server implementation artifact rather than a protocol guarantee.
    //
    // Everything in this block rests on that truncation. If the server ever compares sub-second
    // -- `age <= window` on real fractions -- then window 0 becomes unserveable at ANY rate and
    // this expectation is exactly where that shows up, while window 1 stops having its free
    // second and becomes the genuine boundary case it is often mistaken for already. Nothing
    // else in the table moves: the loss counts for 600, 3, 2 and 1 are the same under either
    // rule. Re-derive this block against the server before trusting it if that day comes.
    assertThat(lossesTolerated(0, 333)).isGreaterThanOrEqualTo(0);
    assertThat(HeartbeatScheduler.builder(client, "mach-1")
        .interval(Duration.ofMillis(333)).build().interval())
        .isEqualTo(HeartbeatScheduler.MINIMUM_INTERVAL);
  }

  @Test
  void negativeWindowIsUnserveableAtAnyRate() {
    // `age_secs <= -30` is false for every non-negative age, so a negative window reads DEAD
    // unconditionally and there is nothing for any interval to chase.
    assertThat(deadAtAgeMillis(-30)).isNegative();
    assertThat(HeartbeatScheduler.intervalForWindow(Duration.ofSeconds(-30)))
        .isEqualTo(HeartbeatScheduler.DEFAULT_INTERVAL);
  }

  @Test
  void intervalForWindowFloorsTheDividedResultOnShortWindows() {
    // Three seconds is where the floor and the divisor meet exactly; above it the divisor
    // governs alone, below it the floor binds.
    assertThat(HeartbeatScheduler.intervalForWindow(Duration.ofSeconds(9)))
        .isEqualTo(Duration.ofSeconds(3));
    assertThat(HeartbeatScheduler.intervalForWindow(Duration.ofSeconds(3)))
        .isEqualTo(HeartbeatScheduler.MINIMUM_INTERVAL);
    assertThat(HeartbeatScheduler.intervalForWindow(Duration.ofSeconds(2)))
        .isEqualTo(HeartbeatScheduler.MINIMUM_INTERVAL);
    assertThat(HeartbeatScheduler.intervalForWindow(Duration.ofSeconds(1)))
        .isEqualTo(HeartbeatScheduler.MINIMUM_INTERVAL);
  }

  @Test
  void fiveHundredMillisecondIntervalBecomesOneSecond() {
    // The behaviour change, named outright so it is visible in the suite rather than only in
    // prose: half a second used to be honoured exactly and now is not. This is the input a unit
    // slip actually produces -- the parameter is a Duration while the policy field is
    // `heartbeat_duration` in SECONDS, so anyone converting by hand lands in this range.
    assertThat(HeartbeatScheduler.MINIMUM_INTERVAL).isEqualTo(Duration.ofSeconds(1));

    assertThat(HeartbeatScheduler.builder(client, "mach-1")
        .interval(Duration.ofMillis(500)).build().interval())
        .isEqualTo(Duration.ofSeconds(1));
    assertThat(ProcessHeartbeatScheduler.builder(client, "proc-1")
        .interval(Duration.ofMillis(500)).build().interval())
        .isEqualTo(Duration.ofSeconds(1));

    // A second is a floor, not a rewrite: anything already at or above it is untouched.
    assertThat(HeartbeatScheduler.builder(client, "mach-1")
        .interval(Duration.ofSeconds(1)).build().interval())
        .isEqualTo(Duration.ofSeconds(1));
    assertThat(HeartbeatScheduler.builder(client, "mach-1")
        .interval(Duration.ofSeconds(45)).build().interval())
        .isEqualTo(Duration.ofSeconds(45));
    assertThat(ProcessHeartbeatScheduler.builder(client, "proc-1")
        .interval(Duration.ofSeconds(45)).build().interval())
        .isEqualTo(Duration.ofSeconds(45));
  }

  @Test
  void nonPositiveIntervalStillFallsBackToTheDefaultRatherThanTheFloor() {
    // Null, zero and negative mean "unspecified", not "as fast as possible", so they keep the
    // documented DEFAULT_INTERVAL fallback. Sending them to the 1s floor instead would turn a
    // caller's `Duration.ZERO` into a 200x faster ping loop than the SDK has ever run.
    assertThat(HeartbeatScheduler.builder(client, "mach-1")
        .interval(Duration.ZERO).build().interval())
        .isEqualTo(HeartbeatScheduler.DEFAULT_INTERVAL);
    assertThat(HeartbeatScheduler.builder(client, "mach-1")
        .interval(Duration.ofSeconds(-5)).build().interval())
        .isEqualTo(HeartbeatScheduler.DEFAULT_INTERVAL);
    assertThat(HeartbeatScheduler.builder(client, "mach-1")
        .interval(null).build().interval())
        .isEqualTo(HeartbeatScheduler.DEFAULT_INTERVAL);
    assertThat(ProcessHeartbeatScheduler.builder(client, "proc-1")
        .interval(Duration.ZERO).build().interval())
        .isEqualTo(ProcessHeartbeatScheduler.DEFAULT_INTERVAL);
  }

  @Test
  void subMillisecondIntervalNoLongerReachesTheTimerAsZeroPeriod() throws Exception {
    // The old guard did not hold its own line. A positive sub-millisecond Duration is neither
    // zero nor negative, so it passed; then Duration.toMillis() truncated it to 0 and
    // scheduleAtFixedRate threw the very IllegalArgumentException the guard existed to prevent --
    // at start(), far from the builder call that caused it. Measured on this toolchain: period 0
    // and period -1 both throw, while period 1 is honoured exactly at ~1000 pings a second, which
    // is why guarding on what the executor rejects was never the right line to draw.
    Duration halfMillisecond = Duration.ofNanos(500_000);
    assertThat(halfMillisecond.isZero()).isFalse();
    assertThat(halfMillisecond.isNegative()).isFalse();
    assertThat(halfMillisecond.toMillis()).isZero();

    HeartbeatScheduler scheduler = HeartbeatScheduler.builder(client, "mach-1")
        .interval(halfMillisecond)
        .build();
    assertThat(scheduler.interval()).isEqualTo(HeartbeatScheduler.MINIMUM_INTERVAL);

    // start() is where it used to throw. It must now arm a once-a-second timer instead.
    scheduler.start();
    assertThat(scheduler.running()).isTrue();
    scheduler.stop();
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void policyDrivenShortWindowIsFlooredNotHonouredLiterally() throws Exception {
    // The reachable mistake end to end: a policy asking for a 1s window would divide to a 333ms
    // ping -- three requests a second from every machine on that policy -- and now does not.
    Policy policy = policyWith("{\"heartbeat_duration\":1}");

    assertThat(policy.effectiveHeartbeatWindow()).isEqualTo(Duration.ofSeconds(1));
    assertThat(HeartbeatScheduler.builder(client, "mach-1").policy(policy).build().interval())
        .isEqualTo(HeartbeatScheduler.MINIMUM_INTERVAL);
  }

  @Test
  void disposeStopsTheTimerAndDeletesTheProcessRow() throws Exception {
    server.enqueue(new MockResponse.Builder().code(204).build());

    ProcessHeartbeatScheduler scheduler =
        ProcessHeartbeatScheduler.builder(client, "proc-1").build();
    scheduler.start();
    assertThat(scheduler.running()).isTrue();

    scheduler.dispose();

    assertThat(scheduler.running()).isFalse();
    RecordedRequest request = server.takeRequest();
    assertThat(request.getMethod()).isEqualTo("DELETE");
    assertThat(request.getTarget()).isEqualTo("/v1/accounts/acct-123/processes/proc-1");
  }

  @Test
  void disposeIssuesAtMostOneDelete() throws Exception {
    server.enqueue(new MockResponse.Builder().code(204).build());

    ProcessHeartbeatScheduler scheduler =
        ProcessHeartbeatScheduler.builder(client, "proc-1").build();
    scheduler.dispose();
    scheduler.dispose();
    scheduler.dispose();

    assertThat(server.getRequestCount()).isEqualTo(1);
    server.takeRequest();
  }

  @Test
  void failedDisposeStaysRetryable() throws Exception {
    // The claim is released on failure, so a transient network error does not permanently wedge
    // the deletion the way a one-shot flag would.
    server.enqueue(new MockResponse.Builder().code(500)
        .addHeader("Content-Type", "application/vnd.api+json")
        .body("{\"errors\":[{\"id\":\"1\",\"status\":\"500\",\"code\":\"INTERNAL_SERVER_ERROR\","
            + "\"title\":\"t\",\"detail\":\"boom\"}]}").build());
    server.enqueue(new MockResponse.Builder().code(204).build());

    ProcessHeartbeatScheduler scheduler =
        ProcessHeartbeatScheduler.builder(client, "proc-1").build();

    assertThatThrownBy(scheduler::dispose)
        .isInstanceOf(TamgaApiException.InternalServerErrorException.class);
    scheduler.dispose();

    assertThat(server.getRequestCount()).isEqualTo(2);
    server.takeRequest();
    server.takeRequest();
  }

  @Test
  void closeStopsWithoutDeletingAnything() {
    // close() runs implicitly at the end of a try-with-resources block; a network write there
    // would delete server state the caller never asked it to.
    try (ProcessHeartbeatScheduler scheduler =
        ProcessHeartbeatScheduler.builder(client, "proc-1").build()) {
      scheduler.start();
    }

    assertThat(server.getRequestCount()).isZero();
  }
}
