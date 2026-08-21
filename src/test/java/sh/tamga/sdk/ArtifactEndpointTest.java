package sh.tamga.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.NullNode;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh.tamga.sdk.error.TamgaApiException;
import sh.tamga.sdk.error.TamgaTransportException;
import sh.tamga.sdk.model.Artifact;
import sh.tamga.sdk.model.ListOptions;
import sh.tamga.sdk.model.Page;

/**
 * The artifact read and download surface. Reading metadata was always permitted to a licence key
 * ({@code artifact.read}, {@code authz/mod.rs:264}); fetching the bytes was not, until
 * {@code Role::LicenseToken} gained {@code artifact.download} ({@code :265}) -- which is why all
 * three routes are only worth modelling now.
 *
 * <p>Three server behaviours are pinned here because each has a plausible wrong answer that no
 * other test would catch: the timestamps are {@code created}/{@code updated} under a camelCase
 * rule that would otherwise make them {@code createdAt}/{@code updatedAt}; the download must ask
 * for {@code redirect=false} rather than following a {@code 303} to storage with the licence key
 * attached; and a {@code 403} on the download can come from the owning release's gate rather than
 * from the permission.
 */
class ArtifactEndpointTest {

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

  /**
   * One artifact resource exactly as {@code artifacts/serializer.rs} emits it: camelCase
   * throughout, but {@code created}/{@code updated} rather than {@code createdAt}/{@code
   * updatedAt}, and no {@code redirectUrl} key at all unless {@code redirect} is passed.
   */
  private static String artifactResource(String id, String redirectUrl) {
    return "{\"id\":\"" + id + "\",\"type\":\"artifacts\",\"attributes\":{"
        + "\"filename\":\"app-1.2.0-x86_64.dmg\",\"filetype\":\"dmg\",\"filesize\":41943040,"
        + "\"checksum\":\"sha256:abc\",\"platform\":\"darwin\",\"arch\":\"arm64\","
        + "\"signature\":\"sig-1\",\"status\":\"UPLOADED\","
        + (redirectUrl == null ? "" : "\"redirectUrl\":\"" + redirectUrl + "\",")
        + "\"metadata\":{\"channel\":\"stable\"},"
        + "\"created\":\"2026-08-21T10:00:00Z\",\"updated\":\"2026-08-21T11:00:00Z\"}}";
  }

  // ------------------------------------------------------------------- reads

