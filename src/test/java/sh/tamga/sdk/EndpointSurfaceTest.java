package sh.tamga.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh.tamga.sdk.error.TamgaApiException;
import sh.tamga.sdk.error.TamgaMachineOverLimitException;
import sh.tamga.sdk.error.TamgaTransportException;
import sh.tamga.sdk.model.ActivationOptions;
import sh.tamga.sdk.model.ActivationResult;
import sh.tamga.sdk.model.CreateMachineOptions;
import sh.tamga.sdk.model.HealthStatus;
import sh.tamga.sdk.model.HeartbeatStatus;
import sh.tamga.sdk.model.License;
import sh.tamga.sdk.model.ListOptions;
import sh.tamga.sdk.model.Machine;
import sh.tamga.sdk.model.MachineListOptions;
import sh.tamga.sdk.model.OffsetPage;
import sh.tamga.sdk.model.Page;
import sh.tamga.sdk.model.Policy;
import sh.tamga.sdk.model.Process;
import sh.tamga.sdk.model.UpdateMachineOptions;
import sh.tamga.sdk.model.UpgradeCheckOptions;
import sh.tamga.sdk.model.UpgradeCheckResult;

/**
 * The endpoint surface added once the account-prefixed URL builder and the missing read routes
 * were the only thing keeping these calls out of reach: machine/license/policy reads, the machine
 * update, the upgrade check, health, and process deletion.
 */
class EndpointSurfaceTest {

