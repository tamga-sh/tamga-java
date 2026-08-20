package sh.tamga.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh.tamga.sdk.error.TamgaActivationValidationException;
import sh.tamga.sdk.error.TamgaMachineOverLimitException;
import sh.tamga.sdk.model.ActivationResult;
import sh.tamga.sdk.model.CheckOutOptions;
import sh.tamga.sdk.model.Component;
import sh.tamga.sdk.model.CreateComponentOptions;
import sh.tamga.sdk.model.CreateMachineOptions;
import sh.tamga.sdk.model.CreateProcessOptions;
import sh.tamga.sdk.model.Entitlement;
import sh.tamga.sdk.model.HeartbeatStatus;
import sh.tamga.sdk.model.License;
import sh.tamga.sdk.model.ListOptions;
import sh.tamga.sdk.model.Machine;
import sh.tamga.sdk.model.OfflineProofResult;
import sh.tamga.sdk.model.Page;
import sh.tamga.sdk.model.Process;
import sh.tamga.sdk.model.Scope;
import sh.tamga.sdk.model.ValidateOptions;
import sh.tamga.sdk.model.ValidationCode;
import sh.tamga.sdk.model.ValidationMeta;
import sh.tamga.sdk.model.ValidationResult;

/** Endpoint behaviour: request shapes, response decoding, and the activation rollback. */
class TamgaClientTest {

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

  private static String licenseWithMeta(String code, boolean valid) {
    return "{\"data\":{\"id\":\"lic-1\",\"type\":\"licenses\",\"attributes\":"
        + "{\"key\":\"K\",\"status\":\"ACTIVE\",\"machines_count\":2,\"suspended\":false}},"
        + "\"meta\":{\"ts\":\"2026-08-20T10:00:00Z\",\"valid\":" + valid
        + ",\"detail\":\"d\",\"code\":\"" + code + "\"}}";
  }

  private static String machineBody() {
    return "{\"data\":{\"id\":\"mach-1\",\"type\":\"machines\",\"attributes\":"
        + "{\"fingerprint\":\"fp-1\",\"heartbeat_status\":\"ALIVE\",\"cores\":4,"
        + "\"memory\":8589934592}}}";
  }

  @Test
  void validateByKeySendsFlatKeyBody() throws Exception {
    enqueueJson(licenseWithMeta("VALID", true));

    ValidationResult result = client.validateByKey("MY-KEY");

    RecordedRequest request = server.takeRequest();
    assertThat(request.getTarget())
        .isEqualTo("/v1/accounts/acct-123/licenses/actions/validate-key");
    assertThat(request.getBody().utf8()).isEqualTo("{\"key\":\"MY-KEY\"}");
    assertThat(result.valid()).isTrue();
    assertThat(result.meta().code()).isEqualTo(ValidationCode.VALID);
    assertThat(result.license().status()).isEqualTo("ACTIVE");
    assertThat(result.license().machinesCount()).isEqualTo(2);
  }

  @Test
  void validateByIdOmitsAnEmptyScopeEntirely() throws Exception {
    enqueueJson(licenseWithMeta("VALID", true));

    client.validateById("lic-1", ValidateOptions.defaults());

    // A present "scope" key is a constraint the server evaluates, so an unset scope must not be
    // sent as null.
    assertThat(bodyOf(server.takeRequest()))
        .isEqualTo("{\"meta\":{\"skip_touch\":false}}")
        .doesNotContain("scope");
  }

  @Test
  void validateByIdSendsOnlyThePopulatedScopeFields() throws Exception {
    enqueueJson(licenseWithMeta("VALID", true));

    client.validateById("lic-1",
        ValidateOptions.defaults().withSkipTouch(true)
            .withScope(Scope.none().withProduct("prod-1").withUser("user-9")));

    String body = bodyOf(server.takeRequest());
    assertThat(body).contains("\"skip_touch\":true");
    assertThat(body).contains("\"product\":\"prod-1\"");
    assertThat(body).contains("\"user\":\"user-9\"");
    assertThat(body).doesNotContain("policy");
    assertThat(body).doesNotContain("checksum");
  }

  @Test
  void quickValidateDecodesFlatBodyWithNoEnvelope() throws Exception {
    enqueueJson("{\"ts\":\"2026-08-20T10:00:00Z\",\"valid\":false,\"detail\":\"expired\","
        + "\"code\":\"EXPIRED\"}");

    ValidationMeta meta = client.quickValidate("lic-1");

    assertThat(server.takeRequest().getMethod()).isEqualTo("GET");
    assertThat(meta.valid()).isFalse();
    assertThat(meta.code()).isEqualTo(ValidationCode.EXPIRED);
    assertThat(meta.detail()).isEqualTo("expired");
    assertThat(meta.ts()).isNotNull();
  }

