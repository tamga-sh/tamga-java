package sh.tamga.sdk.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Options for registering a component against a machine. All three of {@code machineId},
 * {@code fingerprint} and {@code name} are required.
 */
public final class CreateComponentOptions {

  private final String machineId;
  private final String fingerprint;
  private final String name;
  private final Map<String, Object> metadata;

  private CreateComponentOptions(String machineId, String fingerprint, String name,
      Map<String, Object> metadata) {
    this.machineId = machineId;
    this.fingerprint = fingerprint;
    this.name = name;
    this.metadata = metadata;
  }

  /** Creates options for a component on the given machine. All three arguments are required. */
  public static CreateComponentOptions of(String machineId, String fingerprint, String name) {
    return new CreateComponentOptions(machineId, fingerprint, name, null);
  }

  /** Returns a copy carrying arbitrary metadata. */
  public CreateComponentOptions withMetadata(Map<String, Object> value) {
    return new CreateComponentOptions(machineId, fingerprint, name,
        value == null ? null : new LinkedHashMap<>(value));
  }

  /** Returns the id of the machine this component belongs to. */
  public String machineId() {
    return machineId;
  }

  /**
   * Renders the request body.
   *
   * <p><b>Flat, not enveloped</b> -- unlike machine creation. This asymmetry is real server
   * behaviour, not an oversight.
   */
  public Map<String, Object> toRequestBody() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("machine_id", machineId);
    body.put("fingerprint", fingerprint);
    body.put("name", name);
    body.put("metadata", metadata == null ? new LinkedHashMap<String, Object>() : metadata);
    return body;
  }
}
