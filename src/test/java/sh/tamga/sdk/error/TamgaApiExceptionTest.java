package sh.tamga.sdk.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import sh.tamga.sdk.model.ResponseMetadata;
import sh.tamga.sdk.model.TamgaJsonMapper;

/** The error model: the code-to-exception dispatch table and untrusted-body tolerance. */
class TamgaApiExceptionTest {

  private static TamgaApiException dispatch(String code, int status) {
    return TamgaApiException.from(new TamgaError(null, null, code, null, "detail text", null),
        status, new ResponseMetadata("1.8", "EE", "multiplayer", "req-1"));
  }

  @Test
  void everyDocumentedCodeMapsToItsOwnType() {
    assertThat(dispatch("NOT_FOUND", 404))
        .isInstanceOf(TamgaApiException.NotFoundException.class);
    assertThat(dispatch("UNAUTHORIZED", 401))
        .isInstanceOf(TamgaApiException.UnauthorizedException.class);
    assertThat(dispatch("FORBIDDEN", 403))
        .isInstanceOf(TamgaApiException.ForbiddenException.class);
    assertThat(dispatch("INTERNAL_SERVER_ERROR", 500))
        .isInstanceOf(TamgaApiException.InternalServerErrorException.class);
    assertThat(dispatch("KEY_TAKEN", 409))
        .isInstanceOf(TamgaApiException.KeyTakenException.class);
    assertThat(dispatch("FINGERPRINT_TAKEN", 409))
        .isInstanceOf(TamgaApiException.FingerprintTakenException.class);
    assertThat(dispatch("PID_TAKEN", 409))
        .isInstanceOf(TamgaApiException.PidTakenException.class);
    assertThat(dispatch("CHECK_IN_NOT_REQUIRED", 422))
        .isInstanceOf(TamgaApiException.CheckInNotRequiredException.class);
    assertThat(dispatch("TTL_INVALID", 422))
        .isInstanceOf(TamgaApiException.TtlInvalidException.class);
    assertThat(dispatch("LICENSE_NOT_ENCRYPTED", 422))
        .isInstanceOf(TamgaApiException.LicenseNotEncryptedException.class);
    assertThat(dispatch("LICENSE_KEY_MISSING", 422))
        .isInstanceOf(TamgaApiException.LicenseKeyMissingException.class);
    assertThat(dispatch("SCHEME_NOT_SUPPORTED", 422))
        .isInstanceOf(TamgaApiException.SchemeNotSupportedException.class);
    assertThat(dispatch("DATASET_INVALID", 422))
        .isInstanceOf(TamgaApiException.DatasetInvalidException.class);
  }

  @Test
  void theFourCreateTimeLimitCodesMapToTheirOwnTypes() {
    // POST /machines enforces the policy's limits. These arrive as 422s from a call the SDK used
    // to document as enforcing nothing.
    assertThat(dispatch("MACHINE_LIMIT_EXCEEDED", 422))
        .isInstanceOf(TamgaApiException.MachineLimitExceededException.class);
    assertThat(dispatch("CORE_LIMIT_EXCEEDED", 422))
        .isInstanceOf(TamgaApiException.CoreLimitExceededException.class);
    assertThat(dispatch("MEMORY_LIMIT_EXCEEDED", 422))
        .isInstanceOf(TamgaApiException.MemoryLimitExceededException.class);
    assertThat(dispatch("DISK_LIMIT_EXCEEDED", 422))
        .isInstanceOf(TamgaApiException.DiskLimitExceededException.class);
  }

  @Test
  void theProcessLimitCodeMapsToItsOwnType() {
    assertThat(dispatch("TOO_MANY_PROCESSES", 422))
        .isInstanceOf(TamgaApiException.TooManyProcessesException.class);
  }

  @Test
  void theThreeLicenseAuthRejectionsMapToTheirOwnTypes() {
    // All three are front-door rejections of a license-key credential, and none is retryable:
    // LICENSE_NOT_ALLOWED in particular is a policy configuration precondition, not a bad key.
    assertThat(dispatch("LICENSE_SUSPENDED", 401))
        .isInstanceOf(TamgaApiException.LicenseSuspendedException.class);
    assertThat(dispatch("LICENSE_EXPIRED", 401))
        .isInstanceOf(TamgaApiException.LicenseExpiredException.class);
    assertThat(dispatch("LICENSE_NOT_ALLOWED", 401))
        .isInstanceOf(TamgaApiException.LicenseNotAllowedException.class);
  }

  @Test
  void theOriginalThirteenMappingsAreStillLive() {
    // NOT_FOUND, UNAUTHORIZED, FORBIDDEN and INTERNAL_SERVER_ERROR are baked into the server's
    // own error constructors -- they are not superseded by the more specific codes above.
    assertThat(dispatch("NOT_FOUND", 404).code()).isEqualTo("NOT_FOUND");
    assertThat(dispatch("UNAUTHORIZED", 401))
        .isInstanceOf(TamgaApiException.UnauthorizedException.class);
    assertThat(dispatch("FORBIDDEN", 403))
        .isInstanceOf(TamgaApiException.ForbiddenException.class);
    assertThat(dispatch("INTERNAL_SERVER_ERROR", 500))
        .isInstanceOf(TamgaApiException.InternalServerErrorException.class);
  }