  @Test
  void anUnrecognizedValidationCodeDecodesRatherThanFailing() {
    enqueueJson("{\"ts\":\"2026-08-20T10:00:00Z\",\"valid\":false,\"detail\":\"d\","
        + "\"code\":\"SOMETHING_INVENTED_LATER\"}");

    assertThat(client.quickValidate("lic-1").code()).isEqualTo(ValidationCode.UNKNOWN);
  }

  @Test
  void checkInReturnsTheLicense() throws Exception {
    enqueueJson("{\"data\":{\"id\":\"lic-1\",\"type\":\"licenses\","
        + "\"attributes\":{\"key\":\"K\"}}}");

    License license = client.checkIn("lic-1");

    assertThat(server.takeRequest().getMethod()).isEqualTo("POST");
    assertThat(license.id()).isEqualTo("lic-1");
  }

  @Test
  void createMachineSendsAnEnvelopedBodyWithTheLicenseRelationship() throws Exception {
    enqueueJson(machineBody());

    Machine machine = client.createMachine(
        CreateMachineOptions.of("fp-1", "lic-1").withCores(4).withHostname("box"));

    String body = bodyOf(server.takeRequest());
    assertThat(body).contains("\"type\":\"machines\"");
    assertThat(body).contains("\"attributes\"");
    assertThat(body).contains("\"relationships\"");
    assertThat(body).contains("\"license\":{\"data\":{\"type\":\"licenses\",\"id\":\"lic-1\"}}");
    assertThat(body).contains("\"metadata\":{}");
    assertThat(machine.heartbeatStatus()).isEqualTo(HeartbeatStatus.ALIVE);
    assertThat(machine.cores()).isEqualTo(4);
    assertThat(machine.memory()).isEqualTo(8589934592L);
  }

  @Test
  void createComponentSendsFlatBody() throws Exception {
    enqueueJson("{\"data\":{\"id\":\"comp-1\",\"type\":\"components\",\"attributes\":"
        + "{\"fingerprint\":\"cfp\",\"name\":\"gpu\",\"machine_id\":\"mach-1\"}}}");

    Component component = client.createComponent(
        CreateComponentOptions.of("mach-1", "cfp", "gpu"));

    // Deliberately NOT enveloped, unlike createMachine. The asymmetry is real server behaviour.
    String body = bodyOf(server.takeRequest());
    assertThat(body).doesNotContain("\"data\"");
    assertThat(body).contains("\"machine_id\":\"mach-1\"");
    assertThat(component.name()).isEqualTo("gpu");
  }

  @Test
  void createProcessKeepsPidAsString() throws Exception {
    enqueueJson("{\"data\":{\"id\":\"proc-1\",\"type\":\"processes\",\"attributes\":"
        + "{\"pid\":\"4242\",\"machine_id\":\"mach-1\"}}}");

    Process process = client.createProcess(CreateProcessOptions.of("mach-1", "4242"));

    // The server types pid as a string. Sending 4242 unquoted would be a different wire type.
    assertThat(bodyOf(server.takeRequest())).contains("\"pid\":\"4242\"");
    assertThat(process.pid()).isEqualTo("4242");
  }

  @Test
  void deleteMachineIssuesDeleteAndToleratesEmptyBody() throws Exception {
    server.enqueue(new MockResponse.Builder().code(204).build());

    client.deleteMachine("mach-1");

    RecordedRequest request = server.takeRequest();
    assertThat(request.getMethod()).isEqualTo("DELETE");
    assertThat(request.getTarget()).isEqualTo("/v1/accounts/acct-123/machines/mach-1");
  }

  @Test
  void heartbeatPingsHitTheirOwnActions() throws Exception {
    enqueueJson(machineBody());
    client.pingHeartbeat("mach-1");
    assertThat(server.takeRequest().getTarget())
        .isEqualTo("/v1/accounts/acct-123/machines/mach-1/actions/ping-heartbeat");

    enqueueJson(machineBody());
    client.resetHeartbeat("mach-1");
    assertThat(server.takeRequest().getTarget())
        .isEqualTo("/v1/accounts/acct-123/machines/mach-1/actions/reset-heartbeat");

    enqueueJson("{\"data\":{\"id\":\"proc-1\",\"type\":\"processes\",\"attributes\":"
        + "{\"pid\":\"1\"}}}");
    client.pingProcess("proc-1");
    assertThat(server.takeRequest().getTarget())
        .isEqualTo("/v1/accounts/acct-123/processes/proc-1/actions/ping");
  }

