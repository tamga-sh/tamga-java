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
 * A license resource, flattened from the JSON:API {@code data.id} + {@code data.attributes} shape
 * for ergonomic use.
 *
 * <p>The same type serves two paths: the subset embedded in a checked-out {@code .lic} file
 * ({@code sh.tamga.sdk.checkout.LicenseFile}) and the full resource returned by the validation and
 * check-in endpoints. A field the current path does not carry is simply {@code null} or zero --
 * an offline file, for instance, carries no {@code status} or {@code machines_count}.
 *
 * <p>Relationship ids (product/policy/user/environment) are not modeled: this resource carries no
 * {@code relationships} object server-side.
 *
 * <p>Plain nullable accessors, not {@code Optional}-wrapped -- reserved for fields where the wire
 * genuinely distinguishes absent from null, which a straightforward Jackson binding onto this
 * minimal offline-decode model does not do.
 */
public final class License {

  private final String id;
  private final String key;
  private final boolean suspended;
  private final Instant expiry;
  private final int uses;
  private final Instant lastValidatedAt;
  private final Instant lastCheckInAt;
  private final Map<String, Object> metadata;
  private final String name;
  private final String status;
  private final String scheme;
  private final Integer maxMachines;
  private final Integer maxUsers;
  private final Integer maxUses;
  private final int machinesCount;
  private final Instant lastCheckOutAt;
  private final Instant created;
  private final Instant updated;
  private final boolean protectedLicense;
  private final boolean floating;
  private final boolean strict;
  private final boolean encrypted;

  License(String id, String key, boolean suspended, Instant expiry, int uses,
      Instant lastValidatedAt, Instant lastCheckInAt, Map<String, Object> metadata) {
    this(id, key, suspended, expiry, uses, lastValidatedAt, lastCheckInAt, metadata, null, null,
        null, null, null, null, 0, null, null, null, false, false, false, false);
  }

  @SuppressWarnings("checkstyle:ParameterNumber")
  License(String id, String key, boolean suspended, Instant expiry, int uses,
      Instant lastValidatedAt, Instant lastCheckInAt, Map<String, Object> metadata, String name,
      String status, String scheme, Integer maxMachines, Integer maxUsers, Integer maxUses,
      int machinesCount, Instant lastCheckOutAt, Instant created, Instant updated,
      boolean protectedLicense, boolean floating, boolean strict, boolean encrypted) {
    this.id = id;
    this.key = key;
    this.suspended = suspended;
    this.expiry = expiry;
    this.uses = uses;
    this.lastValidatedAt = lastValidatedAt;
    this.lastCheckInAt = lastCheckInAt;
    this.metadata = metadata;
    this.name = name;
    this.status = status;
    this.scheme = scheme;
    this.maxMachines = maxMachines;
    this.maxUsers = maxUsers;
    this.maxUses = maxUses;
    this.machinesCount = machinesCount;
    this.lastCheckOutAt = lastCheckOutAt;
    this.created = created;
    this.updated = updated;
    this.protectedLicense = protectedLicense;
    this.floating = floating;
    this.strict = strict;
    this.encrypted = encrypted;
  }

  /**
   * Decodes a single {@code {id, type, attributes}} license resource node, as returned by the
   * validation and check-in endpoints. Returns {@code null} for a null or absent node.
   */
  public static License fromResourceNode(JsonNode resource) {
    if (resource == null || resource.isNull()) {
      return null;
    }
    JsonNode attrs = resource.path("attributes");
    return new License(
        WireNodes.text(resource, "id"),
        WireNodes.text(attrs, "key"),
        WireNodes.bool(attrs, "suspended"),
        WireNodes.instant(attrs, "expiry"),
        WireNodes.intOrZero(attrs, "uses"),
        WireNodes.instant(attrs, "last_validated_at"),
        WireNodes.instant(attrs, "last_check_in_at"),
        WireNodes.objectMap(attrs, "metadata"),
        WireNodes.text(attrs, "name"),
        WireNodes.text(attrs, "status"),
        WireNodes.text(attrs, "scheme"),
        WireNodes.integer(attrs, "max_machines"),
        WireNodes.integer(attrs, "max_users"),
        WireNodes.integer(attrs, "max_uses"),
        WireNodes.intOrZero(attrs, "machines_count"),
        WireNodes.instant(attrs, "last_check_out_at"),
        WireNodes.instant(attrs, "created"),
        WireNodes.instant(attrs, "updated"),
        WireNodes.bool(attrs, "protected"),
        WireNodes.bool(attrs, "floating"),
        WireNodes.bool(attrs, "strict"),
        WireNodes.bool(attrs, "encrypted"));
  }

