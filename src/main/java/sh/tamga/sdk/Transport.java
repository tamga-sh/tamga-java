package sh.tamga.sdk;

/**
 * {@code Transport.java}
 *
 * <p><b>STUB -- scaffolding only.</b> No HTTP implementation yet.
 *
 * <p>Intended contents once implemented:
 *
 * <ul>
 *   <li>A wrapper around a configured {@code okhttp3.OkHttpClient} (connect/read/write timeouts
 *       exposed on the builder, sane defaults e.g. 10s).
 *   <li>Auth header construction, in the order this SDK defaults to:
 *       <ol>
 *         <li>{@code Authorization: Bearer <token>}
 *         <li>{@code Authorization: Basic <base64>} (3 sub-forms: {@code email:password}, {@code
 *             token:} with empty password, {@code license:<key>})
 *         <li>{@code Authorization: License <key>} -- primary transport for this embedded/client
 *             SDK
 *       </ol>
 *       {@code Cookie: Tamga-Session=<uuid>} (browser/portal-only) and {@code ?token=}/{@code
 *       ?auth=} query-param auth are explicitly OUT OF SCOPE for this SDK.
 *   <li>Token values are always opaque strings. Do NOT build prefix-based type detection ({@code
 *       tok-}/{@code prod-}/{@code env-}/{@code activ-}/{@code lic-}) -- every issued token
 *       currently gets the {@code tok-} prefix regardless of documented type.
 *   <li>Request headers: {@code Tamga-OTP} (set per-request when a TOTP code is supplied), and
 *       {@code Tamga-Version} (sanitized alphanumeric + {@code .}/{@code -}, max 32 chars, pinned
 *       to this SDK's own major version -- NOT the server's {@code "1.8"} default, so future
 *       server-side API evolution doesn't silently reshape responses underneath a released SDK
 *       version).
 *   <li>Response header parsing into a {@code ResponseMetadata} type: {@code Tamga-Version}
 *       (echoed), {@code Tamga-Edition} ({@code "EE"}/{@code "CE"}), {@code Tamga-Mode} ({@code
 *       "singleplayer"}/{@code "multiplayer"}), {@code X-Request-Id}.
 *   <li>Content-type dispatch: {@code application/vnd.api+json} (JSON:API envelope) for all
 *       endpoints EXCEPT {@code GET .../actions/validate} (quick-validate), which is plain {@code
 *       application/json} with a flat {@code { ts, valid, detail, code }} body and no {@code
 *       data} envelope -- this one endpoint needs special-cased response parsing.
 *   <li>A Jackson {@code ObjectMapper} configured with {@code FAIL_ON_UNKNOWN_PROPERTIES = false}
 *       (forward-compat with server additions) and {@code JavaTimeModule} registered for
 *       timestamp fields.
 *   <li>Retry policy limited to network-level failures (connection reset, timeout) -- explicitly
 *       NO retry/backoff wired for HTTP 429 (see below).
 * </ul>
 *
 * <p>Explicitly NOT implemented (doc-only, matching {@code docs/sdk.md}'s "Known Server-Side
 * Gaps"): the {@code Tamga-Environment} request header (planned EE feature, no server code path
 * reads it yet), and {@code X-RateLimit-*} response header parsing / 429 retry-backoff ({@code
 * TOO_MANY_REQUESTS} is declared in the server's error enum but has no constructor and is never
 * returned by any code path today -- client-side retry-after handling would be untestable dead
 * code). Do not "fix" this later against a server that doesn't send it.
 */
public final class Transport {

  private Transport() {
    // Intentionally empty. Implementation deferred to a future session per
    // docs/plans/tamga-java.plan.md Section C.
  }
}
