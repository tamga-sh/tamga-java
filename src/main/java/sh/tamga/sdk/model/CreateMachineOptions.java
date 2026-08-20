package sh.tamga.sdk.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Options for registering a machine against a license. {@code fingerprint} and {@code licenseId}
 * are required; everything else is optional and omitted when unset.
 *
 * <p>Machine, core, memory and disk limits are <b>not</b> checked at creation time. They surface
 * only later, through validation. The machine row exists even when the license is already over its
 * limit -- see {@code TamgaClient.activateMachine}, which creates, validates, and rolls back.
 */
public final class CreateMachineOptions {

  private final String fingerprint;
  private final String licenseId;
  private final String name;
  private final String ip;
  private final String hostname;
  private final String platform;
  private final Integer cores;
  private final Long memory;
  private final Long disk;
  private final Map<String, Object> metadata;

  @SuppressWarnings("checkstyle:ParameterNumber")
  private CreateMachineOptions(String fingerprint, String licenseId, String name, String ip,
      String hostname, String platform, Integer cores, Long memory, Long disk,
      Map<String, Object> metadata) {
    this.fingerprint = fingerprint;
    this.licenseId = licenseId;
    this.name = name;
    this.ip = ip;
    this.hostname = hostname;
    this.platform = platform;
    this.cores = cores;
    this.memory = memory;
    this.disk = disk;
    this.metadata = metadata;
  }

  /** Creates options for the given machine fingerprint and owning license. Both are required. */
  public static CreateMachineOptions of(String fingerprint, String licenseId) {
    return new CreateMachineOptions(fingerprint, licenseId, null, null, null, null, null, null,
        null, null);
  }

  /** Returns a copy with a display name. */
  public CreateMachineOptions withName(String value) {
    return new CreateMachineOptions(fingerprint, licenseId, value, ip, hostname, platform, cores,
        memory, disk, metadata);
  }

  /** Returns a copy with an IP address. */
  public CreateMachineOptions withIp(String value) {
    return new CreateMachineOptions(fingerprint, licenseId, name, value, hostname, platform, cores,
        memory, disk, metadata);
  }

  /** Returns a copy with a hostname. */
  public CreateMachineOptions withHostname(String value) {
    return new CreateMachineOptions(fingerprint, licenseId, name, ip, value, platform, cores,
        memory, disk, metadata);
  }

  /** Returns a copy with a platform identifier. */
  public CreateMachineOptions withPlatform(String value) {
    return new CreateMachineOptions(fingerprint, licenseId, name, ip, hostname, value, cores,
        memory, disk, metadata);
  }

  /** Returns a copy reporting a core count, which validation checks against the policy limit. */
  public CreateMachineOptions withCores(Integer value) {
    return new CreateMachineOptions(fingerprint, licenseId, name, ip, hostname, platform, value,
        memory, disk, metadata);
  }

  /** Returns a copy reporting memory in bytes. */
  public CreateMachineOptions withMemory(Long value) {
    return new CreateMachineOptions(fingerprint, licenseId, name, ip, hostname, platform, cores,
        value, disk, metadata);
  }

  /** Returns a copy reporting disk in bytes. */
  public CreateMachineOptions withDisk(Long value) {
    return new CreateMachineOptions(fingerprint, licenseId, name, ip, hostname, platform, cores,
        memory, value, metadata);
  }

  /** Returns a copy carrying arbitrary metadata. */
  public CreateMachineOptions withMetadata(Map<String, Object> value) {
    return new CreateMachineOptions(fingerprint, licenseId, name, ip, hostname, platform, cores,
        memory, disk, value == null ? null : new LinkedHashMap<>(value));
  }

  /** Returns the machine fingerprint. */
  public String fingerprint() {
    return fingerprint;
  }

  /** Returns the id of the license this machine is registered against. */
  public String licenseId() {
    return licenseId;
  }

  /**
   * Renders the JSON:API request body.
   *
   * <p>Machine creation is the <b>only</b> create in this API that is enveloped -- components and
   * processes post flat bodies. Do not "normalize" this: the asymmetry is real server behaviour.
   *
   * <p>{@code metadata} defaults to an empty object rather than null, matching the rest of the SDK
   * fleet.
   */
  public Map<String, Object> toRequestBody() {
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("fingerprint", fingerprint);
    attributes.put("name", name);
    attributes.put("ip", ip);
    attributes.put("hostname", hostname);
    attributes.put("platform", platform);
    attributes.put("cores", cores);
    attributes.put("memory", memory);
    attributes.put("disk", disk);
    attributes.put("metadata", metadata == null ? new LinkedHashMap<String, Object>() : metadata);

    Map<String, Object> licenseIdentifier = new LinkedHashMap<>();
    licenseIdentifier.put("type", "licenses");
    licenseIdentifier.put("id", licenseId);
    Map<String, Object> licenseRelationship = new LinkedHashMap<>();
    licenseRelationship.put("data", licenseIdentifier);
    Map<String, Object> relationships = new LinkedHashMap<>();
    relationships.put("license", licenseRelationship);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("type", "machines");
    data.put("attributes", attributes);
    data.put("relationships", relationships);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("data", data);
    return body;
  }
}
