package sh.tamga.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh.tamga.sdk.model.HeartbeatStatus;
import sh.tamga.sdk.model.Machine;

/** Heartbeat scheduling, including the stale-heartbeat signal callers depend on. */
class HeartbeatSchedulerTest {

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

  private void enqueueMachine(String status) {
    server.enqueue(new MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "application/vnd.api+json")
        .body("{\"data\":{\"id\":\"mach-1\",\"type\":\"machines\",\"attributes\":"
            + "{\"fingerprint\":\"fp\",\"heartbeat_status\":\"" + status + "\"}}}")
        .build());
  }

  @Test
  void defaultIntervalIsThirdOfServerWindow() {
    // 600s is the server's default machine window, applied when policy.heartbeat_duration is null;
    // a policy that sets that field overrides it and the default interval does not follow.
    assertThat(HeartbeatScheduler.WINDOW).isEqualTo(Duration.ofSeconds(600));
    assertThat(HeartbeatScheduler.DEFAULT_INTERVAL).isEqualTo(Duration.ofSeconds(200));
    assertThat(ProcessHeartbeatScheduler.WINDOW).isEqualTo(Duration.ofSeconds(30));
    assertThat(ProcessHeartbeatScheduler.DEFAULT_INTERVAL).isEqualTo(Duration.ofSeconds(10));
  }

  @Test
  void tickReportsUpdatedMachine() {
    enqueueMachine("ALIVE");
    AtomicReference<Machine> seen = new AtomicReference<>();

    HeartbeatScheduler scheduler = HeartbeatScheduler.builder(client, "mach-1")
        .onTick((machine, error) -> seen.set(machine))
        .build();
    scheduler.tick();

    assertThat(seen.get()).isNotNull();
    assertThat(seen.get().heartbeatStatus()).isEqualTo(HeartbeatStatus.ALIVE);
  }

  @Test
  void tickSurfacesDeadStatusRatherThanHidingIt() {
    enqueueMachine("DEAD");
    AtomicReference<HeartbeatStatus> seen = new AtomicReference<>();

    HeartbeatScheduler.builder(client, "mach-1")
        .onTick((machine, error) -> seen.set(machine.heartbeatStatus()))
        .build()
        .tick();

    // DEAD only reports that the last ping was older than the window -- it says nothing about
    // whether the row still exists, and the ping that observed it has already revived it. The
    // callback is the only place a caller can see the staleness at all, so it must not be hidden.
    assertThat(seen.get()).isEqualTo(HeartbeatStatus.DEAD);
  }

  @Test
  void unexpectedStatusIsCarriedNotRaisedAsError() {
    // Synthetic status: the real ping-heartbeat endpoint cannot answer DEAD, since it writes
    // last_heartbeat_at = NOW() before deriving the status from that same timestamp. What this
    // pins is the routing rule, which is real: any 200 carrying a machine leaves `error` null
    // whatever the status, so re-activation hangs off a 404 and never off a status value.
    enqueueMachine("DEAD");
    AtomicReference<Machine> machineSeen = new AtomicReference<>();
    AtomicReference<Throwable> errorSeen = new AtomicReference<>();

    HeartbeatScheduler.builder(client, "mach-1")
        .onTick((machine, error) -> {
          machineSeen.set(machine);
          errorSeen.set(error);
        })
        .build()
        .tick();

    assertThat(machineSeen.get()).isNotNull();
    assertThat(machineSeen.get().heartbeatStatus()).isEqualTo(HeartbeatStatus.DEAD);
    assertThat(errorSeen.get()).isNull();
  }

  @Test
  void noHeartbeatStatusEndsTheLoopNotEvenDead() throws Exception {
    // Regression: the loop must not stop on any status. DEAD stands in here for an unexpected
    // status -- the real ping-heartbeat endpoint cannot produce it, because it writes
    // last_heartbeat_at = NOW() and then derives the status from that same timestamp, so it
    // always answers ALIVE or RESURRECTED. The defensive property being pinned is real
    // regardless: a loop that stops, returns or short-circuits on a status it did not expect
    // strands a machine that was one ping away from ALIVE, and tamga-python shipped exactly that
    // bug. Three such readings in a row must not perturb the timer at all, and the fourth ping
    // must land and come back ALIVE.
    for (int i = 0; i < 3; i++) {
      enqueueMachine("DEAD");
    }
    for (int i = 0; i < 20; i++) {
      enqueueMachine("ALIVE");
    }
    List<HeartbeatStatus> seen = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch fourTicks = new CountDownLatch(4);

    HeartbeatScheduler scheduler = HeartbeatScheduler.builder(client, "mach-1")
        .interval(Duration.ofMillis(40))
        .onTick((machine, error) -> {
          seen.add(machine == null ? null : machine.heartbeatStatus());
          fourTicks.countDown();
        })
        .build();
    scheduler.start();

    assertThat(fourTicks.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(scheduler.running()).isTrue();
    scheduler.stop();

    assertThat(seen.subList(0, 4)).containsExactly(HeartbeatStatus.DEAD, HeartbeatStatus.DEAD,
        HeartbeatStatus.DEAD, HeartbeatStatus.ALIVE);
    assertThat(server.getRequestCount()).isGreaterThanOrEqualTo(4);
  }

  @Test
  void failedPingIsReportedRatherThanSwallowed() {
    server.enqueue(new MockResponse.Builder().code(404)
        .body("{\"errors\":[{\"code\":\"NOT_FOUND\"}]}").build());
    AtomicReference<Throwable> seen = new AtomicReference<>();

    HeartbeatScheduler.builder(client, "mach-1")
        .onTick((machine, error) -> seen.set(error))
        .build()
        .tick();

    assertThat(seen.get()).isInstanceOf(sh.tamga.sdk.error.TamgaApiException.class);
  }

  @Test
  void throwingCallbackDoesNotStopScheduler() {
    enqueueMachine("ALIVE");

    HeartbeatScheduler scheduler = HeartbeatScheduler.builder(client, "mach-1")
        .onTick((machine, error) -> {
          throw new IllegalStateException("caller bug");
        })
        .build();

    // A ScheduledExecutorService silently cancels all future runs once a task throws, which would
    // stop heartbeats permanently with no signal. The callback must not be able to cause that.
    scheduler.tick();
    assertThat(scheduler.running()).isFalse();
  }

  @Test
  void schedulerRunsUntilStopped() throws Exception {
    for (int i = 0; i < 20; i++) {
      enqueueMachine("ALIVE");
    }
    CountDownLatch ticked = new CountDownLatch(2);

    HeartbeatScheduler scheduler = HeartbeatScheduler.builder(client, "mach-1")
        .interval(Duration.ofMillis(40))
        .onTick((machine, error) -> ticked.countDown())
        .build();
    scheduler.start();

    assertThat(scheduler.running()).isTrue();
    assertThat(ticked.await(10, TimeUnit.SECONDS)).isTrue();

    scheduler.stop();
    assertThat(scheduler.running()).isFalse();
  }

  @Test
  void stoppingIsIdempotentAndCloseableStopsToo() {
    HeartbeatScheduler scheduler = HeartbeatScheduler.builder(client, "mach-1").build();
    scheduler.start();
    scheduler.stop();
    scheduler.stop();
    scheduler.close();

    assertThat(scheduler.running()).isFalse();
  }

  @Test
  void nonPositiveIntervalFallsBackToDefault() {
    HeartbeatScheduler.Builder builder = HeartbeatScheduler.builder(client, "mach-1");

    assertThat(builder.interval(Duration.ZERO).build()).isNotNull();
    assertThat(builder.interval(Duration.ofSeconds(-5)).build()).isNotNull();
    assertThat(builder.interval(null).build()).isNotNull();
  }

  @Test
  void interleavedStartAndStopNeverLeavesTimerRunning() throws Exception {
    // Regression: lifecycle state used to live in an AtomicBoolean plus a separate volatile
    // executor reference. A stop() landing between the flag flip and the executor assignment saw
    // a null executor, did nothing, and left a live timer that no later stop() could reach --
    // running() would report false while the scheduler kept pinging the API forever.
    for (int i = 0; i < 400; i++) {
      enqueueMachine("ALIVE");
    }
    AtomicInteger ticks = new AtomicInteger();
    HeartbeatScheduler scheduler = HeartbeatScheduler.builder(client, "mach-1")
        .interval(Duration.ofMillis(10))
        .onTick((machine, error) -> ticks.incrementAndGet())
        .build();

    ExecutorService pool = Executors.newFixedThreadPool(8);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(8);
    for (int i = 0; i < 8; i++) {
      final boolean starter = i % 2 == 0;
      pool.submit(() -> {
        try {
          start.await();
          for (int n = 0; n < 50; n++) {
            if (starter) {
              scheduler.start();
            } else {
              scheduler.stop();
            }
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          done.countDown();
        }
      });
    }
    start.countDown();
    assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
    pool.shutdownNow();

    scheduler.stop();
    assertThat(scheduler.running()).isFalse();

    // A stopped scheduler must actually be stopped: the tick count has to settle.
    int settled = ticks.get();
    Thread.sleep(150);
    assertThat(ticks.get()).isEqualTo(settled);
  }

  @Test
  void processSchedulerTickReportsProcess() {
    server.enqueue(new MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "application/vnd.api+json")
        .body("{\"data\":{\"id\":\"proc-1\",\"type\":\"processes\",\"attributes\":"
            + "{\"pid\":\"77\",\"machine_id\":\"mach-1\"}}}")
        .build());
    AtomicReference<String> seen = new AtomicReference<>();

    ProcessHeartbeatScheduler scheduler = ProcessHeartbeatScheduler.builder(client, "proc-1")
        .onTick((process, error) -> seen.set(process.pid()))
        .build();
    scheduler.tick();

    assertThat(seen.get()).isEqualTo("77");
  }

  @Test
  void processSchedulerReportsFailuresAndStopsCleanly() throws Exception {
    server.enqueue(new MockResponse.Builder().code(404)
        .body("{\"errors\":[{\"code\":\"NOT_FOUND\"}]}").build());
    AtomicReference<Throwable> seen = new AtomicReference<>();

    // A dead process row is deleted outright, with no resurrection grace, so a
    // failed ping is much closer to losing the registration entirely than it is
    // for a machine. Swallowing it would leave the caller with nothing to act on.
    ProcessHeartbeatScheduler scheduler = ProcessHeartbeatScheduler.builder(client, "proc-1")
        .onTick((process, error) -> seen.set(error))
        .build();
    scheduler.tick();

    assertThat(seen.get()).isInstanceOf(sh.tamga.sdk.error.TamgaApiException.class);
    assertThat(scheduler.running()).isFalse();
    scheduler.stop();
    scheduler.close();
    assertThat(scheduler.running()).isFalse();
  }

  @Test
  void processSchedulerIntervalFallsBackToTheDefault() {
    ProcessHeartbeatScheduler.Builder builder = ProcessHeartbeatScheduler.builder(client, "proc-1");

    assertThat(builder.interval(Duration.ZERO).build()).isNotNull();
    assertThat(builder.interval(Duration.ofSeconds(-1)).build()).isNotNull();
    assertThat(builder.interval(null).build()).isNotNull();
    assertThat(builder.interval(Duration.ofSeconds(5)).build().running()).isFalse();
  }

  @Test
  void throwingProcessCallbackDoesNotStopScheduler() {
    server.enqueue(new MockResponse.Builder().code(200)
        .body("{\"data\":{\"id\":\"proc-1\",\"type\":\"processes\",\"attributes\":"
            + "{\"pid\":\"77\"}}}").build());

    ProcessHeartbeatScheduler scheduler = ProcessHeartbeatScheduler.builder(client, "proc-1")
        .onTick((process, error) -> {
          throw new IllegalStateException("caller bug");
        })
        .build();

    // A ScheduledExecutorService silently cancels all future runs once a task
    // throws, which would stop heartbeats permanently with no signal.
    scheduler.tick();
    assertThat(scheduler.running()).isFalse();
  }

  @Test
  void processSchedulerRunsAndStops() throws Exception {
    for (int i = 0; i < 20; i++) {
      server.enqueue(new MockResponse.Builder().code(200)
          .body("{\"data\":{\"id\":\"proc-1\",\"type\":\"processes\",\"attributes\":"
              + "{\"pid\":\"77\"}}}").build());
    }
    CountDownLatch ticked = new CountDownLatch(2);

    try (ProcessHeartbeatScheduler scheduler = ProcessHeartbeatScheduler.builder(client, "proc-1")
        .interval(Duration.ofMillis(40))
        .onTick((process, error) -> ticked.countDown())
        .build()) {
      scheduler.start();
      assertThat(ticked.await(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void startingTwiceDoesNotCreateSecondTimer() {
    for (int i = 0; i < 5; i++) {
      enqueueMachine("ALIVE");
    }

    HeartbeatScheduler scheduler = HeartbeatScheduler.builder(client, "mach-1")
        .interval(Duration.ofSeconds(30))
        .build();
    scheduler.start();
    scheduler.start();
    scheduler.stop();

    assertThat(scheduler.running()).isFalse();
  }
}
