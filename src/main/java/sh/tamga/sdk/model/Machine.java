package sh.tamga.sdk.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * A machine resource, flattened from the JSON:API {@code data.id} + {@code data.attributes}
 * shape, mirroring {@link License}'s flattening pattern.
 *
 * <p>The same type serves two paths: the subset embedded in a checked-out {@code .machine} file
 * ({@code sh.tamga.sdk.checkout.MachineFile}) and the full resource returned by the machine
 * endpoints. A field the current path does not carry is simply {@code null}.
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
  private final String ip;
  private final String hostname;
  private final Integer cores;
  private final Long memory;
  private final Long disk;
  private final Instant nextHeartbeatAt;
  private final Instant created;
  private final Instant updated;

  Machine(String id, String fingerprint, String name, String platform,
      HeartbeatStatus heartbeatStatus, Instant lastHeartbeatAt, Instant lastCheckOutAt,
      Map<String, Object> metadata) {
    this(id, fingerprint, name, platform, heartbeatStatus, lastHeartbeatAt, lastCheckOutAt,
        metadata, null, null, null, null, null, null, null, null);
  }

  @SuppressWarnings("checkstyle:ParameterNumber")
  Machine(String id, String fingerprint, String name, String platform,
      HeartbeatStatus heartbeatStatus, Instant lastHeartbeatAt, Instant lastCheckOutAt,
      Map<String, Object> metadata, String ip, String hostname, Integer cores, Long memory,
      Long disk, Instant nextHeartbeatAt, Instant created, Instant updated) {
    this.id = id;
    this.fingerprint = fingerprint;
    this.name = name;
    this.platform = platform;
    this.heartbeatStatus = heartbeatStatus;
    this.lastHeartbeatAt = lastHeartbeatAt;
    this.lastCheckOutAt = lastCheckOutAt;
    this.metadata = metadata;
    this.ip = ip;
    this.hostname = hostname;
    this.cores = cores;
    this.memory = memory;
    this.disk = disk;
    this.nextHeartbeatAt = nextHeartbeatAt;
    this.created = created;
    this.updated = updated;
  }

  /**
   * Decodes a single {@code {id, type, attributes}} machine resource node, as returned by the
   * machine endpoints. Returns {@code null} for a null or absent node.
   */
  public static Machine fromResourceNode(JsonNode resource) {
    if (resource == null || resource.isNull()) {
      return null;
    }
    JsonNode attrs = resource.path("attributes");
    return new Machine(
        WireNodes.text(resource, "id"),
        WireNodes.text(attrs, "fingerprint"),
        WireNodes.text(attrs, "name"),
        WireNodes.text(attrs, "platform"),
        HeartbeatStatus.fromWireValue(WireNodes.text(attrs, "heartbeat_status")),
        WireNodes.instant(attrs, "last_heartbeat_at"),
        WireNodes.instant(attrs, "last_check_out_at"),
        WireNodes.objectMap(attrs, "metadata"),
        WireNodes.text(attrs, "ip"),
        WireNodes.text(attrs, "hostname"),
        WireNodes.integer(attrs, "cores"),
        WireNodes.longValue(attrs, "memory"),
        WireNodes.longValue(attrs, "disk"),
        WireNodes.instant(attrs, "next_heartbeat_at"),
        WireNodes.instant(attrs, "created"),
        WireNodes.instant(attrs, "updated"));
  }

  /** Returns the machine's reported IP address, or {@code null}. */
  public String ip() {
    return ip;
  }

  /** Returns the machine's hostname, or {@code null}. */
  public String hostname() {
    return hostname;
  }

  /** Returns the machine's reported core count, or {@code null}. */
  public Integer cores() {
    return cores;
  }

  /**
   * Returns the machine's reported memory in <b>megabytes</b>, or {@code null}.
   *
   * <p>Not bytes. The column is documented as megabytes server-side and feeds the license's
   * {@code machines_memory_count}, which the policy's memory limit is checked against at
   * activation.
   */
  public Long memory() {
    return memory;
  }

  /** Returns the machine's reported disk in <b>megabytes</b>, or {@code null}. Not bytes. */
  public Long disk() {
    return disk;
  }

  /**
   * Returns when the next heartbeat is expected, or {@code null}.
   *
   * <p><b>Whether this reflects the real policy window depends on which call produced the
   * machine.</b> The server derives the field from the heartbeat window joined onto the machine
   * row, and only some routes perform that join:
   *
   * <table border="1">
   *   <caption>What each route's {@code next_heartbeat_at} is measured against</caption>
   *   <tr><th>Route</th><th>Window used</th></tr>
   *   <tr><td>{@code GET /machines/{id}}, {@code GET /machines}</td>
   *       <td>the policy's {@code heartbeat_duration}</td></tr>
   *   <tr><td>{@code POST /machines/{id}/actions/check-out}</td>
   *       <td>the policy's {@code heartbeat_duration}</td></tr>
   *   <tr><td>{@code POST /machines/{id}/actions/generate-offline-proof}</td>
   *       <td>the policy's {@code heartbeat_duration}</td></tr>
   *   <tr><td>{@code POST /machines} (create/activate)</td><td>the 600-second fallback</td></tr>
   *   <tr><td>{@code POST /machines/{id}/actions/ping-heartbeat}</td>
   *       <td>the 600-second fallback</td></tr>
   *   <tr><td>{@code POST /machines/{id}/actions/reset-heartbeat}</td>
   *       <td>the 600-second fallback</td></tr>
   *   <tr><td>{@code PATCH /machines/{id}}</td><td>the 600-second fallback</td></tr>
   * </table>
   *
   * <p>Subtracting {@link #lastHeartbeatAt()} from this field recovers the effective window on the
   * first group only, and nothing on the wire says which group a given response came from. Do not
   * size a ping interval from it -- read the policy instead, through
   * {@code TamgaClient.getLicensePolicy}.
   */
  public Instant nextHeartbeatAt() {
    return nextHeartbeatAt;
  }

  /** Returns when the machine was registered, or {@code null}. */
  public Instant created() {
    return created;
  }

  /** Returns when the machine was last updated, or {@code null}. */
  public Instant updated() {
    return updated;
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

  /**
   * As {@link #parseResourcePayload(byte[])}, also returning the claims that were signed alongside
   * the resource.
   *
   * <p>Machine files carry the same {@code {"data": ..., "meta": {iat, exp, jti, kid}}} envelope
   * license files do -- the server builds both from one {@code LicenseFileClaims} struct. Earlier
   * revisions of this SDK stated machine files carried no claims; they do, and an unread {@code
   * exp} is an offline file that verifies forever.
   *
   * @throws IOException if the payload is malformed, or carries no {@code meta} claims -- i.e. it
   *     is a pre-v2 file. That is the second line of defence behind the {@code alg} gate: a file
   *     must not reach the expiry check with nothing to check.
   */
  public static MachineWithClaims parseResourcePayloadWithClaims(byte[] json) throws IOException {
    JsonApiPayload<Attributes> payload =
        TamgaJsonMapper.instance().readValue(json, new TypeReference<JsonApiPayload<Attributes>>() {
        });
    if (payload == null) {
      throw new IOException("Machine resource payload is empty.");
    }
    if (payload.meta() == null) {
      throw new IOException(
          "Machine file payload is missing the signed 'meta' claims (this looks like a pre-v2"
              + " file).");
    }
    return new MachineWithClaims(fromResource(payload.data()), payload.meta());
  }

  /** A machine plus the claims that were covered by its file's signature. */
  public static final class MachineWithClaims {
    private final Machine machine;
    private final LicenseFileClaims claims;

    MachineWithClaims(Machine machine, LicenseFileClaims claims) {
      this.machine = machine;
      this.claims = claims;
    }

    /** The machine the file describes. */
    public Machine machine() {
      return machine;
    }

    /**
     * The signed {@code iat}/{@code exp}/{@code jti}/{@code kid}.
     *
     * <p>GOTCHA: {@code kid} is derived server-side from the account's <em>Ed25519</em> public key
     * even when the file was signed with RSA or ECDSA, so it does not identify the actual signing
     * key for those schemes. Do not build key selection on it.
     */
    public LicenseFileClaims claims() {
      return claims;
    }
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
