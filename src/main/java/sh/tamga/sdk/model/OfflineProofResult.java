package sh.tamga.sdk.model;

/**
 * The outcome of generating a machine offline proof: the machine resource plus the proof string.
 *
 * <p>The proof is verified offline with {@code sh.tamga.sdk.proof.OfflineProof}, against the same
 * dataset that was submitted.
 */
public final class OfflineProofResult {

  private final Machine machine;
  private final String proof;

  /** Creates a result pairing the machine with its generated proof string. */
  public OfflineProofResult(Machine machine, String proof) {
    this.machine = machine;
    this.proof = proof;
  }

  /** Returns the machine the proof was generated for. */
  public Machine machine() {
    return machine;
  }

  /** Returns the proof string, which begins with the {@code v1x0.} version prefix. */
  public String proof() {
    return proof;
  }
}
