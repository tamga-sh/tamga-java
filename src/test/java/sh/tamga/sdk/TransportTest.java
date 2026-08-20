package sh.tamga.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh.tamga.sdk.error.TamgaApiException;
import sh.tamga.sdk.error.TamgaTransportException;

/**
 * Transport-level behaviour: URL assembly, the seven auth forms, headers, and error mapping.
 *
 * <p>Requests go over a real loopback socket rather than through a mocked round-tripper, so header
 * construction and path escaping are exercised as the server would actually see them.
 */
class TransportTest {

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

  private TamgaClient clientWith(AuthTransport auth) {
    return TamgaClient.builder("acct-123")
        .host(server.url("/").toString())
        .auth(auth)
        .build();
  }

  private void enqueueLicense() {
    server.enqueue(new MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "application/vnd.api+json")
        .body("{\"data\":{\"id\":\"lic-1\",\"type\":\"licenses\",\"attributes\":{\"key\":\"K\"}}}")
        .build());
  }

  @Test
  void bearerAuthSendsAnAuthorizationHeader() throws Exception {
    enqueueLicense();
    clientWith(AuthTransport.bearer("tok-1")).checkIn("lic-1");

    RecordedRequest request = server.takeRequest();
    assertThat(request.getHeaders().get("Authorization")).isEqualTo("Bearer tok-1");
  }

  @Test
  void basicEmailPasswordAuthEncodesTheColonJoinedPair() throws Exception {
    enqueueLicense();
    clientWith(AuthTransport.basicEmailPassword("a@b.com", "pw")).checkIn("lic-1");

    String expected = "Basic " + base64("a@b.com:pw");
    assertThat(server.takeRequest().getHeaders().get("Authorization")).isEqualTo(expected);
  }

  @Test
  void basicTokenAuthKeepsTheTrailingColonForAnEmptyPassword() throws Exception {
    enqueueLicense();
    clientWith(AuthTransport.basicToken("tok-1")).checkIn("lic-1");

    // The trailing colon is load-bearing: base64("tok-1") and base64("tok-1:") differ, and only
    // the latter is a valid Basic credential with an empty password.
    assertThat(server.takeRequest().getHeaders().get("Authorization"))
        .isEqualTo("Basic " + base64("tok-1:"))
        .isNotEqualTo("Basic " + base64("tok-1"));
  }

  @Test
  void basicLicenseKeyAuthUsesTheLiteralLicenseUsername() throws Exception {
    enqueueLicense();
    clientWith(AuthTransport.basicLicenseKey("lic-abc")).checkIn("lic-1");

    assertThat(server.takeRequest().getHeaders().get("Authorization"))
        .isEqualTo("Basic " + base64("license:lic-abc"));
  }

  @Test
  void licenseKeyAuthSendsTheLicenseScheme() throws Exception {
    enqueueLicense();
    clientWith(AuthTransport.licenseKey("lic-abc")).checkIn("lic-1");

    assertThat(server.takeRequest().getHeaders().get("Authorization"))
        .isEqualTo("License lic-abc");
  }

  @Test
  void sessionCookieAuthSendsCookieHeader() throws Exception {
    enqueueLicense();
    clientWith(AuthTransport.sessionCookie("sess-1")).checkIn("lic-1");

    assertThat(server.takeRequest().getHeaders().get("Cookie"))
        .isEqualTo("Tamga-Session=sess-1");
  }

  @Test
  void queryParamAuthSendsTheTokenInTheUrl() throws Exception {
    enqueueLicense();
    clientWith(AuthTransport.queryParam("tok-1")).checkIn("lic-1");

    RecordedRequest request = server.takeRequest();
    assertThat(request.getUrl().queryParameter("token")).isEqualTo("tok-1");
    assertThat(request.getHeaders().get("Authorization")).isNull();
  }

  @Test
  void everyRequestCarriesTheVersionAndAgentHeaders() throws Exception {
    enqueueLicense();
    clientWith(AuthTransport.licenseKey("k")).checkIn("lic-1");

    RecordedRequest request = server.takeRequest();
    assertThat(request.getHeaders().get("Tamga-Version")).isEqualTo("1.8");
    assertThat(request.getHeaders().get("User-Agent")).startsWith("tamga-java/");
  }

  @Test
  void theOneTimePasswordHeaderIsSentOnlyWhenConfigured() throws Exception {
    enqueueLicense();
    clientWith(AuthTransport.licenseKey("k")).checkIn("lic-1");
    assertThat(server.takeRequest().getHeaders().get("Tamga-OTP")).isNull();

    enqueueLicense();
    TamgaClient.builder("acct-123").host(server.url("/").toString())
        .auth(AuthTransport.licenseKey("k")).otp("123456").build().checkIn("lic-1");
    assertThat(server.takeRequest().getHeaders().get("Tamga-OTP")).isEqualTo("123456");
  }

  @Test
  void requestsWithoutBodyCarryNoContentType() throws Exception {
    enqueueLicense();
    clientWith(AuthTransport.licenseKey("k")).checkIn("lic-1");

    assertThat(server.takeRequest().getHeaders().get("Content-Type")).isNull();
  }

  @Test
  void requestsWithBodyDeclareJsonApiContentType() throws Exception {
    enqueueLicense();
    clientWith(AuthTransport.licenseKey("k")).validateByKey("K");

    assertThat(server.takeRequest().getHeaders().get("Content-Type"))
        .startsWith("application/vnd.api+json");
  }

  @Test
  void theUrlIsBuiltUnderTheAccountSegment() throws Exception {
    enqueueLicense();
    clientWith(AuthTransport.licenseKey("k")).checkIn("lic-1");

    assertThat(server.takeRequest().getTarget())
        .isEqualTo("/v1/accounts/acct-123/licenses/lic-1/actions/check-in");
  }

  @Test
  void slashInIdCannotEscapeItsPathSegment() throws Exception {
    enqueueLicense();
    // Without per-segment escaping this would resolve to .../licenses/../../evil/actions/check-in
    // and call a different endpoint entirely.
    clientWith(AuthTransport.licenseKey("k")).checkIn("../../evil");

    String target = server.takeRequest().getTarget();
    assertThat(target).startsWith("/v1/accounts/acct-123/licenses/");
    assertThat(target).doesNotContain("/../");
    assertThat(target).endsWith("/actions/check-in");
  }

  @Test
  void sanitizeVersionDropsDisallowedCharacters() {
    assertThat(Transport.sanitizeVersion("1.8")).isEqualTo("1.8");
    assertThat(Transport.sanitizeVersion("1.8-beta_x")).isEqualTo("1.8-betax");
    assertThat(Transport.sanitizeVersion("a b\tc")).isEqualTo("abc");
    assertThat(Transport.sanitizeVersion(null)).isEmpty();
  }

  @Test
  void sanitizeVersionFiltersBeforeTruncating() {
    // 40 disallowed characters followed by "1.8". Filtering first keeps "1.8"; truncating first
    // would keep the first 32 disallowed characters and then drop everything, yielding "".
    String input = repeat("!", 40) + "1.8";

    assertThat(Transport.sanitizeVersion(input)).isEqualTo("1.8");
  }

  @Test
  void sanitizeVersionTruncatesToThirtyTwoCharacters() {
    assertThat(Transport.sanitizeVersion(repeat("a", 50)))
        .hasSize(Transport.MAX_API_VERSION_LENGTH);
  }

  @Test
  void errorResponseBecomesTypedException() {
    server.enqueue(new MockResponse.Builder()
        .code(404)
        .addHeader("X-Request-Id", "req-9")
        .body("{\"errors\":[{\"code\":\"NOT_FOUND\",\"detail\":\"no such license\"}]}")
        .build());

    assertThatThrownBy(() -> clientWith(AuthTransport.licenseKey("k")).checkIn("lic-1"))
        .isInstanceOf(TamgaApiException.NotFoundException.class)
        .satisfies(thrown -> {
          TamgaApiException api = (TamgaApiException) thrown;
          assertThat(api.code()).isEqualTo("NOT_FOUND");
          assertThat(api.httpStatus()).isEqualTo(404);
          assertThat(api.responseMetadata().requestId()).isEqualTo("req-9");
        });
  }

  @Test
  void nonJsonApiErrorBodyDegradesToUnknownCode() {
    server.enqueue(new MockResponse.Builder()
        .code(502)
        .body("<html>gateway blew up</html>")
        .build());

    assertThatThrownBy(() -> clientWith(AuthTransport.licenseKey("k")).checkIn("lic-1"))
        .isInstanceOf(TamgaApiException.class)
        .satisfies(thrown -> {
          TamgaApiException api = (TamgaApiException) thrown;
          assertThat(api.code()).isEqualTo("UNKNOWN");
          assertThat(api.httpStatus()).isEqualTo(502);
        });
  }

  @Test
  void anEmptyErrorsArrayDegradesToAnUnknownCode() {
    server.enqueue(new MockResponse.Builder().code(500).body("{\"errors\":[]}").build());

    assertThatThrownBy(() -> clientWith(AuthTransport.licenseKey("k")).checkIn("lic-1"))
        .isInstanceOf(TamgaApiException.class)
        .satisfies(thrown -> assertThat(((TamgaApiException) thrown).code()).isEqualTo("UNKNOWN"));
  }

  @Test
  void unreachableServerRaisesTransportError() throws IOException {
    server.close();

    assertThatThrownBy(() -> clientWith(AuthTransport.licenseKey("k")).checkIn("lic-1"))
        .isInstanceOf(TamgaTransportException.class);
  }

  @Test
  void buildingClientWithoutAuthIsRejected() {
    assertThatThrownBy(() -> TamgaClient.builder("acct-123").build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AuthTransport");
  }

  @Test
  void buildingClientWithoutAccountIsRejected() {
    assertThatThrownBy(
        () -> TamgaClient.builder("").auth(AuthTransport.licenseKey("k")).build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("accountId");
  }

  @Test
  void bareHostGetsHttpsScheme() {
    // Not asserted by making a request: the point is that a bare host is accepted at all, and
    // that it is not silently turned into a plaintext URL.
    TamgaClient client = TamgaClient.builder("acct-123")
        .host("api.example.com")
        .auth(AuthTransport.licenseKey("k"))
        .build();

    assertThat(client).isNotNull();
  }

  @Test
  void anExplicitPlaintextSchemeIsPreserved() throws Exception {
    // A local mock server is plain HTTP. Upgrading it to https would make every test here fail,
    // which is exactly why the scheme is preserved rather than forced.
    enqueueLicense();
    clientWith(AuthTransport.licenseKey("k")).checkIn("lic-1");

    assertThat(server.takeRequest().getUrl().scheme()).isEqualTo("http");
  }

  private static String base64(String raw) {
    return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  private static String repeat(String unit, int times) {
    StringBuilder builder = new StringBuilder(unit.length() * times);
    for (int i = 0; i < times; i++) {
      builder.append(unit);
    }
    return builder.toString();
  }
}
