package sh.tamga.sdk.model;

/**
 * The outcome of a successful machine activation: the created machine plus the validation verdict
 * that cleared it.
 *
 * <p>An activation that failed a policy limit does not produce one of these -- it throws
 * {@code TamgaMachineOverLimitException} after rolling the machine back.
 */
public final class ActivationResult {

  private final Machine machine;
  private final ValidationMeta meta;

  /** Creates a result pairing the activated machine with its validation meta. */
  public ActivationResult(Machine machine, ValidationMeta meta) {
    this.machine = machine;
    this.meta = meta;
  }

  /** Returns the newly registered machine. */
  public Machine machine() {
    return machine;
  }

  /** Returns the validation verdict that cleared the activation. */
  public ValidationMeta meta() {
    return meta;
  }
}