  private MockWebServer server;
  private TamgaClient client;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
    client = TamgaClient.builder("acct-123")
        .host(server.url("/").toString())
        .auth(AuthTransport.licenseKey("lic-abc"))
        .build();
  }

  @AfterEach
  void tearDown() throws IOException {
    server.close();
  }

  private void enqueueJson(String body) {
    server.enqueue(new MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "application/vnd.api+json")
        .body(body)
        .build());
  }

  private void enqueueError(int status, String code, String detail) {
    server.enqueue(new MockResponse.Builder()
        .code(status)
        .addHeader("Content-Type", "application/vnd.api+json")
        .body("{\"errors\":[{\"id\":\"01920000-0000-7000-8000-000000000001\",\"status\":\""
            + status + "\",\"code\":\"" + code + "\",\"title\":\"t\",\"detail\":\"" + detail
            + "\"}]}")
        .build());
  }

  private static String machineResource(String id, String fingerprint, String status) {
    return "{\"id\":\"" + id + "\",\"type\":\"machines\",\"attributes\":{\"fingerprint\":\""
        + fingerprint + "\",\"heartbeat_status\":\"" + status + "\",\"hostname\":\"box\","
        + "\"next_heartbeat_at\":\"2026-08-21T10:02:00Z\","
        + "\"last_heartbeat_at\":\"2026-08-21T10:00:00Z\"}}";
  }

  private static String machinePage(String meta, String... resources) {
    return "{\"data\":[" + String.join(",", resources) + "],\"meta\":" + meta + "}";
  }

  private static String pageMeta(int number, int size, int total, int totalPages) {
    // number/size/total are bare lowercase and total_pages is renamed to totalPages -- the mixed
    // casing is the server's, and a fixture that "tidies" it stops testing the decoder.
    return "{\"page\":{\"number\":" + number + ",\"size\":" + size + ",\"total\":" + total
        + ",\"totalPages\":" + totalPages + "}}";
  }

  // ------------------------------------------------------------------ reads

  @Test
  void getLicenseReadsTheStoredResourceWithoutValidating() throws Exception {
    enqueueJson("{\"data\":{\"id\":\"lic-1\",\"type\":\"licenses\",\"attributes\":"
        + "{\"key\":\"K\",\"status\":\"ACTIVE\",\"machines_count\":3}}}");

    License license = client.getLicense("lic-1");

    RecordedRequest request = server.takeRequest();
    assertThat(request.getMethod()).isEqualTo("GET");
    assertThat(request.getTarget()).isEqualTo("/v1/accounts/acct-123/licenses/lic-1");
    assertThat(license.status()).isEqualTo("ACTIVE");
    assertThat(license.machinesCount()).isEqualTo(3);
  }

  @Test
  void getLicensePolicyReachesTheNestedRoute() throws Exception {
    enqueueJson("{\"data\":{\"id\":\"pol-1\",\"type\":\"policies\",\"attributes\":"
        + "{\"name\":\"Pro\",\"heartbeat_duration\":90,\"check_in_interval\":\"weekly\"}}}");

    Policy policy = client.getLicensePolicy("lic-1");

    assertThat(server.takeRequest().getTarget())
        .isEqualTo("/v1/accounts/acct-123/licenses/lic-1/policy");
    assertThat(policy.id()).isEqualTo("pol-1");
    assertThat(policy.heartbeatDuration()).isEqualTo(90);
    assertThat(policy.checkInInterval()).isEqualTo(Policy.CheckInInterval.WEEK);
  }

  @Test
  void getPolicyReachesTheStandaloneRoute() throws Exception {
    enqueueJson("{\"data\":{\"id\":\"pol-1\",\"type\":\"policies\",\"attributes\":{}}}");

    assertThat(client.getPolicy("pol-1").id()).isEqualTo("pol-1");
    assertThat(server.takeRequest().getTarget()).isEqualTo("/v1/accounts/acct-123/policies/pol-1");
  }

  @Test
  void getPolicyIsForbiddenForLicenseKeyCredential() {
    // Not a hypothetical: policy.read is absent from the license token's permission set, so this
    // is what an embedded caller actually gets. getLicensePolicy is the route that works.
    enqueueError(403, "FORBIDDEN", "You are not allowed to read policies");

    assertThatThrownBy(() -> client.getPolicy("pol-1"))
        .isInstanceOf(TamgaApiException.ForbiddenException.class);
  }

  @Test
  void getMachineIsTheReadThatCanReportDead() throws Exception {
    // A ping cannot: it writes last_heartbeat_at = NOW() and then derives the status from that
    // same timestamp. This route measures against a timestamp nothing just reset.
    enqueueJson("{\"data\":" + machineResource("mach-1", "fp-1", "DEAD") + "}");

    Machine machine = client.getMachine("mach-1");

    assertThat(server.takeRequest().getTarget()).isEqualTo("/v1/accounts/acct-123/machines/mach-1");
    assertThat(machine.heartbeatStatus()).isEqualTo(HeartbeatStatus.DEAD);
    assertThat(machine.nextHeartbeatAt()).isEqualTo(Instant.parse("2026-08-21T10:02:00Z"));
  }

  @Test
  void getMachineEscapesTheIdIntoOnePathSegment() throws Exception {
    enqueueJson("{\"data\":" + machineResource("m", "fp", "ALIVE") + "}");

    client.getMachine("../../licenses/lic-9");

    assertThat(server.takeRequest().getTarget())
        .isEqualTo("/v1/accounts/acct-123/machines/..%2F..%2Flicenses%2Flic-9");
  }

  // ------------------------------------------------------- offset pagination

  @Test
  void listMachinesSendsOffsetParametersAndReadsPageMeta() throws Exception {
    enqueueJson(machinePage(pageMeta(2, 25, 60, 3),
        machineResource("mach-1", "fp-1", "ALIVE"), machineResource("mach-2", "fp-2", "DEAD")));

    OffsetPage<Machine> page = client.listMachines(
        MachineListOptions.defaults().page(2).size(25).licenseId("lic-1")
            .platforms(Arrays.asList("linux", "darwin")).sort("name").descending(true));

    RecordedRequest request = server.takeRequest();
    assertThat(request.getTarget())
        .startsWith("/v1/accounts/acct-123/machines?")
        .contains("page%5Bnumber%5D=2")
        .contains("page%5Bsize%5D=25")
        .contains("filter%5Blicense%5D=lic-1")
        .contains("filter%5Bplatform%5D=linux%2Cdarwin")
        .contains("sort=-name")
        // Keyset parameters belong to the sub-collections, never here.
        .doesNotContain("page%5Bafter%5D")
        .doesNotContain("limit=");
    assertThat(page.items()).hasSize(2);
    assertThat(page.number()).isEqualTo(2);
    assertThat(page.size()).isEqualTo(25);
    assertThat(page.total()).isEqualTo(60);
    assertThat(page.totalPages()).isEqualTo(3);
    assertThat(page.hasNextPage()).isTrue();
  }

  @Test
  void listMachinesDefaultsToTheFirstPageAndTheClientPageSize() throws Exception {
    enqueueJson(machinePage(pageMeta(1, 100, 1, 1), machineResource("mach-1", "fp-1", "ALIVE")));

    OffsetPage<Machine> page = client.listMachines(null);

    assertThat(server.takeRequest().getTarget())
        .contains("page%5Bnumber%5D=1")
        .contains("page%5Bsize%5D=100");
    assertThat(page.hasNextPage()).isFalse();
  }

  @Test
  void listMachinesSurvivesResponseWithNoPageMeta() throws Exception {
    // Degrading to zeroed counters keeps the rows; throwing away a whole page because a counter
    // was absent would be the worse failure.
    enqueueJson("{\"data\":[" + machineResource("mach-1", "fp-1", "ALIVE") + "]}");

    OffsetPage<Machine> page = client.listMachines(MachineListOptions.defaults());

    assertThat(page.items()).hasSize(1);
    assertThat(page.totalPages()).isZero();
    assertThat(page.hasNextPage()).isFalse();
  }

  @Test
  void listMachinesOmitsUnsetFiltersEntirely() throws Exception {
    enqueueJson(machinePage(pageMeta(1, 100, 0, 0)));

    client.listMachines(MachineListOptions.defaults()
        .licenseIds(null).platforms(Collections.<String>emptyList()).search(""));

    String target = server.takeRequest().getTarget();
    assertThat(target).doesNotContain("filter%5Blicense%5D")
        .doesNotContain("filter%5Bplatform%5D")
        .doesNotContain("filter%5Bq%5D");
  }

  @Test
  void listMachinesSendsOrderWhenDescendingWithoutSortColumn() throws Exception {
    enqueueJson(machinePage(pageMeta(1, 100, 0, 0)));

    client.listMachines(MachineListOptions.defaults().descending(true));

    assertThat(server.takeRequest().getTarget()).contains("order=desc");
  }

  // ---------------------------------------------------- fingerprint recovery

  @Test
  void findMachineByFingerprintMatchesExactlyRatherThanOnTheSubstringSearch() throws Exception {
    // filter[q] is an ILIKE %term% across name, hostname AND fingerprint, so it also returns rows
    // that merely contain the term. A substring hit is not the same machine.
    enqueueJson(machinePage(pageMeta(1, 100, 2, 1),
        machineResource("mach-other", "fp-1-extended", "ALIVE"),
        machineResource("mach-1", "fp-1", "ALIVE")));

    Machine found = client.findMachineByFingerprint("fp-1", "lic-1");

    RecordedRequest request = server.takeRequest();
    assertThat(request.getTarget()).contains("filter%5Bq%5D=fp-1")
        .contains("filter%5Blicense%5D=lic-1");
    assertThat(found).isNotNull();
    assertThat(found.id()).isEqualTo("mach-1");
  }

  @Test
  void findMachineByFingerprintReturnsNullWhenOnlySubstringsMatch() throws Exception {
    enqueueJson(machinePage(pageMeta(1, 100, 1, 1),
        machineResource("mach-other", "fp-1-extended", "ALIVE")));

    assertThat(client.findMachineByFingerprint("fp-1", null)).isNull();
    assertThat(server.takeRequest().getTarget()).doesNotContain("filter%5Blicense%5D");
  }

  @Test
  void findMachineByFingerprintMakesNoRequestForEmptyFingerprint() {
    assertThat(client.findMachineByFingerprint(null, "lic-1")).isNull();
    assertThat(client.findMachineByFingerprint("", "lic-1")).isNull();
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void activationReusesAlreadyRegisteredFingerprintWhenAskedTo() throws Exception {
    enqueueError(409, "FINGERPRINT_TAKEN", "That fingerprint is already registered");
    enqueueJson(machinePage(pageMeta(1, 100, 1, 1), machineResource("mach-1", "fp-1", "ALIVE")));
    enqueueJson("{\"data\":{\"id\":\"lic-1\",\"type\":\"licenses\",\"attributes\":{}},"
        + "\"meta\":{\"ts\":\"2026-08-21T10:00:00Z\",\"valid\":true,\"detail\":\"d\","
        + "\"code\":\"VALID\"}}");

    ActivationResult result = client.activateMachine(CreateMachineOptions.of("fp-1", "lic-1"),
        null, ActivationOptions.defaults().reuseTakenFingerprint(true));

    assertThat(server.takeRequest().getTarget()).isEqualTo("/v1/accounts/acct-123/machines");
    assertThat(server.takeRequest().getTarget()).contains("filter%5Bq%5D=fp-1");
    assertThat(server.takeRequest().getTarget())
        .isEqualTo("/v1/accounts/acct-123/licenses/lic-1/actions/validate");
    assertThat(result.machine().id()).isEqualTo("mach-1");
    assertThat(server.getRequestCount()).isEqualTo(3);
  }

  @Test
  void activationStillRaisesTheConflictByDefault() throws Exception {
    enqueueError(409, "FINGERPRINT_TAKEN", "taken");

    assertThatThrownBy(() -> client.activateMachine(CreateMachineOptions.of("fp-1", "lic-1"), null))
        .isInstanceOf(TamgaApiException.FingerprintTakenException.class);
    // No lookup attempted: the default path must not spend a second round trip.
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @Test
  void activationRaisesTheConflictWhenTheFingerprintIsHeldOnAnotherLicense() throws Exception {
    // UNIQUE_PER_POLICY/ACCOUNT lets the taken row belong to a different license, and a machine
    // resource carries no license id -- so the license-filtered lookup finds nothing and the
    // original 409 stands rather than a guess being returned.
    enqueueError(409, "FINGERPRINT_TAKEN", "taken");
    enqueueJson(machinePage(pageMeta(1, 100, 0, 0)));

    assertThatThrownBy(() -> client.activateMachine(CreateMachineOptions.of("fp-1", "lic-1"), null,
        ActivationOptions.defaults().reuseTakenFingerprint(true)))
        .isInstanceOf(TamgaApiException.FingerprintTakenException.class);
    assertThat(server.getRequestCount()).isEqualTo(2);
  }

  @Test
  void reusedMachineIsNeverRolledBackWhenTheLicenseIsOverLimit() throws Exception {
    enqueueError(409, "FINGERPRINT_TAKEN", "taken");
    enqueueJson(machinePage(pageMeta(1, 100, 1, 1), machineResource("mach-1", "fp-1", "ALIVE")));
    enqueueJson("{\"data\":{\"id\":\"lic-1\",\"type\":\"licenses\",\"attributes\":{}},"
        + "\"meta\":{\"ts\":\"2026-08-21T10:00:00Z\",\"valid\":false,\"detail\":\"d\","
        + "\"code\":\"TOO_MANY_MACHINES\"}}");

    assertThatThrownBy(() -> client.activateMachine(CreateMachineOptions.of("fp-1", "lic-1"), null,
        ActivationOptions.defaults().reuseTakenFingerprint(true)))
        .isInstanceOf(TamgaMachineOverLimitException.class)
        .satisfies(e -> assertThat(((TamgaMachineOverLimitException) e).rolledBack()).isFalse());
    // Three calls: create, lookup, validate. A fourth would be the DELETE that must not happen.
    assertThat(server.getRequestCount()).isEqualTo(3);
  }

  @Test
  void reuseDoesNotInterceptNonConflictFailure() throws Exception {
    enqueueError(401, "UNAUTHORIZED", "nope");

    assertThatThrownBy(() -> client.activateMachine(CreateMachineOptions.of("fp-1", "lic-1"), null,
        ActivationOptions.defaults().reuseTakenFingerprint(true)))
        .isInstanceOf(TamgaApiException.UnauthorizedException.class);
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  // ----------------------------------------------------------------- update

  @Test
  void updateMachineSendsEnvelopedBodyCarryingOnlyTheFieldsThatWereSet() throws Exception {
    enqueueJson("{\"data\":" + machineResource("mach-1", "fp-1", "ALIVE") + "}");

    client.updateMachine("mach-1", UpdateMachineOptions.none().withHostname("build-box")
        .withCores(8).withMemory(16384L));

    RecordedRequest request = server.takeRequest();
    assertThat(request.getMethod()).isEqualTo("PATCH");
    assertThat(request.getTarget()).isEqualTo("/v1/accounts/acct-123/machines/mach-1");
    // `type` is required by the server's decoder even though the handler ignores it, and an unset
    // field is omitted rather than nulled -- COALESCE makes the two identical server-side.
    assertThat(request.getBody().utf8()).isEqualTo(
        "{\"data\":{\"type\":\"machines\",\"attributes\":"
            + "{\"hostname\":\"build-box\",\"cores\":8,\"memory\":16384}}}");
  }

  @Test
  void updateMachineIsTheWriteThatCanStillReportDead() throws Exception {
    // The counterexample to "a write can never say DEAD": PATCH sets none of the heartbeat
    // columns, so the status is judged against a clock it did not reset. Its next_heartbeat_at is
    // the 600s fallback, because the UPDATE ... RETURNING does not join policies.
    enqueueJson("{\"data\":" + machineResource("mach-1", "fp-1", "DEAD") + "}");

    Machine machine = client.updateMachine("mach-1", UpdateMachineOptions.none().withName("n"));

    assertThat(server.takeRequest().getMethod()).isEqualTo("PATCH");
    assertThat(machine.heartbeatStatus()).isEqualTo(HeartbeatStatus.DEAD);
  }

  @Test
  void updateMachineWithNoOptionsSendsEmptyAttributeBag() throws Exception {
    enqueueJson("{\"data\":" + machineResource("mach-1", "fp-1", "ALIVE") + "}");

    Machine machine = client.updateMachine("mach-1", null);

    assertThat(server.takeRequest().getBody().utf8())
        .isEqualTo("{\"data\":{\"type\":\"machines\",\"attributes\":{}}}");
    assertThat(machine.id()).isEqualTo("mach-1");
  }

  @Test
  void updateMachineCarriesEveryOptionalField() throws Exception {
    enqueueJson("{\"data\":" + machineResource("mach-1", "fp-1", "ALIVE") + "}");
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("tier", "gold");

    client.updateMachine("mach-1", UpdateMachineOptions.none().withName("n").withIp("1.2.3.4")
        .withHostname("h").withPlatform("linux").withCores(2).withMemory(1L).withDisk(2L)
        .withMetadata(metadata));

    assertThat(server.takeRequest().getBody().utf8()).isEqualTo(
        "{\"data\":{\"type\":\"machines\",\"attributes\":{\"name\":\"n\",\"ip\":\"1.2.3.4\","
            + "\"hostname\":\"h\",\"platform\":\"linux\",\"cores\":2,\"memory\":1,\"disk\":2,"
            + "\"metadata\":{\"tier\":\"gold\"}}}}");
  }

  // ------------------------------------------------------ processes on a machine

  @Test
  void listMachineProcessesIsKeysetNotOffset() throws Exception {
    enqueueJson("{\"data\":[{\"id\":\"proc-1\",\"type\":\"processes\",\"attributes\":"
        + "{\"pid\":\"4242\",\"machine_id\":\"mach-1\"}}]}");

    Page<Process> page = client.listMachineProcesses("mach-1", ListOptions.ofLimit(1));

    RecordedRequest request = server.takeRequest();
    assertThat(request.getTarget())
        .isEqualTo("/v1/accounts/acct-123/machines/mach-1/processes?limit=1");
    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).pid()).isEqualTo("4242");
    // A full page synthesizes a cursor from the last row's id, exactly like listComponents.
    assertThat(page.nextCursor()).isEqualTo("proc-1");
  }

  @Test
  void listMachineProcessesReportsNoCursorOnShortPage() throws Exception {
    enqueueJson("{\"data\":[]}");

    Page<Process> page = client.listMachineProcesses("mach-1", null);

    assertThat(server.takeRequest().getTarget()).contains("limit=100");
    assertThat(page.nextCursor()).isNull();
    assertThat(page.items()).isEmpty();
  }

  @Test
  void deleteProcessIssuesDeleteAndAcceptsNoContent() throws Exception {
    server.enqueue(new MockResponse.Builder().code(204).build());

    client.deleteProcess("proc-1");

    RecordedRequest request = server.takeRequest();
    assertThat(request.getMethod()).isEqualTo("DELETE");
    assertThat(request.getTarget()).isEqualTo("/v1/accounts/acct-123/processes/proc-1");
  }

  @Test
  void deleteProcessSurfacesMissingRowAsNotFound() {
    enqueueError(404, "NOT_FOUND", "process not found");

    assertThatThrownBy(() -> client.deleteProcess("proc-1"))
        .isInstanceOf(TamgaApiException.NotFoundException.class);
  }

  // --------------------------------------------------------- upgrade check

  @Test
  void checkForUpgradeSendsEveryRequiredParameterAndDecodesCamelCaseAttributes() throws Exception {
    // The release serializer carries rename_all = "camelCase", which no other resource in this API
    // does: productId, not product_id. The two timestamps are renamed on top of that.
    enqueueJson("{\"data\":{\"id\":\"rel-1\",\"type\":\"releases\",\"attributes\":"
        + "{\"productId\":\"prod-1\",\"name\":\"2.0\",\"version\":\"2.0.0\",\"channel\":\"stable\","
        + "\"status\":\"PUBLISHED\",\"tag\":\"ga\",\"metadata\":{\"k\":\"v\"},"
        + "\"created\":\"2026-08-01T00:00:00Z\",\"updated\":\"2026-08-02T00:00:00Z\"}}}");

    UpgradeCheckResult result = client.checkForUpgrade(
        UpgradeCheckOptions.of("prod-1", "darwin", "dmg", "1.0.0", "stable")
            .withConstraint("^1.0.0"));

    RecordedRequest request = server.takeRequest();
    assertThat(request.getTarget())
        .startsWith("/v1/accounts/acct-123/releases/actions/upgrade?")
        .contains("product=prod-1")
        .contains("platform=darwin")
        .contains("filetype=dmg")
        .contains("version=1.0.0")
        .contains("channel=stable")
        .contains("constraint=%5E1.0.0");
    assertThat(result.updateOffered()).isTrue();
    assertThat(result.release().productId()).isEqualTo("prod-1");
    assertThat(result.release().version()).isEqualTo("2.0.0");
    assertThat(result.release().tag()).isEqualTo("ga");
    assertThat(result.release().metadata()).containsEntry("k", "v");
    assertThat(result.release().created()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
    assertThat(result.release().updated()).isEqualTo(Instant.parse("2026-08-02T00:00:00Z"));
    assertThat(result.release().name()).isEqualTo("2.0");
    assertThat(result.release().channel()).isEqualTo("stable");
    assertThat(result.release().status()).isEqualTo("PUBLISHED");
    assertThat(result.release().id()).isEqualTo("rel-1");
  }

  @Test
  void checkForUpgradeReportsNoOfferOnNoContent() throws Exception {
    // 204 means BOTH "nothing newer" and "something newer exists that you may not have", and the
    // client cannot tell which. It must not be reported as "you are up to date".
    server.enqueue(new MockResponse.Builder().code(204).build());

    UpgradeCheckResult result = client.checkForUpgrade(
        UpgradeCheckOptions.of("prod-1", "linux", "deb", "1.0.0", "stable"));

    assertThat(result.updateOffered()).isFalse();
    assertThat(result.release()).isNull();
    assertThat(server.takeRequest().getTarget()).doesNotContain("constraint");
  }

  @Test
  void checkForUpgradeRefusesToTreatResourcelessOkAsNoUpdate() {
    // A 200 with no resource is a malformed response, not the 204 outcome. Collapsing them would
    // report "no update" for a broken server.
    enqueueJson("{}");

    assertThatThrownBy(() -> client.checkForUpgrade(
        UpgradeCheckOptions.of("prod-1", "linux", "deb", "1.0.0", "stable")))
        .isInstanceOf(TamgaTransportException.class)
        .hasMessageContaining("no release resource");
  }

  @Test
  void checkForUpgradeSurfacesSuspendedLicenseAsForbidden() {
    // A suspension is NOT folded into the 204 the way an expiry is -- it comes back as 403.
    enqueueError(403, "FORBIDDEN", "The license is suspended");

    assertThatThrownBy(() -> client.checkForUpgrade(
        UpgradeCheckOptions.of("prod-1", "linux", "deb", "1.0.0", "stable")))
        .isInstanceOf(TamgaApiException.ForbiddenException.class);
  }

  @Test
  void checkForUpgradeDegradesPlainTextBadRequestToTheUnknownCode() {
    // The handler parses its query with a bare extractor, so a malformed one answers plain text
    // rather than a JSON:API error document.
    server.enqueue(new MockResponse.Builder().code(400)
        .addHeader("Content-Type", "text/plain")
        .body("Failed to deserialize query string").build());

    assertThatThrownBy(() -> client.checkForUpgrade(
        UpgradeCheckOptions.of("prod-1", "linux", "deb", "1.0.0", "stable")))
        .isInstanceOf(TamgaApiException.class)
        .satisfies(e -> assertThat(((TamgaApiException) e).code()).isEqualTo("UNKNOWN"));
  }

  // ----------------------------------------------------------------- health

  @Test
  void healthIsTheOneRouteThatSkipsTheAccountPrefix() throws Exception {
    // The unconditional /v1/accounts/{id} prefix -- not the server -- is why no SDK in this fleet
    // could reach this route.
    server.enqueue(new MockResponse.Builder().code(200)
        .addHeader("Content-Type", "application/json")
        .body("{\"status\":\"ok\",\"version\":\"0.9.1\",\"uptime_secs\":4242}").build());

    HealthStatus health = client.health();

    RecordedRequest request = server.takeRequest();
    assertThat(request.getTarget()).isEqualTo("/v1/health");
    assertThat(request.getHeaders().get("Accept")).isEqualTo("application/json");
    // Credentials still go out, per this SDK's rule that they are sent even where unnecessary.
    assertThat(request.getHeaders().get("Authorization")).isEqualTo("License lic-abc");
    assertThat(health.status()).isEqualTo("ok");
    assertThat(health.version()).isEqualTo("0.9.1");
    assertThat(health.uptimeSeconds()).isEqualTo(4242L);
  }

  @Test
  void healthDecodesFlatBodyWithNoJsonApiEnvelope() throws Exception {
    server.enqueue(new MockResponse.Builder().code(200)
        .addHeader("Content-Type", "application/json")
        .body("{\"status\":\"ok\"}").build());

    HealthStatus health = client.health();

    assertThat(health.status()).isEqualTo("ok");
    assertThat(health.version()).isNull();
    assertThat(health.uptimeSeconds()).isZero();
    server.takeRequest();
  }

  // -------------------------------------------------- shared decoding paths

  @Test
  void successfulResponseWithNoBodyDecodesAsEmptyDocument() throws Exception {
    // Distinct from the upgrade check's 204: there the absence is the answer, here a 200 that
    // simply carried nothing must not blow up the decoder.
    server.enqueue(new MockResponse.Builder().code(200)
        .addHeader("Content-Type", "application/vnd.api+json").build());

    assertThat(client.getMachine("mach-1")).isNull();
    server.takeRequest();
  }

  @Test
  void successfulResponseThatIsNotJsonIsTransportFailure() throws Exception {
    // The status said 200, so this is not an API error -- it is a server that answered with
    // something this client cannot parse, and conflating the two would misreport the cause.
    server.enqueue(new MockResponse.Builder().code(200)
        .addHeader("Content-Type", "application/vnd.api+json")
        .body("<html>not json</html>").build());

    assertThatThrownBy(() -> client.getMachine("mach-1"))
        .isInstanceOf(TamgaTransportException.class)
        .hasMessageContaining("not valid JSON");
    server.takeRequest();
  }
}