  /** Returns the license's display name, or {@code null}. */
  public String name() {
    return name;
  }

  /** Returns the license status string, or {@code null} when decoded from an offline file. */
  public String status() {
    return status;
  }

  /** Returns the key/checkout signing scheme as a raw wire string, or {@code null}. */
  public String scheme() {
    return scheme;
  }

  /** Returns the machine limit carried on the license, or {@code null}. */
  public Integer maxMachines() {
    return maxMachines;
  }

  /** Returns the user limit carried on the license, or {@code null}. */
  public Integer maxUsers() {
    return maxUsers;
  }

  /** Returns the use limit carried on the license, or {@code null}. */
  public Integer maxUses() {
    return maxUses;
  }

  /** Returns how many machines are currently registered against this license. */
  public int machinesCount() {
    return machinesCount;
  }

  /** Returns when the license was last checked out, or {@code null}. */
  public Instant lastCheckOutAt() {
    return lastCheckOutAt;
  }

  /** Returns when the license was created, or {@code null}. */
  public Instant created() {
    return created;
  }

  /** Returns when the license was last updated, or {@code null}. */
  public Instant updated() {
    return updated;
  }

  /** Returns whether the license is protected. */
  public boolean isProtected() {
    return protectedLicense;
  }

  /** Returns whether the license is floating. */
  public boolean floating() {
    return floating;
  }

  /** Returns whether the license is strict. */
  public boolean strict() {
    return strict;
  }

  /** Returns whether checkout files for this license are encrypted. */
  public boolean encrypted() {
    return encrypted;
  }

  /** Returns the license's unique identifier. */
  public String id() {
    return id;
  }

  /** Returns the license key string, or {@code null} if none. */
  public String key() {
    return key;
  }

  /** Returns whether the license has been manually suspended. */
  public boolean suspended() {
    return suspended;
  }

  /** Returns the license's expiration timestamp, or {@code null} if none. */
  public Instant expiry() {
    return expiry;
  }

  /** Returns the number of times the license has been used. */
  public int uses() {
    return uses;
  }

  /** Returns the timestamp of the license's last successful validation, or {@code null}. */
  public Instant lastValidatedAt() {
    return lastValidatedAt;
  }

  /** Returns the timestamp of the license's last check-in, or {@code null}. */
  public Instant lastCheckInAt() {
    return lastCheckInAt;
  }

  /**
   * Returns an unmodifiable view of arbitrary key/value metadata attached to the license, or
   * {@code null}.
   */
  public Map<String, Object> metadata() {
    return metadata == null ? null : Collections.unmodifiableMap(metadata);
  }

  /**
   * Parses a {@code {"data": {...}}} JSON:API license-resource payload -- shared by {@code
   * TamgaClient}'s (future) response mapping and {@code Checkout.LicenseFile}'s embedded-payload
   * parsing, so both paths produce an identically-shaped result.
   *
   * @throws IOException if the payload is not valid JSON matching the expected resource shape.
   */
  public static License parseResourcePayload(byte[] json) throws IOException {
    JsonApiPayload<Attributes> payload =
        TamgaJsonMapper.instance().readValue(json, new TypeReference<JsonApiPayload<Attributes>>() {
        });
    // SECURITY regression (found by independent review): a literal JSON `null` payload
    // deserializes to a null `payload` itself, and a payload with a missing or explicit-null
    // "data" field deserializes to a null `payload.data()` -- both previously reached
    // `resource.attributes()` unguarded, throwing an uncaught NullPointerException instead of the
    // documented IOException (which callers such as LicenseFile.verifyAndDecrypt already convert
    // to a TamgaCheckoutException.OfflineFileFormatException).
    if (payload == null) {
      throw new IOException("License resource payload is empty.");
    }
    return fromResource(payload.data());
  }

