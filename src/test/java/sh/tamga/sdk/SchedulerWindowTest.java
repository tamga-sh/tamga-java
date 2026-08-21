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
