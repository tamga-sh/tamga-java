package sh.tamga.sdk.error;

/**
 * {@code TamgaError.java}
 *
 * <p><b>STUB -- scaffolding only.</b> No error model implemented yet.
 *
 * <p>Intended contents once implemented:
 *
 * <ul>
 *   <li>{@code TamgaError}: an immutable record-like type modeling the JSON:API error object
 *       {@code { status: int, code: String, detail: String, pointer: String (nullable) }},
 *       deserialized from the wire shape {@code { id, status, code, title, detail, source: {
 *       pointer } }}.
 *   <li>{@code TamgaApiException}: an unchecked exception ({@code RuntimeException}) wrapping
 *       {@code TamgaError}. Callers must match on {@code code} (stable, server-documented) and
 *       never on {@code detail} (human-readable text that may change without notice).
 *   <li>Typed exception subclasses / a central {@code code} → exception-type dispatch table
 *       (single source of truth, not duplicated per client class): {@code
 *       TamgaCheckInNotRequiredException} (§E), {@code TamgaLicenseNotEncryptedException} (§F),
 *       {@code TamgaTtlInvalidException} / {@code TamgaSchemeNotSupportedException} (§G), {@code
 *       TamgaFingerprintTakenException} (§H/§J, scope differs by call site), {@code
 *       TamgaPidTakenException} (§J), {@code TamgaKeyTakenException} ({@code 409 KEY_TAKEN}),
 *       {@code TamgaDatasetInvalidException} ({@code 422 DATASET_INVALID}), {@code
 *       TamgaLicenseKeyMissingException} ({@code 422 LICENSE_KEY_MISSING}).
 *   <li>Generic fixed-status exceptions: {@code TamgaNotFoundException} (404), {@code
 *       TamgaUnauthorizedException} (401), {@code TamgaForbiddenException} (403), {@code
 *       TamgaInternalServerErrorException} (500 -- generic; the server never leaks DB detail, so
 *       don't build parsing logic expecting structured {@code detail} here).
 *   <li>A fallback to the generic {@code TamgaApiException} for any unmapped {@code code}.
 * </ul>
 *
 * <p>Explicitly NOT modeled as a retryable/backoff case: {@code 429 TOO_MANY_REQUESTS} is declared
 * in the server's error enum but has no constructor and is never returned by any code path today
 * (see {@code docs/sdk.md}'s "Known Server-Side Gaps"). Do not build client-side 429/backoff
 * handling expecting the server to ever send it under the current deployment.
 *
 * <p>Distinct from {@code sh.tamga.sdk.error.TamgaNativeException} (not yet scaffolded -- lands
 * with §B), which is for JNI/native-layer failures, not HTTP/API errors.
 */
public final class TamgaError {

  private TamgaError() {
    // Intentionally empty. Implementation deferred to a future session per
    // docs/plans/tamga-java.plan.md Section L.
  }
}