  @Test
  void listArtifactsReachesTheReleaseNestedRouteWithKeysetParameters() throws Exception {
    enqueueJson("{\"data\":[" + artifactResource("art-1", null) + "]}");

    Page<Artifact> page = client.listArtifacts("rel-1", ListOptions.ofLimit(2));

    RecordedRequest request = server.takeRequest();
    assertThat(request.getMethod()).isEqualTo("GET");
    assertThat(request.getTarget())
        .isEqualTo("/v1/accounts/acct-123/releases/rel-1/artifacts?limit=2");
    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).filename()).isEqualTo("app-1.2.0-x86_64.dmg");
    // A short page ends the listing -- one row against a limit of two.
    assertThat(page.nextCursor()).isNull();
  }

  @Test
  void listArtifactsSynthesizesTheNextCursorFromTheLastIdOnFullPages() throws Exception {
    enqueueJson("{\"data\":[" + artifactResource("art-1", null) + ","
        + artifactResource("art-2", null) + "]}");

    Page<Artifact> page = client.listArtifacts("rel-1", ListOptions.ofLimit(2));

    // This route sends neither meta.page nor links, so a full page is the only "there may be more"
    // signal there is.
    assertThat(page.nextCursor()).isEqualTo("art-2");
  }

  @Test
  void listArtifactsDefaultsToTheServerMaximumRatherThanItsDefaultOf25() throws Exception {
    enqueueJson("{\"data\":[]}");

    client.listArtifacts("rel-1", null);

    assertThat(server.takeRequest().getTarget())
        .isEqualTo("/v1/accounts/acct-123/releases/rel-1/artifacts?limit=100");
  }

  @Test
  void listArtifactsEscapesTheReleaseIdIntoOnePathSegment() throws Exception {
    enqueueJson("{\"data\":[]}");

    client.listArtifacts("../../licenses/lic-9", ListOptions.ofLimit(1));

    assertThat(server.takeRequest().getTarget())
        .isEqualTo("/v1/accounts/acct-123/releases/..%2F..%2Flicenses%2Flic-9/artifacts?limit=1");
  }

  @Test
  void getArtifactReadsTheStandaloneRoute() throws Exception {
    enqueueJson("{\"data\":" + artifactResource("art-1", null) + "}");

    Artifact artifact = client.getArtifact("art-1");

    assertThat(server.takeRequest().getTarget())
        .isEqualTo("/v1/accounts/acct-123/artifacts/art-1");
    assertThat(artifact.id()).isEqualTo("art-1");
    assertThat(artifact.status()).isEqualTo("UPLOADED");
    assertThat(artifact.filesize()).isEqualTo(41_943_040L);
    assertThat(artifact.checksum()).isEqualTo("sha256:abc");
    assertThat(artifact.platform()).isEqualTo("darwin");
    assertThat(artifact.arch()).isEqualTo("arm64");
    assertThat(artifact.signature()).isEqualTo("sig-1");
    assertThat(artifact.filetype()).isEqualTo("dmg");
    assertThat(artifact.metadata()).containsEntry("channel", "stable");
  }

  @Test
  void timestampsAreCreatedAndUpdatedDespiteTheCamelCaseRule() throws Exception {
    // The trap: rename_all = "camelCase" is on the whole struct, so redirectUrl really is
    // camelCase -- but created_at/updated_at carry their own #[serde(rename)] on top of it
    // (serializer.rs:34-37). Applying the camelCase rule uniformly reads two nulls and nothing
    // complains.
    enqueueJson("{\"data\":{\"id\":\"art-1\",\"type\":\"artifacts\",\"attributes\":{"
        + "\"filename\":\"a.bin\",\"status\":\"UPLOADED\",\"metadata\":{},"
        + "\"createdAt\":\"2026-01-01T00:00:00Z\",\"updatedAt\":\"2026-01-02T00:00:00Z\","
        + "\"created\":\"2026-08-21T10:00:00Z\",\"updated\":\"2026-08-21T11:00:00Z\"}}}");

    Artifact artifact = client.getArtifact("art-1");

    assertThat(artifact.created()).isEqualTo(Instant.parse("2026-08-21T10:00:00Z"));
    assertThat(artifact.updated()).isEqualTo(Instant.parse("2026-08-21T11:00:00Z"));
  }

  @Test
  void redirectUrlIsAbsentOnTheReadRoutesRatherThanNull() throws Exception {
    // skip_serializing_if = "Option::is_none" -- the key is not in the document at all on a read,
    // which is normal and not an error.
    enqueueJson("{\"data\":" + artifactResource("art-1", null) + "}");

    assertThat(client.getArtifact("art-1").redirectUrl()).isNull();
  }

  @Test
  void anAbsentResourceNodeDecodesToNullRatherThanThrowing() {
    // Every model in this SDK treats a missing resource and an explicit JSON null the same way,
    // because a decoder that throws on a reshaped response turns schema drift into an outage.
    assertThat(Artifact.fromResourceNode(null)).isNull();
    assertThat(Artifact.fromResourceNode(NullNode.getInstance())).isNull();
  }

  @Test
  void metadataReadsNullWhenTheAttributeIsAbsentRatherThanAnEmptyMap() throws Exception {
    // Absent and empty are different facts, and only the caller knows which one matters.
    enqueueJson("{\"data\":{\"id\":\"art-1\",\"type\":\"artifacts\",\"attributes\":{"
        + "\"filename\":\"a.bin\",\"status\":\"UPLOADED\"}}}");

    assertThat(client.getArtifact("art-1").metadata()).isNull();
  }

  // ---------------------------------------------------------------- download

  @Test
  void downloadAsksForTheBodyRatherThanThe303ThatCarriesTheCredentialToStorage() throws Exception {
    enqueueJson("{\"data\":" + artifactResource("art-1", "https://storage.example/art-1?sig=x")
        + "}");

    Artifact artifact = client.requestArtifactDownload("art-1");

    RecordedRequest request = server.takeRequest();
    assertThat(request.getTarget())
        .isEqualTo("/v1/accounts/acct-123/artifacts/art-1/actions/download?redirect=false");
    assertThat(artifact.redirectUrl()).isEqualTo("https://storage.example/art-1?sig=x");
    // The credential goes to the API and nowhere else: the SDK returns the storage URL rather than
    // fetching it, so it cannot attach the licence key to a request at the storage host.
    assertThat(request.getHeaders().get("Authorization")).isEqualTo("License lic-abc");
  }

  @Test
  void theDefault303IsNeverFollowedIfTheRedirectParameterIsIgnored() throws Exception {
    // Defence in depth for the case that matters most: if redirect=false were dropped by a proxy,
    // or a future server ignored it, the client must not follow the Location to storage with the
    // Authorization header still on the request. It refuses redirects outright, so the 303
    // surfaces as an error and exactly one request is ever made.
    server.enqueue(new MockResponse.Builder()
        .code(303)
        .addHeader("Location", "https://storage.example/art-1?sig=x")
        .build());

    assertThatThrownBy(() -> client.requestArtifactDownload("art-1"))
        .isInstanceOf(TamgaApiException.class);

    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @Test
  void downloadSendsAnExplicitTtlInSeconds() throws Exception {
    enqueueJson("{\"data\":" + artifactResource("art-1", "https://storage.example/a") + "}");

    client.requestArtifactDownload("art-1", Duration.ofHours(2));

    assertThat(server.takeRequest().getTarget()).isEqualTo(
        "/v1/accounts/acct-123/artifacts/art-1/actions/download?redirect=false&ttl=7200");
  }

  @Test
  void downloadOmitsTtlEntirelyWhenTheCallerNamesNone() throws Exception {
    enqueueJson("{\"data\":" + artifactResource("art-1", "https://storage.example/a") + "}");

    client.requestArtifactDownload("art-1", null);

    // Omitted, not sent as the server's default: a client-side copy of that default would go stale
    // the moment the server changed it.
    assertThat(server.takeRequest().getTarget())
        .isEqualTo("/v1/accounts/acct-123/artifacts/art-1/actions/download?redirect=false");
  }

  @Test
  void ttlBelowTheServerMinimumIsRefusedWithoutTouchingTheNetwork() {
    assertThatThrownBy(() -> client.requestArtifactDownload("art-1", Duration.ofSeconds(59)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("60");

    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void ttlAboveTheServerMaximumIsRefusedWithoutTouchingTheNetwork() {
    assertThatThrownBy(() -> client.requestArtifactDownload("art-1", Duration.ofDays(8)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("604800");

    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void bothTtlBoundsAreThemselvesAccepted() throws Exception {
    enqueueJson("{\"data\":" + artifactResource("art-1", "https://storage.example/a") + "}");
    enqueueJson("{\"data\":" + artifactResource("art-1", "https://storage.example/a") + "}");

    client.requestArtifactDownload("art-1", Artifact.MIN_DOWNLOAD_TTL);
    client.requestArtifactDownload("art-1", Artifact.MAX_DOWNLOAD_TTL);

    assertThat(server.takeRequest().getTarget()).endsWith("&ttl=60");
    assertThat(server.takeRequest().getTarget()).endsWith("&ttl=604800");
  }

  @Test
  void forbiddenOnDownloadMayBeTheReleaseGateRatherThanThePermission() {
    // enforce_release_access runs on this route as well as require_download, so a CLOSED
    // distribution strategy refuses a licence key that genuinely holds artifact.download. All four
    // refusals carry the same generic FORBIDDEN code and differ only in detail, so nothing here
    // can be branched on beyond the status.
    enqueueError(403, "FORBIDDEN",
        "This release is only accessible to admins, developers, and product tokens");

    assertThatThrownBy(() -> client.requestArtifactDownload("art-1"))
        .isInstanceOf(TamgaApiException.ForbiddenException.class)
        .hasMessageContaining("admins, developers, and product tokens");
  }

  @Test
  void downloadUrlsWithNonHttpSchemesAreRefusedRatherThanHandedBack() {
    // redirectUrl is chosen by the server and handed straight to whatever HTTP client the
    // application uses, so "it parsed" is not the test that matters. HttpUrl.parse answers the
    // right question -- measured: it returns null for file:, ftp:, jar:, javascript:, a relative
    // path and a Windows path, and accepts HTTPS: case-insensitively.
    for (String hostile : new String[] {"file:///etc/passwd", "ftp://h/f", "jar:file:///x!/y",
        "javascript:alert(1)", "/relative/path"}) {
      enqueueJson("{\"data\":" + artifactResource("art-1", hostile) + "}");

      assertThatThrownBy(() -> client.requestArtifactDownload("art-1"))
          .as(hostile)
          .isInstanceOf(TamgaTransportException.class)
          .hasMessageContaining("not an http or https URL");
    }
  }

  @Test
  void uppercaseHttpsSchemesAreStillHttpsUrls() {
    enqueueJson("{\"data\":" + artifactResource("art-1", "HTTPS://storage.example/o") + "}");

    assertThat(client.requestArtifactDownload("art-1").redirectUrl())
        .isEqualTo("HTTPS://storage.example/o");
  }

  @Test
  void downloadAnsweringWithoutAnyUrlIsAnErrorNotSilentNull() {
    // Sending redirect=false is precisely what asks for the URL in the body, so a response without
    // one is not something a caller can act on -- and returning null would look like the read
    // routes, where absence is normal.
    enqueueJson("{\"data\":" + artifactResource("art-1", null) + "}");

    assertThatThrownBy(() -> client.requestArtifactDownload("art-1"))
        .isInstanceOf(TamgaTransportException.class)
        .hasMessageContaining("without a redirectUrl");
  }

  @Test
  void downloadAnsweringWithNoResourceAtAllIsAnError() {
    enqueueJson("{\"data\":null}");

    assertThatThrownBy(() -> client.requestArtifactDownload("art-1"))
        .isInstanceOf(TamgaTransportException.class)
        .hasMessageContaining("no artifact resource");
  }

  @Test
  void refusedDownloadUrlsAreNotEchoedIntoTheMessage() {
    // A presigned URL carries its whole authorisation in the query string. A rejected one is still
    // a URL somebody chose, and a message that quotes it lands in a log.
    enqueueJson("{\"data\":"
        + artifactResource("art-1", "ftp://storage.example/o?sig=SECRET") + "}");

    assertThatThrownBy(() -> client.requestArtifactDownload("art-1"))
        .isInstanceOf(TamgaTransportException.class)
        .hasMessageNotContaining("SECRET")
        .hasMessageContaining("scheme: ftp");
  }

  @Test
  void presignTtlInvalidIsNotTheCheckoutTtlInvalidCode() {
    // The download route's code is PRESIGN_TTL_INVALID (artifacts/service.rs:33) while the
    // checkout routes use TTL_INVALID (check_out_license.rs:48). The typed exception is keyed on
    // the unprefixed spelling, so a caller catching TtlInvalidException around a download would
    // miss this one. Pinned so nobody assumes the mapping either way. The client validates the
    // range locally, so reaching this at all takes a server whose bounds have moved.
    enqueueError(422, "PRESIGN_TTL_INVALID",
        "Presigned URL TTL must be between 1 minute and 1 week");

    assertThatThrownBy(() -> client.requestArtifactDownload("art-1"))
        .isInstanceOf(TamgaApiException.class)
        .isNotInstanceOf(TamgaApiException.TtlInvalidException.class);
  }

  @Test
  void storageBeingUnconfiguredSurfacesAsItsOwn422() {
    enqueueError(422, "STORAGE_UNAVAILABLE",
        "No storage backend is configured, so artifacts cannot be downloaded");

    assertThatThrownBy(() -> client.requestArtifactDownload("art-1"))
        .isInstanceOf(TamgaApiException.class)
        .hasMessageContaining("STORAGE_UNAVAILABLE");
  }
}
