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
 * A license resource, flattened from the JSON:API {@code data.id} + {@code data.attributes} shape
 * for ergonomic use.
 *
 * <p><b>Scope note:</b> this models exactly the fields needed to decode a checked-out {@code .lic}
 * file's embedded resource ({@code sh.tamga.sdk.checkout.LicenseFile}) -- entitlement caching,
 * relationship IDs (product/policy/user/environment), and the full {@code TamgaClient}-facing
 * validate-by-key/validate-by-ID response shapes are still deferred to a future session, same as
 * before this architecture pivot.
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

  License(String id, String key, boolean suspended, Instant expiry, int uses,
      Instant lastValidatedAt, Instant lastCheckInAt, Map<String, Object> metadata) {
    this.id = id;
    this.key = key;
    this.suspended = suspended;
    this.expiry = expiry;
    this.uses = uses;
    this.lastValidatedAt = lastValidatedAt;
    this.lastCheckInAt = lastCheckInAt;
    this.metadata = metadata;
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
