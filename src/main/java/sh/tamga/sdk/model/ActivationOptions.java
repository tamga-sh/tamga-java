package sh.tamga.sdk.model;

/**
 * How {@code TamgaClient.activateMachine} should behave beyond the create-validate sequence
 * itself.
 *
 * <p>Today that is one decision: what to do when the fingerprint is already registered. The type
 * exists rather than a boolean parameter so the next such decision does not need a third overload.
 *
 * <p>{@link #defaults()} reproduces the behaviour of the two-argument {@code activateMachine}
 * exactly, so passing it changes nothing.
 */
public final class ActivationOptions {

  private static final ActivationOptions DEFAULTS = new ActivationOptions(false);

  private final boolean reuseTakenFingerprint;

  private ActivationOptions(boolean reuseTakenFingerprint) {
    this.reuseTakenFingerprint = reuseTakenFingerprint;
  }

  /**
   * Returns the default options: a fingerprint that is already registered raises
   * {@code 409 FINGERPRINT_TAKEN}.
   */
  public static ActivationOptions defaults() {
    return DEFAULTS;
  }

  /**
   * Returns a copy that makes re-activating an already-registered fingerprint <b>succeed</b>,
   * returning the existing machine, instead of raising {@code 409 FINGERPRINT_TAKEN}.
   *
   * <p>This is what a machine wants on its second launch. The fingerprint is stable by
   * construction, so an application that activates at startup hits the conflict on every run after
   * the first, and the raw 409 leaves it holding an error where it wanted a machine id. Turning
   * this on makes activation idempotent for that case: the existing row is looked up and validated
   * exactly as a freshly created one would be.
   *
   * <p><b>The existing machine is never deleted.</b> If validation then reports an over-limit
   * verdict, the row stays where it is -- it predates this call and the seat it holds is not this
   * activation's to give back. That differs from the create path, where the machine this call just
   * made is rolled back.
   *
   * <p><b>Recovery is scoped to the license being activated against, and that is sufficient rather
   * than merely safe.</b> All three uniqueness strategies raise the conflict on a predicate that
   * includes the caller's own license rows -- {@code UNIQUE_PER_LICENSE} matches
   * {@code license_id} directly, {@code UNIQUE_PER_POLICY} joins licenses on the caller's own
   * {@code policy_id}, and {@code UNIQUE_PER_ACCOUNT} spans the account -- so a genuine
   * re-activation of <em>this</em> license's machine produces {@code FINGERPRINT_TAKEN} under
   * every strategy, and a license-filtered lookup finds it every time.
   *
   * <p>What a wider search would add is only the cross-license case, and that is the case the
   * server is refusing on purpose: registering one fingerprint against several licenses is how a
   * customer shares seats, which is exactly what the wider scopes exist to prevent. Returning
   * another license's machine would leave the client pinging and checking out a machine its
   * license does not own while its own machine count stayed at zero -- and since the resource
   * carries no license id, nothing on the client side could detect that. So a fingerprint taken
   * under a different license finds nothing here and the original 409 is raised unchanged.
   */
  public ActivationOptions reuseTakenFingerprint(boolean value) {
    return value == reuseTakenFingerprint ? this : new ActivationOptions(value);
  }

  /** Returns whether an already-registered fingerprint should resolve to the existing machine. */
  public boolean reusesTakenFingerprint() {
    return reuseTakenFingerprint;
  }
}
