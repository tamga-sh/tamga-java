package sh.tamga.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The 60-second entitlement cache, both as a unit and through the client. */
class EntitlementCacheTest {

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

  private void enqueueEntitlements() {
    server.enqueue(new MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "application/vnd.api+json")
        .body("{\"data\":[{\"id\":\"ent-1\",\"type\":\"entitlements\",\"attributes\":"
            + "{\"code\":\"PRO\",\"name\":\"Pro plan\"}},{\"id\":\"ent-2\","
            + "\"type\":\"entitlements\",\"attributes\":{\"code\":\"BETA\",\"name\":\"Beta\"}}]}")
        .build());
  }

  @Test
  void hasEntitlementMatchesOnTheCodeNotTheName() {
    enqueueEntitlements();

    assertThat(client.hasEntitlement("lic-1", "PRO")).isTrue();
    // "Pro plan" is a display label; matching on it would break the moment someone renames it.
    assertThat(client.hasEntitlement("lic-1", "Pro plan")).isFalse();
  }

  @Test
  void secondLookupInsideWindowMakesNoSecondRequest() {
    enqueueEntitlements();

    assertThat(client.hasEntitlement("lic-1", "PRO")).isTrue();
    assertThat(client.hasEntitlement("lic-1", "BETA")).isTrue();
    assertThat(client.hasEntitlement("lic-1", "NOPE")).isFalse();

    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @Test
  void invalidatingForcesRefetch() {
    enqueueEntitlements();
    assertThat(client.hasEntitlement("lic-1", "PRO")).isTrue();

    client.invalidateEntitlementCache("lic-1");
    enqueueEntitlements();
    assertThat(client.hasEntitlement("lic-1", "PRO")).isTrue();

    assertThat(server.getRequestCount()).isEqualTo(2);
  }

  @Test
  void separateLicensesAreCachedSeparately() {
    enqueueEntitlements();
    enqueueEntitlements();

    client.hasEntitlement("lic-1", "PRO");
    client.hasEntitlement("lic-2", "PRO");

    assertThat(server.getRequestCount()).isEqualTo(2);
  }

  @Test
  void theLookupRequestsTheServerMaximumPageSize() throws Exception {
    enqueueEntitlements();

    client.hasEntitlement("lic-1", "PRO");

    assertThat(server.takeRequest().getUrl().queryParameter("limit"))
        .isEqualTo(String.valueOf(TamgaClient.ENTITLEMENT_LOOKUP_PAGE_SIZE));
  }

  @Test
  void anEntryGoesStaleOnceTheWindowElapses() {
    AtomicLong now = new AtomicLong(0);
    EntitlementCache cache = new EntitlementCache(now::get);
    cache.put("lic-1", new HashSet<>(Arrays.asList("PRO")));

    assertThat(cache.fresh("lic-1")).isNotNull();

    now.set(EntitlementCache.TTL.toMillis() - 1);
    assertThat(cache.fresh("lic-1")).isNotNull();

    now.set(EntitlementCache.TTL.toMillis());
    assertThat(cache.fresh("lic-1")).isNull();
  }

  @Test
  void absentLicenseIsMiss() {
    EntitlementCache cache = new EntitlementCache(() -> 0L);

    assertThat(cache.fresh("never-seen")).isNull();
  }

  @Test
  void storedCodesAreCopiedSoLaterMutationCannotCorruptTheEntry() {
    EntitlementCache cache = new EntitlementCache(() -> 0L);
    HashSet<String> codes = new HashSet<>(Arrays.asList("PRO"));
    cache.put("lic-1", codes);

    codes.add("SNEAKED_IN");

    assertThat(cache.fresh("lic-1")).containsExactly("PRO");
  }

  @Test
  void concurrentLookupsAreRaceFree() throws Exception {
    // Meaningful mainly under a race detector, but it also pins that the network call is not made
    // while the lock is held: a deadlock or serialized stall would blow the timeout below.
    List<String> licenses = Arrays.asList("lic-1", "lic-2", "lic-3", "lic-4", "lic-5");
    for (int i = 0; i < 200; i++) {
      enqueueEntitlements();
    }

    int threads = 20;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    AtomicBoolean failed = new AtomicBoolean(false);

    for (int i = 0; i < threads; i++) {
      final String licenseId = licenses.get(i % licenses.size());
      pool.submit(() -> {
        try {
          start.await();
          for (int n = 0; n < 10; n++) {
            if (!client.hasEntitlement(licenseId, "PRO")) {
              failed.set(true);
            }
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          failed.set(true);
        } catch (RuntimeException e) {
          failed.set(true);
        } finally {
          done.countDown();
        }
      });
    }

    start.countDown();
    assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
    pool.shutdownNow();
    assertThat(failed).isFalse();
  }
}