  @Test
  void anUnmappedCodeFallsBackToTheGenericType() {
    TamgaApiException thrown = dispatch("SOMETHING_ADDED_LATER", 418);

    assertThat(thrown).isExactlyInstanceOf(TamgaApiException.class);
    assertThat(thrown.code()).isEqualTo("SOMETHING_ADDED_LATER");
    assertThat(thrown.httpStatus()).isEqualTo(418);
  }

  @Test
  void rateLimitingHasNoDedicatedTypeBecauseItIsRetriedInTheTransport() {
    // A 429 only reaches a caller once the retry budget is exhausted, and then as the generic
    // type. A dedicated subclass would imply the caller is expected to handle throttling itself.
    assertThat(dispatch("TOO_MANY_REQUESTS", 429))
        .isExactlyInstanceOf(TamgaApiException.class);
  }

  @Test
  void dispatchingIsDrivenByCodeNotHttpStatus() {
    // The same code arriving with an unexpected status still maps to its type, and an unrelated
    // code arriving with 404 does not become NotFoundException.
    assertThat(dispatch("NOT_FOUND", 500))
        .isInstanceOf(TamgaApiException.NotFoundException.class);
    assertThat(dispatch("DATASET_INVALID", 404))
        .isInstanceOf(TamgaApiException.DatasetInvalidException.class);
  }

  @Test
  void theMessageCarriesCodeAndDetail() {
    assertThat(dispatch("NOT_FOUND", 404)).hasMessage("NOT_FOUND: detail text");
  }

  @Test
  void errorWithNoDetailStillHasUsableMessage() {
    TamgaApiException thrown = TamgaApiException.from(
        new TamgaError(null, null, "FORBIDDEN", null, null, null), 403, null);

    assertThat(thrown).hasMessage("FORBIDDEN");
  }

  @Test
  void responseMetadataIsCarriedForSupportCorrelation() {
    TamgaApiException thrown = dispatch("NOT_FOUND", 404);

    assertThat(thrown.responseMetadata().requestId()).isEqualTo("req-1");
    assertThat(thrown.responseMetadata().tamgaEdition()).isEqualTo("EE");
    assertThat(thrown.responseMetadata().tamgaMode()).isEqualTo("multiplayer");
    assertThat(thrown.error().detail()).isEqualTo("detail text");
  }

  @Test
  void fullErrorDocumentDecodesEveryField() throws IOException {
    JsonNode document = TamgaJsonMapper.instance().readTree(
        "{\"errors\":[{\"id\":\"e-1\",\"status\":\"422\",\"code\":\"TTL_INVALID\","
            + "\"title\":\"Bad ttl\",\"detail\":\"too long\","
            + "\"source\":{\"pointer\":\"/meta/ttl\"}}]}");

    TamgaError error = TamgaError.fromErrorDocument(document, "fallback");

    assertThat(error.id()).isEqualTo("e-1");
    assertThat(error.status()).isEqualTo("422");
    assertThat(error.code()).isEqualTo("TTL_INVALID");
    assertThat(error.title()).isEqualTo("Bad ttl");
    assertThat(error.detail()).isEqualTo("too long");
    assertThat(error.pointer()).isEqualTo("/meta/ttl");
  }

  @Test
  void onlyTheFirstErrorIsTaken() throws IOException {
    JsonNode document = TamgaJsonMapper.instance().readTree(
        "{\"errors\":[{\"code\":\"FIRST\"},{\"code\":\"SECOND\"}]}");

    assertThat(TamgaError.fromErrorDocument(document, "fallback").code()).isEqualTo("FIRST");
  }

  @Test
  void malformedErrorDocumentsDegradeRatherThanThrow() throws IOException {
    // Error bodies arrive from the network and are untrusted. Failing to parse one must not mask
    // the HTTP status, which is the part that is actually reliable.
    assertThat(TamgaError.fromErrorDocument(null, "no body").code())
        .isEqualTo(TamgaError.UNKNOWN_CODE);

    JsonNode empty = TamgaJsonMapper.instance().readTree("{\"errors\":[]}");
    assertThat(TamgaError.fromErrorDocument(empty, "empty").code())
        .isEqualTo(TamgaError.UNKNOWN_CODE);

    JsonNode notJsonApi = TamgaJsonMapper.instance().readTree("{\"message\":\"nope\"}");
    assertThat(TamgaError.fromErrorDocument(notJsonApi, "not json:api").detail())
        .isEqualTo("not json:api");

    JsonNode wrongType = TamgaJsonMapper.instance().readTree("{\"errors\":\"a string\"}");
    assertThat(TamgaError.fromErrorDocument(wrongType, "wrong type").code())
        .isEqualTo(TamgaError.UNKNOWN_CODE);
  }

  @Test
  void anErrorWithNoCodeIsTreatedAsUnknown() throws IOException {
    JsonNode document = TamgaJsonMapper.instance().readTree(
        "{\"errors\":[{\"detail\":\"something went wrong\"}]}");

    assertThat(TamgaError.fromErrorDocument(document, "fallback").code())
        .isEqualTo(TamgaError.UNKNOWN_CODE);
  }

  @Test
  void transportFailuresAreDistinctType() {
    // A caller deciding whether to fall back to offline verification needs this distinction: a
    // transport failure says nothing about the license, whereas an API error does.
    assertThat(new TamgaTransportException("boom"))
        .isNotInstanceOf(TamgaApiException.class)
        .hasMessage("boom");
    assertThat(new TamgaTransportException("boom", new IOException("cause")))
        .hasCauseInstanceOf(IOException.class);
  }
}
