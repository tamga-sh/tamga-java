package sh.tamga.sdk.checkout;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The inner {@code {enc, sig, alg}} JSON structure carried inside a {@code .machine} file's PEM
 * envelope -- same shape as {@link LicenseFileCertificate}.
 */
final class MachineFileCertificate {

  /**
   * The payload: {@code "<nonce_b64>.<ciphertext_b64>"} for an encrypted file (two independently
   * base64-encoded halves, unlike a {@code .lic} file's single blob), or plain base64-encoded JSON
   * for an unencrypted one.
   */
  final String enc;

  /** The signature over {@link #enc}'s string bytes -- the string itself, not its decoding. */
  final String sig;

  /**
   * The algorithm identifier: {@code <encoding>+<signing-suffix>+v2}, e.g. {@code
   * "aes-256-gcm+rsa-pss-sha256+v2"}. Parsed and version-gated by {@link MachineFileAlg}, and
   * cross-checked against the caller's scheme -- but NEVER used to select the verifier, see {@link
   * MachineFile}'s type-level remarks. It is outside the signature, so it is attacker-editable.
   */
  final String alg;

  @JsonCreator
  MachineFileCertificate(@JsonProperty("enc") String enc, @JsonProperty("sig") String sig,
      @JsonProperty("alg") String alg) {
    this.enc = enc;
    this.sig = sig;
    this.alg = alg;
  }
}
