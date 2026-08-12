package sh.tamga.sdk.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * A machine resource, flattened from the JSON:API {@code data.id} + {@code data.attributes}
 * shape, mirroring {@link License}'s flattening pattern.
 *
 * <p><b>Scope note:</b> models exactly the fields needed to decode a checked-out {@code .machine}
 * file's embedded resource ({@code sh.tamga.sdk.checkout.MachineFile}) -- the full {@code
 * TamgaClient}-facing machine-management surface (create/update/heartbeat-ping endpoints, a
 * heartbeat scheduler) is still deferred to a future session.
 */
public final class Machine {

  private final String id;
  private final String fingerprint;
  private final String name;
  private final String platform;
  private final HeartbeatStatus heartbeatStatus;
  private final Instant lastHeartbeatAt;
  private final Instant lastCheckOutAt;
  private final Map<String, Object> metadata;

  Machine(String id, String fingerprint, String name, String platform,
      HeartbeatStatus heartbeatStatus, Instant lastHeartbeatAt, Instant lastCheckOutAt,
      Map<String, Object> metadata) {
    this.id = id;
    this.fingerprint = fingerprint;
    this.name = name;
    this.platform = platform;
    this.heartbeatStatus = heartbeatStatus;
    this.lastHeartbeatAt = lastHeartbeatAt;
    this.lastCheckOutAt = lastCheckOutAt;
    this.metadata = metadata;
  }

  /** Returns the machine's unique ID. */
  public String id() {
    return id;
  }

  /** Returns the machine's fingerprint identifier, or {@code null}. */
  public String fingerprint() {
    return fingerprint;
  }

  /** Returns the machine's display name, or {@code null}. */
  public String name() {
    return name;
  }

  /** Returns the machine's platform/OS identifier, or {@code null}. */
  public String platform() {
    return platform;
  }

  /** Returns the machine's current heartbeat status. */
  public HeartbeatStatus heartbeatStatus() {
    return heartbeatStatus;
  }

  /** Returns when the machine last sent a heartbeat ping, or {@code null}. */
  public Instant lastHeartbeatAt() {
    return lastHeartbeatAt;
  }

  /**
   * Returns when the machine was last checked out (offline {@code .machine} file issued), or
   * {@code null}.
   */
  public Instant lastCheckOutAt() {
    return lastCheckOutAt;
  }

  /**
   * Returns an unmodifiable view of arbitrary caller-supplied metadata attached to the machine,
   * or {@code null}.
   */
  public Map<String, Object> metadata() {
    return metadata == null ? null : Collections.unmodifiableMap(metadata);
  }

  /**
   * Parses a {@code {"data": {...}}} JSON:API machine-resource payload -- shared by {@code
   * TamgaClient}'s (future) response mapping and {@code Checkout.MachineFile}'s embedded-payload
   * parsing.
   *
   * @throws IOException if the payload is not valid JSON matching the expected resource shape.
   */
  public static Machine parseResourcePayload(byte[] json) throws IOException {
    JsonApiPayload<Attributes> payload =
        TamgaJsonMapper.instance().readValue(json, new TypeReference<JsonApiPayload<Attributes>>() {
        });
    // SECURITY regression (found by independent review): a literal JSON `null` payload
    // deserializes to a null `payload` itself, and a payload with a missing or explicit-null
    // "data" field deserializes to a null `payload.data()` -- both previously reached
    // `resource.attributes()` unguarded, throwing an uncaught NullPointerException instead of the
    // documented IOException (which callers such as MachineFile.verifyAndDecrypt already convert
    // to a TamgaCheckoutException.OfflineFileFormatException).
    if (payload == null) {
      throw new IOException("Machine resource payload is empty.");
    }
    return fromResource(payload.data());
  }

  private static Machine fromResource(JsonApiResource<Attributes> resource) throws IOException {
    if (resource == null) {
      throw new IOException("Machine resource payload is missing its data object.");
    }
    Attributes attrs = resource.attributes();
    if (attrs == null) {
      return new Machine(resource.id(), null, null, null, HeartbeatStatus.NOT_STARTED, null, null,
          null);
    }
    HeartbeatStatus status = HeartbeatStatus.fromWireValue(attrs.heartbeatStatus);
    return new Machine(resource.id(), attrs.fingerprint, attrs.name, attrs.platform, status,
        attrs.lastHeartbeatAt, attrs.lastCheckOutAt, attrs.metadata);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof Machine)) {
      return false;
    }
    Machine machine = (Machine) other;
    return Objects.equals(id, machine.id)
        && Objects.equals(fingerprint, machine.fingerprint)
        && Objects.equals(name, machine.name)
        && Objects.equals(platform, machine.platform)
        && heartbeatStatus == machine.heartbeatStatus
        && Objects.equals(lastHeartbeatAt, machine.lastHeartbeatAt)
        && Objects.equals(lastCheckOutAt, machine.lastCheckOutAt)
        && Objects.equals(metadata, machine.metadata);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, fingerprint, name, platform, heartbeatStatus, lastHeartbeatAt,
        lastCheckOutAt, metadata);
  }

  /** The JSON:API {@code attributes} bag for a machine resource. */
  private static final class Attributes {
    private final String fingerprint;
    private final String name;
    private final String platform;
    private final String heartbeatStatus;
    private final Instant lastHeartbeatAt;
    private final Instant lastCheckOutAt;
    private final Map<String, Object> metadata;

    @JsonCreator
    Attributes(@JsonProperty("fingerprint") String fingerprint, @JsonProperty("name") String name,
        @JsonProperty("platform") String platform,
        @JsonProperty("heartbeat_status") String heartbeatStatus,
        @JsonProperty("last_heartbeat_at") Instant lastHeartbeatAt,
        @JsonProperty("last_check_out_at") Instant lastCheckOutAt,
        @JsonProperty("metadata") Map<String, Object> metadata) {
      this.fingerprint = fingerprint;
      this.name = name;
      this.platform = platform;
      this.heartbeatStatus = heartbeatStatus;
      this.lastHeartbeatAt = lastHeartbeatAt;
      this.lastCheckOutAt = lastCheckOutAt;
      this.metadata = metadata;
    }
  }
}
