package sh.tamga.sdk.error;

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
}
