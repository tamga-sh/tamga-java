package sh.tamga.sdk.error;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Base type for failures parsing/verifying/decrypting an already-issued offline {@code
 * .lic}/{@code .machine} file or proof -- distinct from the (still-deferred) HTTP-facing {@code
 * TamgaApiException} in {@link TamgaError}, which covers live API call failures instead.
 *
 * <p>Unchecked (extends {@link RuntimeException}), matching this SDK's convention for domain
 * errors -- see {@code ecc:java-coding-standards}.
 */
public class TamgaCheckoutException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Constructs an exception with the given message. */
  public TamgaCheckoutException(String message) {
    super(message);
  }

  /** Constructs an exception with the given message and cause. */
  public TamgaCheckoutException(String message, Throwable cause) {
    super(message, cause);
  }

  /** The PEM envelope or inner JSON is malformed. */
  public static final class OfflineFileFormatException extends TamgaCheckoutException {
    private static final long serialVersionUID = 1L;

    /** Constructs an exception with the given message. */
    public OfflineFileFormatException(String message) {
      super(message);
    }

    /** Constructs an exception with the given message and cause. */
    public OfflineFileFormatException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** Signature verification failed -- the file may be forged or corrupted. */
  public static final class SignatureVerificationException extends TamgaCheckoutException {
    private static final long serialVersionUID = 1L;

    /** Constructs the exception with its fixed message. */
    public SignatureVerificationException() {
      super("Signature verification failed -- the file may be forged or corrupted.");
    }
  }

  /**
   * Decryption failed AFTER a successful signature check -- almost always the wrong license key
   * (license files) or the wrong license key/fingerprint pair (machine files), occasionally
   * payload corruption. Kept distinct from {@link SignatureVerificationException} so a caller can
   * react differently ("check your license key" vs. "this file may be forged/tampered") -- unlike
   * a network-facing oracle, there is no adversary benefit to collapsing the two for a file the
   * user already has in hand.
   */
  public static final class DecryptionException extends TamgaCheckoutException {
    private static final long serialVersionUID = 1L;

    /** Constructs an exception with the given message. */
    public DecryptionException(String message) {
      super(message);
    }
  }

  /** The certificate's {@code alg} field, or a caller-supplied scheme, is not recognized. */
  public static final class UnsupportedAlgorithmException extends TamgaCheckoutException {
    private static final long serialVersionUID = 1L;

    /** Constructs an exception with the given message. */
    public UnsupportedAlgorithmException(String message) {
      super(message);
    }
  }

  /**
   * The file's signature verified, but its signed {@code exp} claim has passed -- an authentic
   * license file that has simply run out.
   *
   * <p>Its own type on purpose: a caller that cannot tell "expired" from "forged" either warns the
   * user about tampering when their trial merely ended, or treats a forgery as a renewal prompt.
   */
  public static final class LicenseFileExpiredException extends TamgaCheckoutException {
    private static final long serialVersionUID = 1L;

    private final long expiresAt;

    /** Constructs an exception for a file that expired at {@code expiresAt} (Unix seconds). */
    public LicenseFileExpiredException(long expiresAt) {
      super("License file expired at unix timestamp " + expiresAt + ".");
      this.expiresAt = expiresAt;
    }

    /** The {@code exp} claim, seconds since the Unix epoch. */
    public long expiresAt() {
      return expiresAt;
    }
  }

  /**
   * {@code RSA_2048_JWT_RS256} (or any other scheme never implemented for a given file type) was
   * requested explicitly.
   */
  public static final class SchemeNotSupportedException extends TamgaCheckoutException {
    private static final long serialVersionUID = 1L;

    /** Constructs an exception with the given message. */
    public SchemeNotSupportedException(String message) {
      super(message);
    }
  }

  /**
   * Client-side mirror of the server's {@code 422 TTL_INVALID}: {@code ttl} must be {@code
   * > 0 && <= 31536000} (365 days).
   */
  public static final class TtlInvalidException extends TamgaCheckoutException {
    private static final long serialVersionUID = 1L;

    /** Constructs an exception with the given message. */
    public TtlInvalidException(String message) {
      super(message);
    }
  }

  /**
   * No key in the supplied set verified the file, and the file's own {@code kid} claim names a key
   * the set does not hold.
   *
   * <p><b>This is the outcome that is not a forgery</b>, and separating it from {@link
   * SignatureVerificationException} is the entire reason to verify through a key set. It is what a
   * genuine file signed before the account rotated its signing key produces against a set that has
   * not caught up: a set fetched before the rotation, an application shipped with one pinned key,
   * or a key an operator deleted outright (which is how a <em>compromised</em> key is retired, and
   * which does invalidate every legitimate file signed with it). A tampered file whose {@code kid}
   * IS in the set fails as {@link SignatureVerificationException} instead. The first calls for
   * refreshing the key set or shipping an update; the second calls for refusing the file.
   *
   * <p>Nothing about the file has been trusted at this point. The {@code kid} is read from bytes
   * whose signature has already failed against every key held, and it is used for one thing only:
   * choosing which of these two errors to report. It can never introduce a key.
   */
  public static class UnknownSigningKeyException extends TamgaCheckoutException {
    private static final long serialVersionUID = 1L;

    private final String keyId;
    private final transient List<String> availableKeyIds;

    /**
     * Constructs the exception for a file claiming {@code keyId} against a set holding {@code
     * availableKeyIds}.
     */
    public UnknownSigningKeyException(String keyId, List<String> availableKeyIds) {
      this(keyId, availableKeyIds,
          "The file names signing key '" + keyId + "', which is not in the supplied key set "
              + "(holding: " + describe(availableKeyIds) + "). This is what an authentic file "
              + "signed before a key rotation looks like -- refresh the account's signing keys "
              + "before treating it as forged.");
    }

    /** Constructs the exception with a message a subclass supplies. */
    protected UnknownSigningKeyException(String keyId, List<String> availableKeyIds,
        String message) {
      super(message);
      this.keyId = keyId;
      List<String> copy =
          availableKeyIds == null ? new ArrayList<String>() : new ArrayList<>(availableKeyIds);
      this.availableKeyIds = Collections.unmodifiableList(copy);
    }

    /** The {@code kid} the file claims, verbatim and unverified. */
    public String keyId() {
      return keyId;
    }

    /** The key ids the set did hold, in the order it holds them. Never null. */
    public List<String> availableKeyIds() {
      return availableKeyIds;
    }

    private static String describe(List<String> keyIds) {
      return keyIds == null || keyIds.isEmpty() ? "none" : String.join(", ", keyIds);
    }
  }

  /**
   * The file's {@code kid} is the one an account whose public-key column was never populated
   * produces -- {@link sh.tamga.sdk.crypto.Ed25519#UNPUBLISHED_ACCOUNT_KEY_ID}, the key id of the
   * empty string.
   *
   * <p>A distinct outcome because the remedy is distinct. An ordinary {@link
   * UnknownSigningKeyException} says the key set is stale and fetching it again may well fix
   * things. This one says the server never published a key to match against at all: both checkout
   * handlers derive the claim from {@code account.ed25519_public_key.unwrap_or_default()} while
   * signing with the private half, so the file IS signed by a real key and its {@code kid} names
   * nothing. No amount of refetching will produce a matching entry; an operator has to populate
   * the column.
   *
   * <p>A subclass of {@link UnknownSigningKeyException} rather than a sibling, so a caller who
   * only wants "not a forgery, my keys are wrong" keeps catching one type while a caller who wants
   * to tell support which of the two it is can catch this.
   *
   * <p>Note the ordering this SDK verifies in makes this rarer than it sounds: every key held is
   * tried against the signature first, so a file with this {@code kid} still verifies normally if
   * the account's real key is in the set. Reaching this error means the signature failed against
   * every key AND the file names the unpopulated-column sentinel.
   */
  public static final class SigningKeyNotPublishedException extends UnknownSigningKeyException {
    private static final long serialVersionUID = 1L;

    /** Constructs the exception for a set holding {@code availableKeyIds}. */
    public SigningKeyNotPublishedException(String keyId, List<String> availableKeyIds) {
      super(keyId, availableKeyIds,
          "The file names signing key '" + keyId + "', which is the id of the EMPTY public key -- "
              + "the account that issued it has no published Ed25519 public key, so its files "
              + "name a key that cannot exist in any key set. Refetching the signing keys will "
              + "not help; the account's public key column has to be populated server-side.");
    }
  }

  /**
   * The supplied key set holds nothing that could verify anything: it was empty, or every entry
   * was for another algorithm, or none decoded as base64 of a raw 32-byte Ed25519 key.
   *
   * <p>Its own type rather than an {@link UnknownSigningKeyException} with an empty list, because
   * the file's {@code kid} is irrelevant to it -- no file could have verified. <b>An empty
   * published set is the ordinary state of a healthy account</b>: {@code account_signing_keys} is
   * written only by {@code rotate_ed25519}, which backfills the current key on its way through, so
   * an account that has never rotated has no rows and the route answers {@code {"data": []}}. Pin
   * the account's published key instead of treating that as a server failure.
   */
  public static final class NoUsableSigningKeyException extends TamgaCheckoutException {
    private static final long serialVersionUID = 1L;

    private final transient List<String> presentKeyIds;

    /** Constructs the exception listing the ids that were present but unusable. */
    public NoUsableSigningKeyException(List<String> presentKeyIds) {
      super("The supplied signing key set holds no usable Ed25519 key"
          + (presentKeyIds == null || presentKeyIds.isEmpty() ? "; it is empty."
              : "; present but unusable: " + String.join(", ", presentKeyIds) + "."));
      List<String> copy =
          presentKeyIds == null ? new ArrayList<String>() : new ArrayList<>(presentKeyIds);
      this.presentKeyIds = Collections.unmodifiableList(copy);
    }

    /** The ids that were present in the set but could not be used. Never null. */
    public List<String> presentKeyIds() {
      return presentKeyIds;
    }
  }
}
