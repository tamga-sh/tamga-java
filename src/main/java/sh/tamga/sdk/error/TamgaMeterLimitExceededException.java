package sh.tamga.sdk.error;

/**
 * Thrown by {@code TamgaClient.incrementEntitlementUsage} when the increment would push a meter
 * entitlement's {@code current_value} past its {@code max_value}.
 *
 * <p>Mirrors {@link TamgaMachineOverLimitException}'s role of normalizing a raw wire-level error
 * into a friendlier, purpose-named exception, but the shape is simpler: machine activation has two
 * distinct over-limit paths (a create-time rejection and a later validate-time verdict) that need
 * unifying into one vocabulary, via {@code ValidationCode.fromMachineLimitErrorCode}. A meter's cap
 * has only one path -- the direct {@code 422 METER_LIMIT_EXCEEDED} from the increment action
 * itself -- so there is no second vocabulary to reconcile and no rollback semantics: nothing was
 * created or deleted, the increment simply did not apply.
 *
 * <p>{@link #entitlementId()} names which meter hit its cap, read from the server's
 * {@code meta.entitlement_id} via {@link TamgaApiException.MeterLimitExceededException}, which is
 * always this exception's {@linkplain #getCause() cause}.
 */
public final class TamgaMeterLimitExceededException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String entitlementId;

  /**
   * Creates the exception from the wire-level error it wraps.
   *
   * @param entitlementId the id of the entitlement whose cap was exceeded, or {@code null} if the
   *     server did not name one
   * @param cause the {@link TamgaApiException.MeterLimitExceededException} this normalizes
   */
  public TamgaMeterLimitExceededException(String entitlementId,
      TamgaApiException.MeterLimitExceededException cause) {
    super(message(entitlementId), cause);
    this.entitlementId = entitlementId;
  }

  private static String message(String entitlementId) {
    return "Entitlement usage rejected: over meter cap (entitlement "
        + (entitlementId == null ? "unknown" : entitlementId) + ")";
  }

  /**
   * Returns the id of the entitlement whose cap was exceeded, or {@code null} if the server did
   * not send {@code meta.entitlement_id}.
   */
  public String entitlementId() {
    return entitlementId;
  }
}
