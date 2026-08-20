package sh.tamga.sdk.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Options for registering a process against a machine. {@code machineId} and {@code pid} are
 * required.
 *
 * <p>{@code pid} is a {@code String} because the server types it that way on the wire. A caller
 * holding a numeric process id must stringify it at the call site -- this SDK will not coerce it
 * silently, so the string-not-integer contract stays visible where it matters.
 */
public final class CreateProcessOptions {

  private final String machineId;
  private final String pid;
  private final Map<String, Object> metadata;

  private CreateProcessOptions(String machineId, String pid, Map<String, Object> metadata) {
    this.machineId = machineId;
    this.pid = pid;
    this.metadata = metadata;
  }

  /** Creates options for a process on the given machine. Both arguments are required. */
  public static CreateProcessOptions of(String machineId, String pid) {
    return new CreateProcessOptions(machineId, pid, null);
  }

  /** Returns a copy carrying arbitrary metadata. */
  public CreateProcessOptions withMetadata(Map<String, Object> value) {
    return new CreateProcessOptions(machineId, pid,
        value == null ? null : new LinkedHashMap<>(value));
  }

  /** Returns the id of the machine this process belongs to. */
  public String machineId() {
    return machineId;
  }

  /** Renders the flat, non-enveloped request body. */
  public Map<String, Object> toRequestBody() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("machine_id", machineId);
    body.put("pid", pid);
    body.put("metadata", metadata == null ? new LinkedHashMap<String, Object>() : metadata);
    return body;
  }
}
