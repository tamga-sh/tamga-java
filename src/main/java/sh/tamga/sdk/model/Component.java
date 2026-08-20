package sh.tamga.sdk.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * A component resource -- a sub-part of a machine, identified by its own fingerprint and unique
 * per machine.
 */
public final class Component {

  private final String id;
  private final String fingerprint;
  private final String name;
  private final String machineId;
  private final Instant created;
  private final Instant updated;
  private final Map<String, Object> metadata;

  Component(String id, String fingerprint, String name, String machineId, Instant created,
      Instant updated, Map<String, Object> metadata) {
    this.id = id;
    this.fingerprint = fingerprint;
    this.name = name;
    this.machineId = machineId;
    this.created = created;
    this.updated = updated;
    this.metadata = metadata;
  }

  /** Decodes a single {@code {id, type, attributes}} component resource node. */
  public static Component fromResourceNode(JsonNode resource) {
    if (resource == null || resource.isNull()) {
      return null;
    }
    JsonNode attrs = resource.path("attributes");
    return new Component(
        WireNodes.text(resource, "id"),
        WireNodes.text(attrs, "fingerprint"),
        WireNodes.text(attrs, "name"),
        WireNodes.text(attrs, "machine_id"),
        WireNodes.instant(attrs, "created"),
        WireNodes.instant(attrs, "updated"),
        WireNodes.objectMap(attrs, "metadata"));
  }

  /** Returns the component's unique id. */
  public String id() {
    return id;
  }

  /** Returns the component's fingerprint, unique within its machine. */
  public String fingerprint() {
    return fingerprint;
  }

  /** Returns the component's display name. */
  public String name() {
    return name;
  }

  /** Returns the id of the machine this component belongs to. */
  public String machineId() {
    return machineId;
  }

  /** Returns when the component was created, or {@code null}. */
  public Instant created() {
    return created;
  }

  /** Returns when the component was last updated, or {@code null}. */
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
    if (!(other instanceof Component)) {
      return false;
    }
    Component that = (Component) other;
    return Objects.equals(id, that.id) && Objects.equals(fingerprint, that.fingerprint)
        && Objects.equals(machineId, that.machineId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, fingerprint, machineId);
  }
}
