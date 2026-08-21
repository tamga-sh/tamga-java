package sh.tamga.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh.tamga.sdk.checkout.SigningKeySet;
import sh.tamga.sdk.error.TamgaApiException;
import sh.tamga.sdk.model.SigningKey;

/** {@code GET /v1/accounts/{accountId}/signing-keys} -- the account's published key history. */
class SigningKeysEndpointTest {

  private static final String ZERO_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
  private static final String SEQUENTIAL_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

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

  private static String keyResource(String id, String publicKey, String status, String retired) {
    return "{\"type\":\"signing-keys\",\"id\":\"" + id + "\",\"attributes\":{"
        + "\"algorithm\":\"ed25519\",\"publicKey\":\"" + publicKey + "\",\"status\":\"" + status
        + "\",\"created\":\"2026-01-01T00:00:00Z\""
        + (retired == null ? "" : ",\"retired\":\"" + retired + "\"") + "}}";
  }

  @Test
  void listSigningKeysReadsTheAccountScopedRouteAndKeepsRetiredKeys() throws Exception {
    // Retired keys are the whole point: a file signed before the last rotation needs the key that
    // signed it, and dropping them here would reintroduce exactly the defect this route exists to
    // close.
    enqueueJson("{\"data\":["
        + keyResource("905f28def18eaac0", SEQUENTIAL_KEY, "active", null) + ","
        + keyResource("51643eac9777b63a", ZERO_KEY, "retired", "2026-06-01T00:00:00Z") + "]}");

    List<SigningKey> keys = client.listSigningKeys();

    RecordedRequest request = server.takeRequest();
    assertThat(request.getMethod()).isEqualTo("GET");
    assertThat(request.getTarget()).isEqualTo("/v1/accounts/acct-123/signing-keys");
    assertThat(keys).hasSize(2);
    assertThat(keys.get(0).keyId()).isEqualTo("905f28def18eaac0");
    assertThat(keys.get(0).isRetired()).isFalse();
    assertThat(keys.get(1).keyId()).isEqualTo("51643eac9777b63a");
    assertThat(keys.get(1).isRetired()).isTrue();
    assertThat(keys.get(1).publicKey()).isEqualTo(ZERO_KEY);
  }

  @Test
  void signingKeySetIndexesWhatTheRouteReturned() throws Exception {
    enqueueJson("{\"data\":["
        + keyResource("905f28def18eaac0", SEQUENTIAL_KEY, "active", null) + ","
        + keyResource("51643eac9777b63a", ZERO_KEY, "retired", "2026-06-01T00:00:00Z") + "]}");

    SigningKeySet set = client.signingKeySet();

    assertThat(server.takeRequest().getTarget()).isEqualTo("/v1/accounts/acct-123/signing-keys");
    assertThat(set.keyIds()).containsExactly("905f28def18eaac0", "51643eac9777b63a");
    assertThat(set.mismatchedKeyIds()).isEmpty();
    assertThat(set.skippedKeyIds()).isEmpty();
  }

  @Test
  void anAccountThatNeverRotatedAnswersAnEmptyListWhichIsNotAnError() throws Exception {
    // account_signing_keys is written only by the rotation path, so a healthy account that has
    // never rotated has no rows at all. Treating that as a failure would be wrong.
    enqueueJson("{\"data\":[]}");

    assertThat(client.listSigningKeys()).isEmpty();
    server.takeRequest();
  }

  @Test
  void licenseKeyCredentialIsRefusedByTheRoute() {
    // account.read is absent from the LicenseToken permission set, and unlike getPolicy there is
    // no sibling route reaching the same resource under a permission a license key does hold.
    server.enqueue(new MockResponse.Builder()
        .code(403)
        .addHeader("Content-Type", "application/vnd.api+json")
        .body("{\"errors\":[{\"id\":\"01920000-0000-7000-8000-000000000001\",\"status\":\"403\","
            + "\"code\":\"FORBIDDEN\",\"title\":\"t\",\"detail\":\"not allowed\"}]}")
        .build());

    assertThatThrownBy(() -> client.listSigningKeys())
        .isInstanceOf(TamgaApiException.ForbiddenException.class);
  }

  @Test
  void theReturnedListIsUnmodifiable() throws Exception {
    enqueueJson("{\"data\":[" + keyResource("51643eac9777b63a", ZERO_KEY, "active", null) + "]}");

    List<SigningKey> keys = client.listSigningKeys();

    assertThatThrownBy(() -> keys.add(null)).isInstanceOf(UnsupportedOperationException.class);
    server.takeRequest();
  }
}
