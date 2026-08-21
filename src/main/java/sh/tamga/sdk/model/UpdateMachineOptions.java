package sh.tamga.sdk.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Attributes to change on an existing machine. Everything is optional; anything left unset is
 * omitted from the request and keeps its stored value.
 *
 * <p><b>Omission and an explicit null mean the same thing to the server</b>, which merges with
 * {@code COALESCE}. That makes a partial update natural but leaves no way to clear a column
 * through this route: a machine that once reported a hostname keeps reporting it until some later
 * update supplies a different one. This type therefore omits unset fields rather than sending
 * nulls, since the two are indistinguishable server-side and the shorter body is the honest one.
 *
 * <p>{@code fingerprint} is deliberately not here. It is the machine's identity and the column
 * uniqueness is enforced on, and the server does not accept it on an update -- a machine with a
 * new fingerprint is a new machine. The license, policy, owner and group relationships are
 * likewise fixed at creation as far as this endpoint is concerned.
 *
 * <p><b>{@code memory} and {@code disk} are megabytes, not bytes</b>, the same as on
 * {@link CreateMachineOptions}. Sending bytes inflates the license's running total by a factor of
 * about a million and makes the next activation on that license fail on a memory limit.
 */
public final class UpdateMachineOptions {

  private final String name;
  private final String ip;
  private final String hostname;
  private final String platform;
  private final Integer cores;
  private final Long memory;
  private final Long disk;
  private final Map<String, Object> metadata;

  @SuppressWarnings("checkstyle:ParameterNumber")
  private UpdateMachineOptions(String name, String ip, String hostname, String platform,
      Integer cores, Long memory, Long disk, Map<String, Object> metadata) {
    this.name = name;
    this.ip = ip;
    this.hostname = hostname;
    this.platform = platform;
    this.cores = cores;
    this.memory = memory;
    this.disk = disk;
    this.metadata = metadata;
  }

  /**
   * Returns options that change nothing.
   *
   * <p>Sending them is a valid request that returns the machine unchanged, which makes it a
   * cheap read-back after an update -- though {@code TamgaClient.getMachine} says so more
   * plainly.
   */
  public static UpdateMachineOptions none() {
    return new UpdateMachineOptions(null, null, null, null, null, null, null, null);
  }

  /** Returns a copy setting the display name. */
  public UpdateMachineOptions withName(String value) {
    return new UpdateMachineOptions(value, ip, hostname, platform, cores, memory, disk, metadata);
  }

  /** Returns a copy setting the reported IP address. */
  public UpdateMachineOptions withIp(String value) {
    return new UpdateMachineOptions(name, value, hostname, platform, cores, memory, disk, metadata);
  }

  /** Returns a copy setting the hostname. */
  public UpdateMachineOptions withHostname(String value) {
    return new UpdateMachineOptions(name, ip, value, platform, cores, memory, disk, metadata);
  }

  /** Returns a copy setting the platform identifier. */
  public UpdateMachineOptions withPlatform(String value) {
    return new UpdateMachineOptions(name, ip, hostname, value, cores, memory, disk, metadata);
  }

  /** Returns a copy setting the reported core count, which policy limits are checked against. */
  public UpdateMachineOptions withCores(Integer value) {
    return new UpdateMachineOptions(name, ip, hostname, platform, value, memory, disk, metadata);
  }

  /** Returns a copy setting memory in <b>megabytes</b>. Not bytes -- see this class's note. */
  public UpdateMachineOptions withMemory(Long value) {
    return new UpdateMachineOptions(name, ip, hostname, platform, cores, value, disk, metadata);
  }

  /** Returns a copy setting disk in <b>megabytes</b>. Not bytes -- see this class's note. */
  public UpdateMachineOptions withDisk(Long value) {
    return new UpdateMachineOptions(name, ip, hostname, platform, cores, memory, value, metadata);
  }

  /**
   * Returns a copy replacing the machine's metadata.
   *
   * <p>A whole-object replacement, not a merge of individual keys: the server stores the object it
   * is given. Read the current metadata first if the intent is to add one key.
   */
  public UpdateMachineOptions withMetadata(Map<String, Object> value) {
    return new UpdateMachineOptions(name, ip, hostname, platform, cores, memory, disk,
        value == null ? null : new LinkedHashMap<>(value));
  }

  /**
   * Renders the JSON:API request body.
   *
   * <p>Enveloped, like {@link CreateMachineOptions#toRequestBody()} and unlike the flat component
   * and process creates. The {@code type} member is required by the server's decoder even though
   * the handler then ignores its value.
   */
  public Map<String, Object> toRequestBody() {
    Map<String, Object> attributes = new LinkedHashMap<>();
    putIfSet(attributes, "name", name);
    putIfSet(attributes, "ip", ip);
    putIfSet(attributes, "hostname", hostname);
    putIfSet(attributes, "platform", platform);
    putIfSet(attributes, "cores", cores);
    putIfSet(attributes, "memory", memory);
    putIfSet(attributes, "disk", disk);
    putIfSet(attributes, "metadata", metadata);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("type", "machines");
    data.put("attributes", attributes);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("data", data);
    return body;
  }

  private static void putIfSet(Map<String, Object> target, String key, Object value) {
    if (value != null) {
      target.put(key, value);
    }
  }
}
