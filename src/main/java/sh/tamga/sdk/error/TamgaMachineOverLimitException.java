package sh.tamga.sdk.error;

import sh.tamga.sdk.model.ValidationMeta;

/**
 * Thrown by {@code TamgaClient.activateMachine} when validation reported an over-limit code after
 * the machine was created.
 *
 * <p><b>By the time this is thrown the machine has already been deleted.</b> Activation rolls back
 * so a rejected activation does not leave an orphaned row consuming a seat. The validation meta is
 * carried so callers can tell which limit was exceeded.
 */
public final class TamgaMachineOverLimitException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient ValidationMeta validationMeta;

  /** Creates the exception carrying the validation meta that reported the over-limit code. */
  public TamgaMachineOverLimitException(ValidationMeta validationMeta) {
    super("Machine activation rolled back: over policy limit ("
        + (validationMeta == null ? "unknown" : String.valueOf(validationMeta.code())) + ")");
    this.validationMeta = validationMeta;
  }

  /** Returns the validation meta, whose code identifies which limit was exceeded. */
  public ValidationMeta validationMeta() {
    return validationMeta;
  }
}
