package sh.tamga.sdk.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * A process resource -- a running process registered against a machine.
 *
 * <p><b>The process id is a {@code String} on the wire, not an integer.</b> The server types it
 * that way, and this SDK sends and accepts it as a string even though operating-system process ids
 * are numeric. A caller holding a numeric pid must stringify it explicitly at the call site rather
 * than have this SDK coerce it silently.
 *
 * <p>Unlike {@link Machine} there is deliberately no heartbeat-status field. A process's aliveness
 * is purely a function of {@link #lastHeartbeatAt()} against the hardcoded 30-second window: a
 * dead process row is deleted outright rather than tracked through a dead/resurrected state.
 *
 * <p>This type intentionally shadows {@code java.lang.Process} within this package. Callers
 * elsewhere must import it explicitly, which takes precedence over the implicit
 * {@code java.lang} import.
 */
public final class Process {

  private final String id;
  private final String pid;
  private final String machineId;
  private final Instant lastHeartbeatAt;
  private final Instant created;
  private final Instant updated;
  private final Map<String, Object> metadata;

  Process(String id, String pid, String machineId, Instant lastHeartbeatAt, Instant created,
      Instant updated, Map<String, Object> metadata) {
    this.id = id;
    this.pid = pid;
    this.machineId = machineId;
    this.lastHeartbeatAt = lastHeartbeatAt;
    this.created = created;
    this.updated = updated;
    this.metadata = metadata;
  }

  /** Decodes a single {@code {id, type, attributes}} process resource node. */
  public static Process fromResourceNode(JsonNode resource) {
    if (resource == null || resource.isNull()) {
      return null;
    }
    JsonNode attrs = resource.path("attributes");
    return new Process(
        WireNodes.text(resource, "id"),
        WireNodes.text(attrs, "pid"),
        WireNodes.text(attrs, "machine_id"),
        WireNodes.instant(attrs, "last_heartbeat_at"),
        WireNodes.instant(attrs, "created"),
        WireNodes.instant(attrs, "updated"),
        WireNodes.objectMap(attrs, "metadata"));
  }

  /** Returns the process resource's unique id. */
  public String id() {
    return id;
  }

  /** Returns the operating-system process id, as a string -- see this class's note on typing. */
  public String pid() {
    return pid;
  }

  /** Returns the id of the machine this process belongs to. */
  public String machineId() {
    return machineId;
  }

  /** Returns when this process last pinged, or {@code null}. */
  public Instant lastHeartbeatAt() {
    return lastHeartbeatAt;
  }

  /** Returns when the process was registered, or {@code null}. */
  public Instant created() {
    return created;
  }

  /** Returns when the process was last updated, or {@code null}. */
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
    if (!(other instanceof Process)) {
      return false;
    }
    Process that = (Process) other;
    return Objects.equals(id, that.id) && Objects.equals(pid, that.pid)
        && Objects.equals(machineId, that.machineId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, pid, machineId);
  }
}
