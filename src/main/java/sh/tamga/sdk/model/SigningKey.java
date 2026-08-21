package sh.tamga.sdk.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Objects;

/**
 * One public signing key an account has held -- the {@code signing-keys} resource served by
 * {@code GET /v1/accounts/{account_id}/signing-keys}, current and retired keys alike.
 *
 * <p>Retired keys are published deliberately, and that is the whole point of the route: an offline
 * {@code .lic} or {@code .machine} file names the key that signed it in its {@code kid} claim, and
 * a client holding a file issued before the account's last rotation needs that key. Its only other
 * options are to fail verification on an authentic file or to accept any key, and the second
 * defeats signing entirely.
 *
 * <p>Two things about this resource are easy to get wrong.
 *
 * <p><b>The resource {@code id} IS the {@code kid}.</b> It is not a UUID like every other resource
 * this SDK decodes: the server sets {@code id: k.kid} ({@code accounts/serializer.rs:123}),
 * the same value it writes into the file's claim, with its own comment reading "The {@code kid}
 * doubles as the resource id -- it is what an offline file names." So matching a file to its key
 * needs no local hashing at all on this path. {@link sh.tamga.sdk.crypto.Ed25519#keyId(String)}
 * exists for the other direction -- a key pinned in an application binary, with no API call in
 * sight -- and as a cross-check against what the server published.
 *
 * <p><b>{@code publicKey} is camelCase inside an otherwise snake_case attribute bag.</b> The
 * server's {@code SigningKeyAttributes} carries no {@code rename_all}; the single field rename on
 * {@code public_key} ({@code accounts/serializer.rs:111-112}) is the only exception, and {@code
 * algorithm}, {@code status}, {@code created} and {@code retired} are all bare. Reading {@code
 * public_key} here yields {@code null} and nothing else complains -- the same trap the {@link
 * Release} resource sets from the opposite direction.
 *
 * <p><b>Ed25519 only, today.</b> The table's {@code CHECK} also admits {@code rsa2048} and {@code
 * ecdsa_p256}, but {@code rotate_ed25519} is the only code path that writes a row and it hardcodes
 * {@code 'ed25519'}. The account's RSA and ECDSA signing keys are neither published here nor
 * rotated at all -- and a {@code .machine} file signed under one of those schemes still carries a
 * {@code kid} computed from the account's <em>Ed25519</em> public key, because both checkout
 * handlers derive the claim from that column whatever scheme actually signed the bytes. Treat
 * {@code kid} as meaningful for Ed25519-signed files only.
 *
 * <p><b>An empty listing is normal, not a failure.</b> {@code account_signing_keys} is written
 * only by {@code rotate_ed25519}, which backfills the current key on its way through, so an
 * account that has never rotated has no rows at all. Pin the account's published key with {@link
 * sh.tamga.sdk.checkout.SigningKeySet#ofPublicKeys} rather than treating that as an error.
 */
public final class SigningKey {

  /** The {@code algorithm} value the server writes for every key it publishes today. */
  public static final String ED25519_ALGORITHM = "ed25519";

  /** Wire {@code status} of the key currently signing new files: at most one per algorithm. */
  public static final String ACTIVE_STATUS = "active";

  /** Wire {@code status} of a key kept for verification only. */
  public static final String RETIRED_STATUS = "retired";

  private final String keyId;
  private final String algorithm;
  private final String publicKey;
  private final String status;
  private final Instant created;
  private final Instant retired;

  private SigningKey(String keyId, String algorithm, String publicKey, String status,
      Instant created, Instant retired) {
    this.keyId = keyId;
    this.algorithm = algorithm;
    this.publicKey = publicKey;
    this.status = status;
    this.created = created;
    this.retired = retired;
  }

  /**
   * Decodes a single {@code {id, type, attributes}} signing-key resource node.
   *
   * <p>The {@code id} is read as the {@code kid} -- see the type-level remarks -- and {@code
   * publicKey} is read under exactly that spelling.
   */
  public static SigningKey fromResourceNode(JsonNode resource) {
    if (resource == null || resource.isNull()) {
      return null;
    }
    JsonNode attrs = resource.path("attributes");
    return new SigningKey(
        WireNodes.text(resource, "id"),
        WireNodes.text(attrs, "algorithm"),
        WireNodes.text(attrs, "publicKey"),
        WireNodes.text(attrs, "status"),
        WireNodes.instant(attrs, "created"),
        WireNodes.instant(attrs, "retired"));
  }

  /**
   * Builds an Ed25519 key record from an id and a public key the caller already holds -- a key
   * pinned in the application binary, loaded from a bundled file, or fetched by a build step.
   *
   * <p>That path matters more than it looks: the signing-keys route requires the {@code
   * account.read} permission, which a license-key credential does not hold, so an embedded client
   * cannot fetch the set at all. An offline verifier that only works while it has a network is not
   * offline. {@link sh.tamga.sdk.checkout.SigningKeySet#ofPublicKeys} derives the id for you and
   * is the usual entry point; this one is here for a caller who has both halves already.
   *
   * @param keyId the key's id, as {@link sh.tamga.sdk.crypto.Ed25519#keyId(String)} computes it
   *     and as an offline file's {@code kid} claim names it.
   * @param publicKey the public key exactly as the server publishes it: standard base64 of the raw
   *     32 bytes. Do not re-encode, trim or convert it to PEM -- the id is a hash of this string.
   */
  public static SigningKey ed25519(String keyId, String publicKey) {
    return new SigningKey(keyId, ED25519_ALGORITHM, publicKey, ACTIVE_STATUS, null, null);
  }

  /**
   * The key's id: the JSON:API resource {@code id}, and the value an offline file's {@code kid}
   * claim names.
   */
  public String keyId() {
    return keyId;
  }

  /** The signing algorithm -- {@code "ed25519"} on every row the server publishes today. */
  public String algorithm() {
    return algorithm;
  }

  /**
   * The public half, standard base64 of the raw key bytes, exactly as published.
   *
   * <p>Kept as the published string rather than decoded bytes because the string is what {@link
   * #keyId()} hashes: normalising it changes the hash and breaks the match.
   */
  public String publicKey() {
    return publicKey;
  }

  /** {@code "active"} or {@code "retired"}, or {@code null} when the server sent neither. */
  public String status() {
    return status;
  }

  /** When the key was created, or {@code null}. */
  public Instant created() {
    return created;
  }

  /**
   * When the key was retired, or {@code null} while it is still active.
   *
   * <p>Absent rather than null on the wire for an active key -- the server skips the field
   * entirely ({@code skip_serializing_if = "Option::is_none"}), so the two are indistinguishable
   * here and both read as {@code null}.
   */
  public Instant retired() {
    return retired;
  }

  /**
   * Whether this key is retired: verification only, no longer signing.
   *
   * <p>A file that verifies only under a retired key is authentic and was issued before the
   * account's last rotation. Nothing is wrong with it, but whatever hands these out is due a fresh
   * checkout.
   */
  public boolean isRetired() {
    return RETIRED_STATUS.equals(status);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof SigningKey)) {
      return false;
    }
    SigningKey that = (SigningKey) other;
    return Objects.equals(keyId, that.keyId) && Objects.equals(publicKey, that.publicKey)
        && Objects.equals(algorithm, that.algorithm) && Objects.equals(status, that.status);
  }

  @Override
  public int hashCode() {
    return Objects.hash(keyId, publicKey, algorithm, status);
  }
}
