package sh.tamga.sdk.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * An entitlement resource.
 *
 * <p>Despite being nested under {@code /licenses/{id}/entitlements}, list and get return full
 * entitlement resources, not lightweight junction records.
 *
 * <p>{@link #code()} is the stable, developer-facing identifier and is what
 * {@code TamgaClient.hasEntitlement} matches on. {@link #name()} is a display label that may
 * collide or change independently -- never match on it.
 */
public final class Entitlement {

  private final String id;
  private final String name;
  private final String code;
  private final Instant created;
  private final Instant updated;
  private final Map<String, Object> metadata;

  Entitlement(String id, String name, String code, Instant created, Instant updated,
      Map<String, Object> metadata) {
    this.id = id;
    this.name = name;
    this.code = code;
    this.created = created;
    this.updated = updated;
    this.metadata = metadata;
  }

  /** Decodes a single {@code {id, type, attributes}} entitlement resource node. */
  public static Entitlement fromResourceNode(JsonNode resource) {
    if (resource == null || resource.isNull()) {
      return null;
    }
    JsonNode attrs = resource.path("attributes");
    return new Entitlement(
        WireNodes.text(resource, "id"),
        WireNodes.text(attrs, "name"),
        WireNodes.text(attrs, "code"),
        WireNodes.instant(attrs, "created"),
        WireNodes.instant(attrs, "updated"),
        WireNodes.objectMap(attrs, "metadata"));
  }

  /** Returns the entitlement's unique id. */
  public String id() {
    return id;
  }

  /** Returns the display label. Never match on this -- match on {@link #code()}. */
  public String name() {
    return name;
  }

  /** Returns the stable, developer-facing entitlement code. */
  public String code() {
    return code;
  }

  /** Returns when the entitlement was created, or {@code null}. */
  public Instant created() {
    return created;
  }

  /** Returns when the entitlement was last updated, or {@code null}. */
  public Instant updated() {
    return updated;
  }

  /** Returns an unmodifiable view of arbitrary metadata, or {@code null}. */
  public Map<String, Object> metadata() {
    return metadata == null ? null : Collections.unmodifiableMap(metadata);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof Entitlement)) {
      return false;
    }
    Entitlement that = (Entitlement) other;
    return Objects.equals(id, that.id) && Objects.equals(code, that.code)
        && Objects.equals(name, that.name);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, code, name);
  }
}
