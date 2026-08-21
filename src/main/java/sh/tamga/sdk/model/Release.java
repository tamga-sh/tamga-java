package sh.tamga.sdk.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * A release resource -- one published build of a product, as returned by the upgrade check.
 *
 * <p><b>Its attribute keys are camelCase, and that is the exception, not this SDK's convention.</b>
 * Every other resource in this API serialises {@code snake_case} attributes; the release
 * serialiser carries a {@code rename_all = "camelCase"} that the others do not, so the owning
 * product arrives as {@code productId} rather than {@code product_id}. A decoder that assumes the
 * house style reads {@code null} for it and nothing else complains. The two timestamps are renamed
 * individually on top of that rule, so they stay {@code created}/{@code updated} rather than
 * becoming {@code createdAt}/{@code updatedAt}.
 *
 * <p>{@code tag} is omitted from the response entirely when the release has none, rather than sent
 * as null.
 *
 * <p>There is no download URL here, because the release resource carries none: the bytes live on
 * its {@link Artifact}s. That is a change of reason rather than of fact -- this note previously
 * said the artifact route was unreachable to every credential this SDK issues, which stopped being
 * true when {@code Role::LicenseToken} gained {@code artifact.read} and {@code artifact.download}
 * ({@code authz/mod.rs:264-265}). Reach the bytes with
 * {@code TamgaClient.listArtifacts(release.id(), ...)} followed by
 * {@code requestArtifactDownload(...)}.
 */
public final class Release {

  private final String id;
  private final String productId;
  private final String name;
  private final String version;
  private final String channel;
  private final String status;
  private final String tag;
  private final Map<String, Object> metadata;
  private final Instant created;
  private final Instant updated;

  @SuppressWarnings("checkstyle:ParameterNumber")
  private Release(String id, String productId, String name, String version, String channel,
      String status, String tag, Map<String, Object> metadata, Instant created, Instant updated) {
    this.id = id;
    this.productId = productId;
    this.name = name;
    this.version = version;
    this.channel = channel;
    this.status = status;
    this.tag = tag;
    this.metadata = metadata;
    this.created = created;
    this.updated = updated;
  }

  /**
   * Decodes a single {@code {id, type, attributes}} release resource node. Returns {@code null} for
   * a null or absent node.
   */
  public static Release fromResourceNode(JsonNode resource) {
    if (resource == null || resource.isNull()) {
      return null;
    }
    JsonNode attrs = resource.path("attributes");
    return new Release(
        WireNodes.text(resource, "id"),
        // camelCase, unlike every other resource in this API -- see this class's note.
        WireNodes.text(attrs, "productId"),
        WireNodes.text(attrs, "name"),
        WireNodes.text(attrs, "version"),
        WireNodes.text(attrs, "channel"),
        WireNodes.text(attrs, "status"),
        WireNodes.text(attrs, "tag"),
        WireNodes.objectMap(attrs, "metadata"),
        WireNodes.instant(attrs, "created"),
        WireNodes.instant(attrs, "updated"));
  }

  /** Returns the release's unique id. */
  public String id() {
    return id;
  }

  /** Returns the id of the product this release belongs to. */
  public String productId() {
    return productId;
  }

  /** Returns the release's display name, or {@code null}. */
  public String name() {
    return name;
  }

  /** Returns the release version -- the value to compare against what is currently installed. */
  public String version() {
    return version;
  }

  /** Returns the release channel, such as {@code stable}. */
  public String channel() {
    return channel;
  }

  /** Returns the release status as a raw wire string. */
  public String status() {
    return status;
  }

  /** Returns the release tag, or {@code null} when the release carries none. */
  public String tag() {
    return tag;
  }

  /** Returns an unmodifiable view of arbitrary release metadata, or {@code null}. */
  public Map<String, Object> metadata() {
    return metadata == null ? null : Collections.unmodifiableMap(metadata);
  }

  /** Returns when the release was published, or {@code null}. */
  public Instant created() {
    return created;
  }

  /** Returns when the release was last updated, or {@code null}. */
  public Instant updated() {
    return updated;
  }
}