  @Test
  void generateOfflineProofReturnsTheProofFromMeta() throws Exception {
    enqueueJson("{\"data\":{\"id\":\"mach-1\",\"type\":\"machines\",\"attributes\":{}},"
        + "\"meta\":{\"proof\":\"v1x0.abc\"}}");

    Map<String, Object> dataset = new LinkedHashMap<>();
    dataset.put("cores", 4);
    OfflineProofResult result = client.generateOfflineProof("mach-1", dataset);

    assertThat(bodyOf(server.takeRequest()))
        .isEqualTo("{\"meta\":{\"dataset\":{\"cores\":4}}}");
    assertThat(result.proof()).isEqualTo("v1x0.abc");
    assertThat(result.machine().id()).isEqualTo("mach-1");
  }

  @Test
  void generateOfflineProofSendsEmptyObjectForNullDataset() throws Exception {
    enqueueJson("{\"data\":{\"id\":\"mach-1\",\"type\":\"machines\",\"attributes\":{}},"
        + "\"meta\":{\"proof\":\"v1x0.abc\"}}");

    client.generateOfflineProof("mach-1", null);

    assertThat(bodyOf(server.takeRequest()))
        .isEqualTo("{\"meta\":{\"dataset\":{}}}");
  }

  @Test
  void activateMachineReturnsTheMachineWhenValidationPasses() throws Exception {
    enqueueJson(machineBody());
    enqueueJson(licenseWithMeta("VALID", true));

    ActivationResult result = client.activateMachine(
        CreateMachineOptions.of("fp-1", "lic-1"), null);

    assertThat(result.machine().id()).isEqualTo("mach-1");
    assertThat(result.meta().code()).isEqualTo(ValidationCode.VALID);
    assertThat(server.getRequestCount()).isEqualTo(2);
  }

  @Test
  void activateMachineRollsMachineBackWhenOverLimit() throws Exception {
    enqueueJson(machineBody());
    enqueueJson(licenseWithMeta("TOO_MANY_MACHINES", false));
    server.enqueue(new MockResponse.Builder().code(204).build());

    assertThatThrownBy(
        () -> client.activateMachine(CreateMachineOptions.of("fp-1", "lic-1"), null))
        .isInstanceOf(TamgaMachineOverLimitException.class)
        .satisfies(thrown -> assertThat(
            ((TamgaMachineOverLimitException) thrown).validationMeta().code())
            .isEqualTo(ValidationCode.TOO_MANY_MACHINES));

    server.takeRequest();
    server.takeRequest();
    RecordedRequest rollback = server.takeRequest();
    // Creation enforces no limit, so without this delete the rejected activation would leave a
    // row behind that still consumes a seat.
    assertThat(rollback.getMethod()).isEqualTo("DELETE");
    assertThat(rollback.getTarget()).isEqualTo("/v1/accounts/acct-123/machines/mach-1");
  }

  @Test
  void activateMachineKeepsTheMachineWhenValidationItselfFails() throws Exception {
    enqueueJson(machineBody());
    server.enqueue(new MockResponse.Builder().code(500)
        .body("{\"errors\":[{\"code\":\"INTERNAL_SERVER_ERROR\"}]}").build());

    assertThatThrownBy(
        () -> client.activateMachine(CreateMachineOptions.of("fp-1", "lic-1"), null))
        .isInstanceOf(TamgaActivationValidationException.class)
        .satisfies(thrown -> {
          // The machine is handed back so the caller can retry validation or delete it. A
          // transient failure is not a verdict about the license, and deleting on one would
          // destroy a seat the license may well be entitled to.
          Machine stranded = ((TamgaActivationValidationException) thrown).machine();
          assertThat(stranded.id()).isEqualTo("mach-1");
        })
        .hasCauseInstanceOf(sh.tamga.sdk.error.TamgaApiException.class);

    // Exactly two calls: the create and the failed validate. No DELETE. This matches tamga-go.
    assertThat(server.getRequestCount()).isEqualTo(2);
  }

  @Test
  void failedRollbackDoesNotMaskOriginalCause() {
    enqueueJson(machineBody());
    enqueueJson(licenseWithMeta("TOO_MANY_CORES", false));
    server.enqueue(new MockResponse.Builder().code(500)
        .body("{\"errors\":[{\"code\":\"INTERNAL_SERVER_ERROR\"}]}").build());

    // The delete fails, but the caller still needs to learn they are over a limit.
    assertThatThrownBy(
        () -> client.activateMachine(CreateMachineOptions.of("fp-1", "lic-1"), null))
        .isInstanceOf(TamgaMachineOverLimitException.class);
  }

