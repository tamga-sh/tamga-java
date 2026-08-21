package sh.tamga.sdk.error;

import sh.tamga.sdk.model.ValidationMeta;

/**
 * Thrown by {@code TamgaClient.activateMachine} when the license turned out to be over a policy
 * limit. <b>No machine row survives either way</b> -- see {@link #rolledBack()} for which of the
 * two paths produced it.
 *
 * <p>There are two, because the server enforces limits in two places:
 *
 * <ul>
 *   <li><b>At creation.</b> {@code POST /machines} checks the machine, core, memory and disk
 *       limits and rejects with {@code 422 MACHINE_LIMIT_EXCEEDED} and friends. Nothing was
 *       created, so nothing is rolled back and {@link #rolledBack()} is {@code false}. The
 *       rejected {@code TamgaApiException} is carried as the cause.
 *   <li><b>At validation.</b> The create-time check runs through the policy's overage strategy, so
 *       under {@code ALLOW_ACCESS} or {@code ALLOW_1_25X_OVERAGE} creation still succeeds and the
 *       limit only appears in the validate verdict. The machine is deleted before this is thrown
 *       and {@link #rolledBack()} is {@code true}.
 * </ul>
 *
 * <p>Either way {@link #validationMeta()} names the limit, using the validation vocabulary
 * ({@code TOO_MANY_MACHINES}, {@code TOO_MANY_CORES}, {@code TOO_MUCH_MEMORY},
 * {@code TOO_MUCH_DISK}) so a caller does not have to handle two sets of names for one condition.
 */
public final class TamgaMachineOverLimitException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient ValidationMeta validationMeta;
  private final boolean rolledBack;

  /**
   * Creates the exception for the validate-time path: the machine was created and has already been
   * deleted.
   */
  public TamgaMachineOverLimitException(ValidationMeta validationMeta) {
    this(validationMeta, true, null);
  }

  /**
   * Creates the exception, stating whether a machine was created and rolled back.
   *
   * @param validationMeta the verdict, whose code identifies the limit
   * @param rolledBack {@code true} when a machine row was created and then deleted, {@code false}
   *     when the server refused to create one at all
   * @param cause the underlying API error for the create-time path, or {@code null}
   */
  public TamgaMachineOverLimitException(ValidationMeta validationMeta, boolean rolledBack,
      Throwable cause) {
    super(message(validationMeta, rolledBack), cause);
    this.validationMeta = validationMeta;
    this.rolledBack = rolledBack;
  }

  private static String message(ValidationMeta validationMeta, boolean rolledBack) {
    String code = validationMeta == null ? "unknown" : String.valueOf(validationMeta.code());
    return (rolledBack ? "Machine activation rolled back: over policy limit ("
        : "Machine activation refused at creation: over policy limit (") + code + ")";
  }

  /** Returns the validation meta, whose code identifies which limit was exceeded. */
  public ValidationMeta validationMeta() {
    return validationMeta;
  }

  /**
   * Returns whether a machine row was created and then deleted ({@code true}), or the server
   * refused to create one in the first place ({@code false}).
   *
   * <p>Only relevant for diagnostics: no machine exists in either case.
   */
  public boolean rolledBack() {
    return rolledBack;
  }
}
