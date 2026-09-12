package sh.tamga.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Objects;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh.tamga.sdk.error.TamgaMeterLimitExceededException;
import sh.tamga.sdk.model.Entitlement;

/**
 * The three meter actions -- {@code increment}/{@code decrement}/{@code reset} -- mirroring
 * {@code pingHeartbeat}/{@code resetHeartbeat}'s shape one path segment deeper.
 */
class EntitlementTest {

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

  private void enqueueEntitlement(int currentValue, Integer maxValue) {
    server.enqueue(new MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "application/vnd.api+json")
        .body("{\"data\":{\"id\":\"ent-1\",\"type\":\"entitlements\",\"attributes\":"
            + "{\"code\":\"REQUESTS\",\"kind\":\"meter\",\"current_value\":" + currentValue
            + ",\"max_value\":" + (maxValue == null ? "null" : maxValue) + "}}}")
        .build());
  }

  private void enqueueMeterLimitExceeded(String entitlementId) {
    server.enqueue(new MockResponse.Builder()
        .code(422)
        .addHeader("Content-Type", "application/vnd.api+json")
        .body("{\"errors\":[{\"id\":\"01920000-0000-7000-8000-000000000002\",\"status\":\"422\","
            + "\"code\":\"METER_LIMIT_EXCEEDED\",\"title\":\"Unprocessable Entity\","
            + "\"detail\":\"meter cap exceeded\",\"meta\":{\"entitlement_id\":\"" + entitlementId
            + "\"}}]}")
        .build());
  }

  private static String bodyOf(RecordedRequest request) {
    return Objects.requireNonNull(request.getBody(), "recorded request had no body").utf8();
  }

  @Test
  void incrementWithNoAmountSendsBareActionCall() throws Exception {
    enqueueEntitlement(1, 1000);

    Entitlement result = client.incrementEntitlementUsage("lic-1", "ent-1");

    RecordedRequest request = server.takeRequest();
    assertThat(request.getTarget())
        .isEqualTo("/v1/accounts/acct-123/licenses/lic-1/entitlements/ent-1/actions/increment");
    assertThat(bodyOf(request)).isEmpty();
    assertThat(result.currentValue()).isEqualTo(1);
    assertThat(result.maxValue()).isEqualTo(1000);
  }

  @Test
  void incrementWithAnExplicitAmountSendsTheIncrementBody() throws Exception {
    enqueueEntitlement(5, 1000);

    client.incrementEntitlementUsage("lic-1", "ent-1", 5);

    assertThat(bodyOf(server.takeRequest())).isEqualTo("{\"increment\":5}");
  }

  @Test
  void decrementWithNoAmountSendsBareActionCall() throws Exception {
    enqueueEntitlement(0, 1000);

    Entitlement result = client.decrementEntitlementUsage("lic-1", "ent-1");

    RecordedRequest request = server.takeRequest();
    assertThat(request.getTarget())
        .isEqualTo("/v1/accounts/acct-123/licenses/lic-1/entitlements/ent-1/actions/decrement");
    assertThat(bodyOf(request)).isEmpty();
    assertThat(result.currentValue()).isEqualTo(0);
  }

  @Test
  void decrementWithAnExplicitAmountSendsTheDecrementBody() throws Exception {
    enqueueEntitlement(0, 1000);

    client.decrementEntitlementUsage("lic-1", "ent-1", 3);

    assertThat(bodyOf(server.takeRequest())).isEqualTo("{\"decrement\":3}");
  }

  @Test
  void resetSendsBareActionCallWithNoBody() throws Exception {
    enqueueEntitlement(0, 1000);

    Entitlement result = client.resetEntitlementUsage("lic-1", "ent-1");

    RecordedRequest request = server.takeRequest();
    assertThat(request.getTarget())
        .isEqualTo("/v1/accounts/acct-123/licenses/lic-1/entitlements/ent-1/actions/reset");
    assertThat(bodyOf(request)).isEmpty();
    assertThat(result.currentValue()).isEqualTo(0);
  }

  @Test
  void incrementBeyondTheCapThrowsTheTypedMeterLimitException() {
    enqueueMeterLimitExceeded("ent-1");

    assertThatThrownBy(() -> client.incrementEntitlementUsage("lic-1", "ent-1", 50))
        .isInstanceOf(TamgaMeterLimitExceededException.class)
        .extracting(e -> ((TamgaMeterLimitExceededException) e).entitlementId())
        .isEqualTo("ent-1");
  }

  @Test
  void theMeterLimitExceptionCarriesTheWireLevelErrorAsItsCause() {
    enqueueMeterLimitExceeded("ent-1");

    assertThatThrownBy(() -> client.incrementEntitlementUsage("lic-1", "ent-1"))
        .isInstanceOf(TamgaMeterLimitExceededException.class)
        .cause()
        .isInstanceOf(sh.tamga.sdk.error.TamgaApiException.MeterLimitExceededException.class);
  }

  @Test
  void meterLimitExceptionWithNoNamedEntitlementStillHasUsableMessage() {
    TamgaMeterLimitExceededException thrown = new TamgaMeterLimitExceededException(null, null);

    assertThat(thrown.entitlementId()).isNull();
    assertThat(thrown).hasMessageContaining("unknown");
  }
}
