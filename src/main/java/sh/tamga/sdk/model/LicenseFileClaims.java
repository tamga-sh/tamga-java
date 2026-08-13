package sh.tamga.sdk.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The claims carried <em>inside</em> the signed bytes of a {@code .lic} file.
 *
 * <p>These are the point of file format v2. In v1 the {@code ttl}/{@code expiry} a caller asked for
 * lived only in the JSON:API envelope around the certificate, never inside the signed bytes -- so a
 * 24-hour trial file was cryptographically valid forever, because the client is the attacker and
 * any check built on the envelope is bypassed by keeping (or redistributing) the raw certificate
 * string. Unlike the envelope, these cannot be edited by whoever holds the file.
 */
public final class LicenseFileClaims {

  private final long issuedAt;
  private final Long expiresAt;
  private final String id;
  private final String keyId;

  @JsonCreator
  LicenseFileClaims(
      @JsonProperty("iat") long issuedAt,
      @JsonProperty("exp") Long expiresAt,
      @JsonProperty("jti") String id,
      @JsonProperty("kid") String keyId) {
    this.issuedAt = issuedAt;
    this.expiresAt = expiresAt;
    this.id = id;
    this.keyId = keyId;
  }

  /** Issued-at, seconds since the Unix epoch. */
  public long issuedAt() {
    return issuedAt;
  }

  /**
   * Expiry, seconds since the Unix epoch, or {@code null} when the file never expires -- checkout
   * was made without a {@code ttl}.
   */
  public Long expiresAt() {
    return expiresAt;
  }

  /** Unique per checkout -- usable for replay detection. */
  public String id() {
    return id;
  }

  /** Identifies the signing key, so a file survives a key rotation. */
  public String keyId() {
    return keyId;
  }
}