  /**
   * As {@link #parseResourcePayload(byte[])}, also returning the claims that were signed alongside
   * the resource.
   *
   * @throws IOException if the payload is malformed, or carries no {@code meta} claims -- i.e. it
   *     is a pre-v2 file. That is the second line of defence behind the {@code alg} gate: a file
   *     must not reach the expiry check with nothing to check.
   */
  public static LicenseWithClaims parseResourcePayloadWithClaims(byte[] json) throws IOException {
    JsonApiPayload<Attributes> payload =
        TamgaJsonMapper.instance().readValue(json, new TypeReference<JsonApiPayload<Attributes>>() {
        });
    if (payload == null) {
      throw new IOException("License resource payload is empty.");
    }
    if (payload.meta() == null) {
      throw new IOException(
          "License file payload is missing the signed 'meta' claims (this looks like a pre-v2"
              + " file).");
    }
    return new LicenseWithClaims(fromResource(payload.data()), payload.meta());
  }

  /** A license plus the claims that were covered by its file's signature. */
  public static final class LicenseWithClaims {
    private final License license;
    private final LicenseFileClaims claims;

    LicenseWithClaims(License license, LicenseFileClaims claims) {
      this.license = license;
      this.claims = claims;
    }

    /** The license the file describes. */
    public License license() {
      return license;
    }

    /** The signed {@code iat}/{@code exp}/{@code jti}/{@code kid}. */
    public LicenseFileClaims claims() {
      return claims;
    }
  }

  private static License fromResource(JsonApiResource<Attributes> resource) throws IOException {
    if (resource == null) {
      throw new IOException("License resource payload is missing its data object.");
    }
    Attributes attrs = resource.attributes();
    if (attrs == null) {
      return new License(resource.id(), null, false, null, 0, null, null, null);
    }
    return new License(resource.id(), attrs.key, attrs.suspended, attrs.expiry, attrs.uses,
        attrs.lastValidatedAt, attrs.lastCheckInAt, attrs.metadata);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof License)) {
      return false;
    }
    License license = (License) other;
    return suspended == license.suspended
        && uses == license.uses
        && Objects.equals(id, license.id)
        && Objects.equals(key, license.key)
        && Objects.equals(expiry, license.expiry)
        && Objects.equals(lastValidatedAt, license.lastValidatedAt)
        && Objects.equals(lastCheckInAt, license.lastCheckInAt)
        && Objects.equals(metadata, license.metadata);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, key, suspended, expiry, uses, lastValidatedAt, lastCheckInAt, metadata);
  }

  /** The JSON:API {@code attributes} bag for a license resource. */
  private static final class Attributes {
    private final String key;
    private final boolean suspended;
    private final Instant expiry;
    private final int uses;
    private final Instant lastValidatedAt;
    private final Instant lastCheckInAt;
    private final Map<String, Object> metadata;

    @JsonCreator
    Attributes(@JsonProperty("key") String key, @JsonProperty("suspended") boolean suspended,
        @JsonProperty("expiry") Instant expiry, @JsonProperty("uses") int uses,
        @JsonProperty("last_validated_at") Instant lastValidatedAt,
        @JsonProperty("last_check_in_at") Instant lastCheckInAt,
        @JsonProperty("metadata") Map<String, Object> metadata) {
      this.key = key;
      this.suspended = suspended;
      this.expiry = expiry;
      this.uses = uses;
      this.lastValidatedAt = lastValidatedAt;
      this.lastCheckInAt = lastCheckInAt;
      this.metadata = metadata;
    }
  }
}
