package sh.tamga.sdk.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The server's liveness report, from {@code GET /v1/health}.
 *
 * <p><b>Not a JSON:API resource.</b> The handler answers a bare
 * {@code {"status", "version", "uptime_secs"}} object with no {@code data}, no {@code type} and no
 * {@code attributes}, so it must not be put through the envelope decoder the rest of this SDK
 * uses. It is the second flat response in the protocol, alongside {@code quickValidate}.
 *
 * <p><b>Its diagnostic value is what it does not need.</b> The route is exempt from two checks
 * every other endpoint applies: it is in the server's public-route list, so no credential is
 * required, and it bypasses host-header verification entirely. That makes it a way to separate two
 * failures that otherwise look alike from the client side -- if every call is answering
 * {@code 403} with "The Host header does not match any configured host" and this one still
 * succeeds, the problem is the server's allowed-hosts configuration, not the caller's credential.
 * A credential problem would leave this route working too, so a success here never proves the
 * credential is good.
 *
 * <p>{@code version} is the <b>server's</b> build version, unrelated to this SDK's, and unrelated
 * to the {@code Tamga-Version} API revision negotiated by header.
 */
public final class HealthStatus {

  private final String status;
  private final String version;
  private final long uptimeSeconds;

  /** Creates a status report from its three already-extracted fields. */
  public HealthStatus(String status, String version, long uptimeSeconds) {
    this.status = status;
    this.version = version;
    this.uptimeSeconds = uptimeSeconds;
  }

  /**
   * Decodes the flat health document. Reads the fields off the root node directly -- there is no
   * {@code data} envelope to unwrap.
   */
  public static HealthStatus fromJson(JsonNode root) {
    if (root == null || root.isNull()) {
      return null;
    }
    Long uptime = WireNodes.longValue(root, "uptime_secs");
    return new HealthStatus(WireNodes.text(root, "status"), WireNodes.text(root, "version"),
        uptime == null ? 0L : uptime);
  }

  /** Returns the reported status string, {@code "ok"} on a healthy server. */
  public String status() {
    return status;
  }

  /** Returns the server's own build version -- not this SDK's, and not the API revision. */
  public String version() {
    return version;
  }

  /**
   * Returns how many seconds the server process has been running.
   *
   * <p>Useful as a restart detector: a value that has gone backwards between two calls means the
   * process was replaced, which explains an in-memory rate-limit bucket or connection pool
   * behaving as though it had never seen this client.
   */
  public long uptimeSeconds() {
    return uptimeSeconds;
  }
}
