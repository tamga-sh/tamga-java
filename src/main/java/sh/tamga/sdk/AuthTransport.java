package sh.tamga.sdk;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import okhttp3.HttpUrl;
import okhttp3.Request;

/**
 * Applies one authentication scheme to an outgoing request.
 *
 * <p>The server accepts seven forms and tries them in a fixed order. This SDK deliberately sends
 * <b>exactly one</b> -- whichever the caller configured -- rather than replicating that fallback
 * chain. {@link #licenseKey(String)} is the default for an embedded client.
 *
 * <p><b>Authentication is enforced server-side.</b> Every endpoint this SDK calls sits behind it,
 * and a missing or bad credential is rejected -- do not build client logic that assumes otherwise.
 *
 * <p><b>{@link #licenseKey(String)} additionally depends on the license's policy.</b> The server
 * accepts a license key only when the policy's {@code authentication_strategy} is {@code LICENSE}
 * or {@code MIXED}; the column defaults to {@code TOKEN}, and {@code NONE} is refused the same
 * way. A policy that was never configured for it answers {@code 401 LICENSE_NOT_ALLOWED}
 * ({@link sh.tamga.sdk.error.TamgaApiException.LicenseNotAllowedException}) to every call. That is
 * a configuration precondition, not a transient failure: retrying, or asking the user for their
 * key again, cannot fix it. Two further front-door rejections apply to the same credential
 * regardless of endpoint -- {@code 401 LICENSE_SUSPENDED} for a suspended license, and
 * {@code 401 LICENSE_EXPIRED} for an expired one whose policy uses {@code REVOKE_ACCESS}.
 *
 * <p><b>Tokens are opaque strings.</b> The server documents {@code tok-}/{@code prod-}/{@code env-}
 * /{@code activ-}/{@code lic-} prefixes per token type, but every issued token currently gets the
 * {@code tok-} prefix regardless of its type. Never build prefix-based type detection against that
 * documented-but-unimplemented convention.
 */
@FunctionalInterface
public interface AuthTransport {

  /**
   * Applies this transport's credentials to a request under construction.
   *
   * <p>Header-based transports mutate {@code requestBuilder}; the query-parameter transport mutates
   * {@code urlBuilder} instead. Both are supplied so a single interface covers every form.
   */
  void apply(HttpUrl.Builder urlBuilder, Request.Builder requestBuilder);

  /** Sends {@code Authorization: Bearer <token>}. */
  static AuthTransport bearer(String token) {
    return (url, request) -> request.header("Authorization", "Bearer " + token);
  }

  /** Sends {@code Authorization: Basic base64("<email>:<password>")}. */
  static AuthTransport basicEmailPassword(String email, String password) {
    return basic(email + ":" + password);
  }

  /**
   * Sends {@code Authorization: Basic base64("<token>:")} -- the token as the username with an
   * empty password. Note the trailing colon: omitting it produces a different, invalid credential.
   */
  static AuthTransport basicToken(String token) {
    return basic(token + ":");
  }

  /** Sends {@code Authorization: Basic base64("license:<key>")}. */
  static AuthTransport basicLicenseKey(String key) {
    return basic("license:" + key);
  }

  /**
   * Sends {@code Authorization: License <key>} -- the primary transport for an embedded client SDK
   * validating against a raw license key, and this SDK's default.
   *
   * <p>Requires the license's policy to allow it -- see this interface's note on
   * {@code authentication_strategy}, which defaults to a value that does not.
   */
  static AuthTransport licenseKey(String key) {
    return (url, request) -> request.header("Authorization", "License " + key);
  }

  /**
   * Sends {@code Cookie: Tamga-Session=<uuid>}.
   *
   * <p>Provided for completeness only. This form is browser and portal oriented -- the server pairs
   * it with an {@code Origin} check -- and is rarely the right choice for a non-browser consumer.
   */
  static AuthTransport sessionCookie(String sessionId) {
    return (url, request) -> request.header("Cookie", "Tamga-Session=" + sessionId);
  }

  /**
   * Sends the token as a {@code ?token=} query parameter. The server also accepts {@code ?auth=} as
   * a synonym; this transport sends {@code token}, mirroring the bearer semantics it substitutes
   * for.
   *
   * <p>Prefer a header transport where possible: query strings are far more likely to be captured
   * in proxy logs, browser history, and referrer headers than an {@code Authorization} header is.
   */
  static AuthTransport queryParam(String token) {
    return (url, request) -> url.setQueryParameter("token", token);
  }

  /** Shared base64 encoder for the three {@code Basic} sub-forms. */
  private static AuthTransport basic(String userPass) {
    String encoded =
        Base64.getEncoder().encodeToString(userPass.getBytes(StandardCharsets.UTF_8));
    return (url, request) -> request.header("Authorization", "Basic " + encoded);
  }
}
