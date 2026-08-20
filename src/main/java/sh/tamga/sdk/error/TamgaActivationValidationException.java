package sh.tamga.sdk.error;

import sh.tamga.sdk.model.Machine;

/**
 * Thrown by {@code TamgaClient.activateMachine} when the machine was created but the validation
 * call that follows it failed -- a network error or an unrelated server fault, not a verdict about
 * the license.
 *
 * <p><b>The machine still exists.</b> Whether it is permitted is unknown, so deleting it would
 * destroy a seat on the strength of a transient error. It is carried on this exception instead:
 * retry the validation, or delete it with {@code deleteMachine}.
 *
 * <p>This mirrors {@code tamga-go}, which returns the created machine alongside the error rather
 * than rolling back. An earlier revision of this SDK rolled back here, on the reasoning that
 * throwing left no way to hand the machine back -- which was simply wrong, since an exception
 * carries whatever it is given.
 */
public final class TamgaActivationValidationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient Machine machine;

  /** Creates the exception carrying the machine that was created, and the failure that followed. */
  public TamgaActivationValidationException(Machine machine, Throwable cause) {
    super("Machine " + (machine == null ? "<unknown>" : machine.id())
        + " was created, but validating the license failed. The machine still exists.", cause);
    this.machine = machine;
  }

  /** Returns the machine that was created and left in place. */
  public Machine machine() {
    return machine;
  }
}