  @Test
  void checkOutLicenseRequestsTheRawCertificateOverGet() throws Exception {
    server.enqueue(new MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "application/octet-stream")
        .body("-----BEGIN LICENSE FILE-----\nabc\n-----END LICENSE FILE-----\n")
        .build());

    String pem = client.checkOutLicense("lic-1", CheckOutOptions.defaults().withTtl(3600));

    RecordedRequest request = server.takeRequest();
    assertThat(request.getMethod()).isEqualTo("GET");
    assertThat(request.getUrl().queryParameter("ttl")).isEqualTo("3600");
    assertThat(request.getUrl().queryParameter("encrypt")).isEqualTo("false");
    assertThat(pem).startsWith("-----BEGIN LICENSE FILE-----");
  }

  @Test
  void checkOutMachineCanUseTheEnvelopedPostVariant() throws Exception {
    enqueueJson("{\"data\":{\"id\":\"mf-1\",\"type\":\"machine-files\",\"attributes\":"
        + "{\"certificate\":\"-----BEGIN MACHINE FILE-----\\nxyz\\n-----END MACHINE FILE-----\","
        + "\"algorithm\":\"aes-256-gcm+ed25519\"}}}");

    String pem = client.checkOutMachine("mach-1",
        CheckOutOptions.defaults().withUsePost(true).withEncrypt(true));

    RecordedRequest request = server.takeRequest();
    assertThat(request.getMethod()).isEqualTo("POST");
    assertThat(request.getBody().utf8()).contains("\"encrypt\":true");
    assertThat(pem).startsWith("-----BEGIN MACHINE FILE-----");
  }

  @Test
  void outOfRangeCheckoutTimeToLiveIsRejectedBeforeRoundTrip() {
    int tooLong = CheckOutOptions.MAX_TTL_SECONDS + 1;
    assertThatThrownBy(() -> assertThat(CheckOutOptions.defaults().withTtl(0)).isNull())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> assertThat(CheckOutOptions.defaults().withTtl(tooLong)).isNull())
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void listEntitlementsSynthesizesCursorOnlyForFullPage() throws Exception {
    enqueueJson("{\"data\":[{\"id\":\"ent-1\",\"type\":\"entitlements\",\"attributes\":"
        + "{\"code\":\"PRO\",\"name\":\"Pro\"}},{\"id\":\"ent-2\",\"type\":\"entitlements\","
        + "\"attributes\":{\"code\":\"BETA\",\"name\":\"Beta\"}}]}");

    Page<Entitlement> page = client.listEntitlements("lic-1", ListOptions.ofLimit(2));

    RecordedRequest request = server.takeRequest();
    assertThat(request.getUrl().queryParameter("limit")).isEqualTo("2");
    assertThat(page.items()).hasSize(2);
    // A full page means there may be more, so the cursor is the last item's id.
    assertThat(page.nextCursor()).isEqualTo("ent-2");
  }

  @Test
  void shortPageEndsPagination() {
    enqueueJson("{\"data\":[{\"id\":\"ent-1\",\"type\":\"entitlements\",\"attributes\":"
        + "{\"code\":\"PRO\"}}]}");

    Page<Entitlement> page = client.listEntitlements("lic-1", ListOptions.ofLimit(50));

    assertThat(page.items()).hasSize(1);
    assertThat(page.nextCursor()).isNull();
  }

  @Test
  void pageCursorIsSentAsKeysetParameter() throws Exception {
    enqueueJson("{\"data\":[]}");

    client.listComponents("mach-1", ListOptions.ofLimit(10).after("comp-9"));

    assertThat(server.takeRequest().getUrl().queryParameter("page[after]")).isEqualTo("comp-9");
  }

  @Test
  void getEntitlementFetchesSingleResource() throws Exception {
    enqueueJson("{\"data\":{\"id\":\"ent-1\",\"type\":\"entitlements\",\"attributes\":"
        + "{\"code\":\"PRO\",\"name\":\"Pro\"}}}");

    Entitlement entitlement = client.getEntitlement("lic-1", "ent-1");

    assertThat(server.takeRequest().getTarget())
        .isEqualTo("/v1/accounts/acct-123/licenses/lic-1/entitlements/ent-1");
    assertThat(entitlement.code()).isEqualTo("PRO");
  }

  @Test
  void listComponentsDecodesItsResources() {
    enqueueJson("{\"data\":[{\"id\":\"comp-1\",\"type\":\"components\",\"attributes\":"
        + "{\"fingerprint\":\"cfp\",\"name\":\"gpu\",\"machine_id\":\"mach-1\"}}]}");

    Page<Component> page = client.listComponents("mach-1", ListOptions.defaults());

    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).machineId()).isEqualTo("mach-1");
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
