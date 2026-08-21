package sh.tamga.sdk.error;

import sh.tamga.sdk.model.ResponseMetadata;

/**
 * Thrown for every non-2xx API response.
 *
 * <p>Unchecked, matching this SDK's existing {@link TamgaCheckoutException} convention: an API
 * failure is a runtime condition, not something every call site should be forced to declare.
 *
 * <p><b>Match on {@link TamgaError#code()}, never on the message or the HTTP status.</b> The nested
 * subclasses below exist so callers can {@code catch} the common cases directly; anything without a
 * dedicated subclass arrives as a plain {@code TamgaApiException}. Use {@link #from} to build one
 * so the code-to-subclass mapping stays in a single place.
 *
 * <p>HTTP 429 does not appear among the subclasses. It is retried transparently in the transport
 * and only reaches a caller as a plain {@code TamgaApiException} with status 429 once the retry
 * budget is exhausted.
 */
public class TamgaApiException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient TamgaError error;
  private final transient ResponseMetadata responseMetadata;
  private final int httpStatus;

  /** Creates an exception carrying the decoded error, the HTTP status, and response metadata. */
  public TamgaApiException(TamgaError error, int httpStatus, ResponseMetadata responseMetadata) {
    super(buildMessage(error));
    this.error = error;
    this.httpStatus = httpStatus;
    this.responseMetadata = responseMetadata;
  }

  private static String buildMessage(TamgaError error) {
    if (error == null) {
      return TamgaError.UNKNOWN_CODE;
    }
    return error.detail() == null ? error.code() : error.code() + ": " + error.detail();
  }

  /**
   * Builds the most specific exception type for the error's {@code code}. This is the single
   * dispatch point -- do not duplicate the mapping at call sites.
   */
  public static TamgaApiException from(TamgaError error, int httpStatus,
      ResponseMetadata responseMetadata) {
    String code = error == null ? TamgaError.UNKNOWN_CODE : error.code();
    switch (code) {
      case "NOT_FOUND":
        return new NotFoundException(error, httpStatus, responseMetadata);
      case "UNAUTHORIZED":
        return new UnauthorizedException(error, httpStatus, responseMetadata);
      case "FORBIDDEN":
        return new ForbiddenException(error, httpStatus, responseMetadata);
      case "INTERNAL_SERVER_ERROR":
        return new InternalServerErrorException(error, httpStatus, responseMetadata);
      case "KEY_TAKEN":
        return new KeyTakenException(error, httpStatus, responseMetadata);
      case "FINGERPRINT_TAKEN":
        return new FingerprintTakenException(error, httpStatus, responseMetadata);
      case "PID_TAKEN":
        return new PidTakenException(error, httpStatus, responseMetadata);
      case "CHECK_IN_NOT_REQUIRED":
        return new CheckInNotRequiredException(error, httpStatus, responseMetadata);
      case "TTL_INVALID":
        return new TtlInvalidException(error, httpStatus, responseMetadata);
      case "LICENSE_NOT_ENCRYPTED":
        return new LicenseNotEncryptedException(error, httpStatus, responseMetadata);
      case "LICENSE_KEY_MISSING":
        return new LicenseKeyMissingException(error, httpStatus, responseMetadata);
      case "SCHEME_NOT_SUPPORTED":
        return new SchemeNotSupportedException(error, httpStatus, responseMetadata);
      case "DATASET_INVALID":
        return new DatasetInvalidException(error, httpStatus, responseMetadata);
      case "MACHINE_LIMIT_EXCEEDED":
        return new MachineLimitExceededException(error, httpStatus, responseMetadata);
      case "CORE_LIMIT_EXCEEDED":
        return new CoreLimitExceededException(error, httpStatus, responseMetadata);
      case "MEMORY_LIMIT_EXCEEDED":
        return new MemoryLimitExceededException(error, httpStatus, responseMetadata);
      case "DISK_LIMIT_EXCEEDED":
        return new DiskLimitExceededException(error, httpStatus, responseMetadata);
      case "TOO_MANY_PROCESSES":
        return new TooManyProcessesException(error, httpStatus, responseMetadata);
      case "LICENSE_SUSPENDED":
        return new LicenseSuspendedException(error, httpStatus, responseMetadata);
      case "LICENSE_EXPIRED":
        return new LicenseExpiredException(error, httpStatus, responseMetadata);
      case "LICENSE_NOT_ALLOWED":
        return new LicenseNotAllowedException(error, httpStatus, responseMetadata);
      default:
        return new TamgaApiException(error, httpStatus, responseMetadata);
    }
  }

  /** Returns the decoded JSON:API error object. */
  public TamgaError error() {
    return error;
  }

  /** Returns the stable error code, a shorthand for {@code error().code()}. */
  public String code() {
    return error == null ? TamgaError.UNKNOWN_CODE : error.code();
  }

  /** Returns the HTTP status the error arrived with. */
  public int httpStatus() {
    return httpStatus;
  }

  /** Returns the diagnostic response headers, notably the request id worth logging. */
  public ResponseMetadata responseMetadata() {
    return responseMetadata;
  }

  /** The requested resource does not exist. HTTP 404. */
  public static final class NotFoundException extends TamgaApiException {
    private static final long serialVersionUID = 1L;

    NotFoundException(TamgaError error, int status, ResponseMetadata metadata) {
      super(error, status, metadata);
    }
  }

  /** The request carried no valid credentials. HTTP 401. */
  public static final class UnauthorizedException extends TamgaApiException {
    private static final long serialVersionUID = 1L;

    UnauthorizedException(TamgaError error, int status, ResponseMetadata metadata) {
      super(error, status, metadata);
    }
  }

  /** The credentials were valid but not permitted to perform this action. HTTP 403. */
  public static final class ForbiddenException extends TamgaApiException {
    private static final long serialVersionUID = 1L;

    ForbiddenException(TamgaError error, int status, ResponseMetadata metadata) {
      super(error, status, metadata);
    }
  }

  /**
   * The server failed. HTTP 500. The server never leaks database detail here, so do not build
   * parsing logic expecting structured {@code detail}.
   */
  public static final class InternalServerErrorException extends TamgaApiException {
    private static final long serialVersionUID = 1L;

    InternalServerErrorException(TamgaError error, int status, ResponseMetadata metadata) {
      super(error, status, metadata);
    }
  }

  /** A license with that key already exists. HTTP 409. */
  public static final class KeyTakenException extends TamgaApiException {
    private static final long serialVersionUID = 1L;

    KeyTakenException(TamgaError error, int status, ResponseMetadata metadata) {
      super(error, status, metadata);
    }
  }

  /**
   * That fingerprint is already registered. HTTP 409. Raised both when creating a machine against
   * a license and when creating a component against a machine -- the scope differs by call site.
   */
  public static final class FingerprintTakenException extends TamgaApiException {
    private static final long serialVersionUID = 1L;

    FingerprintTakenException(TamgaError error, int status, ResponseMetadata metadata) {
      super(error, status, metadata);
    }
  }

  /** That process id is already registered for the machine. HTTP 409. */
  public static final class PidTakenException extends TamgaApiException {
    private static final long serialVersionUID = 1L;

    PidTakenException(TamgaError error, int status, ResponseMetadata metadata) {
      super(error, status, metadata);
    }
  }

  /**
   * The license does not require a check-in right now. HTTP 422. Gate the call on the policy's
   * {@code requireCheckIn} rather than calling and catching this.
   */
  public static final class CheckInNotRequiredException extends TamgaApiException {
    private static final long serialVersionUID = 1L;

    CheckInNotRequiredException(TamgaError error, int status, ResponseMetadata metadata) {
      super(error, status, metadata);
    }
  }

  /** The requested checkout time-to-live was out of range. HTTP 422. */
  public static final class TtlInvalidException extends TamgaApiException {
    private static final long serialVersionUID = 1L;

    TtlInvalidException(TamgaError error, int status, ResponseMetadata metadata) {
      super(error, status, metadata);
    }
  }

  /** An encrypted checkout was requested for a license that is not encrypted. HTTP 422. */
  public static final class LicenseNotEncryptedException extends TamgaApiException {
    private static final long serialVersionUID = 1L;

    LicenseNotEncryptedException(TamgaError error, int status, ResponseMetadata metadata) {
      super(error, status, metadata);
    }
  }

  /** The operation needed a license key that was not supplied. HTTP 422. */
  public static final class LicenseKeyMissingException extends TamgaApiException {
    private static final long serialVersionUID = 1L;

    LicenseKeyMissingException(TamgaError error, int status, ResponseMetadata metadata) {
      super(error, status, metadata);
    }
  }

  /** The license's signing scheme is not supported for this operation. HTTP 422. */
  public static final class SchemeNotSupportedException extends TamgaApiException {
    private static final long serialVersionUID = 1L;

    SchemeNotSupportedException(TamgaError error, int status, ResponseMetadata metadata) {
      super(error, status, metadata);
    }
  }

  /** The offline-proof dataset was rejected. HTTP 422. */
  public static final class DatasetInvalidException extends TamgaApiException {
    private static final long serialVersionUID = 1L;

    DatasetInvalidException(TamgaError error, int status, ResponseMetadata metadata) {
      super(error, status, metadata);
    }
  }

  /**
   * The license is already at its machine limit. HTTP 422, raised by {@code POST /machines}.
   *
   * <p>Machine creation <b>does</b> enforce the policy's limits, and it does so through the
   * policy's overage strategy: under {@code ALLOW_ACCESS} or {@code ALLOW_1_25X_OVERAGE} the
   * create still succeeds and the limit only surfaces at validate. So this is one of two ways an
   * over-limit activation is reported, not the only one --
   * {@code TamgaClient.activateMachine} normalizes both into
   * {@link TamgaMachineOverLimitException}.
   */
  public static final class MachineLimitExceededException extends TamgaApiException {
    private static final long serialVersionUID = 1L;

    MachineLimitExceededException(TamgaError error, int status, ResponseMetadata metadata) {
      super(error, status, metadata);
    }
  }

  /**
   * The license is already at its CPU-core limit. HTTP 422, raised by {@code POST /machines} when
   * the machine's reported {@code cores} would push the license past {@code policy.max_cores}.
   */
  public static final class CoreLimitExceededException extends TamgaApiException {
    private static final long serialVersionUID = 1L;

    CoreLimitExceededException(TamgaError error, int status, ResponseMetadata metadata) {
      super(error, status, metadata);
    }
  }

  /**
   * The license is already at its memory limit. HTTP 422, raised by {@code POST /machines}.
   *
   * <p>The reported {@code memory} is in <b>megabytes</b>. Reporting bytes inflates the license's
   * running total by a factor of about a million and makes this the response to the next
   * activation.
   */
  public static final class MemoryLimitExceededException extends TamgaApiException {
    private static final long serialVersionUID = 1L;

    MemoryLimitExceededException(TamgaError error, int status, ResponseMetadata metadata) {
      super(error, status, metadata);
    }
  }

  /**
   * The license is already at its disk limit. HTTP 422, raised by {@code POST /machines}. The
   * reported {@code disk} is in <b>megabytes</b> -- see {@link MemoryLimitExceededException}.
   */
  public static final class DiskLimitExceededException extends TamgaApiException {
    private static final long serialVersionUID = 1L;

    DiskLimitExceededException(TamgaError error, int status, ResponseMetadata metadata) {
      super(error, status, metadata);
    }
  }

  /**
   * The machine is already at the policy's process limit. HTTP 422, raised by
   * {@code POST /processes}.
   *
   * <p>Nothing on the server reclaims a process row: the reaper is not scheduled, so a process
   * that crashed without being deleted holds its slot indefinitely.
   */
  public static final class TooManyProcessesException extends TamgaApiException {
    private static final long serialVersionUID = 1L;

    TooManyProcessesException(TamgaError error, int status, ResponseMetadata metadata) {
      super(error, status, metadata);
    }
  }

  /**
   * The license is suspended and cannot authenticate. HTTP 401, raised at the front door for
   * license-key credentials regardless of policy.
   *
   * <p>Not retryable and not a transport problem: no number of retries un-suspends a license.
   */
  public static final class LicenseSuspendedException extends TamgaApiException {
    private static final long serialVersionUID = 1L;

    LicenseSuspendedException(TamgaError error, int status, ResponseMetadata metadata) {
      super(error, status, metadata);
    }
  }

  /**
   * The license has expired and its policy refuses to authenticate it. HTTP 401.
   *
   * <p>Only {@code REVOKE_ACCESS} produces this. Under {@code RESTRICT_ACCESS} (the default),
   * {@code MAINTAIN_ACCESS} and {@code ALLOW_ACCESS} an expired license still authenticates and
   * the expiry surfaces as the {@code EXPIRED} validation code instead -- which is the difference
   * between "you keep what you paid for" and "you are locked out".
   */
  public static final class LicenseExpiredException extends TamgaApiException {
    private static final long serialVersionUID = 1L;

    LicenseExpiredException(TamgaError error, int status, ResponseMetadata metadata) {
      super(error, status, metadata);
    }
  }

  /**
   * License-key authentication is not permitted by the license's policy. HTTP 401.
   *
   * <p><b>A configuration precondition, not a transient failure.</b> The policy's
   * {@code authentication_strategy} must be {@code LICENSE} or {@code MIXED}; the column defaults
   * to {@code TOKEN}, and {@code NONE} is refused the same way. Retrying, or asking the user to
   * re-enter their key, cannot fix it -- the policy has to be changed.
   */
  public static final class LicenseNotAllowedException extends TamgaApiException {
    private static final long serialVersionUID = 1L;

    LicenseNotAllowedException(TamgaError error, int status, ResponseMetadata metadata) {
      super(error, status, metadata);
    }
  }
}
