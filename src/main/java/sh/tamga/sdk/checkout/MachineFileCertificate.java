package sh.tamga.sdk.checkout;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The inner {@code {enc, sig, alg}} JSON structure carried inside a {@code .machine} file's PEM
 * envelope -- same shape as {@link LicenseFileCertificate}.
 */
final class MachineFileCertificate {

  /**
   * The payload: base64-encoded AES-256-GCM ciphertext (encrypted files) or plain base64-encoded
   * JSON (unencrypted files).
   */
  final String enc;

  /** The signature over {@link #enc}'s base64 string bytes, base64-encoded. */
  final String sig;

  /**
   * The algorithm identifier reported by the server (e.g. contains {@code "aes-256-gcm"} and/or
   * a signature-scheme suffix like {@code "rsa-sha256"}). NEVER used to select the verifier --
   * see {@link MachineFile}'s type-level remarks.
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
