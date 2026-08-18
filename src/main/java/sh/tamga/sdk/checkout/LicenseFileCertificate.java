package sh.tamga.sdk.checkout;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The inner {@code {enc, sig, alg}} JSON structure carried inside a {@code .lic} file's PEM
 * envelope.
 */
final class LicenseFileCertificate {

  /**
   * Base64-encoded license payload -- either AES-256-GCM ciphertext (encrypted license) or plain
   * JSON (unencrypted), depending on {@link #alg}.
   */
  final String enc;

  /**
   * Base64-encoded Ed25519 signature, computed over the ASCII/UTF-8 bytes of {@link #enc}'s
   * base64 string itself, not the decoded payload bytes.
   */
  final String sig;

  /**
   * Algorithm identifier -- exactly {@code "base64+ed25519+v2"} (plain) or {@code
   * "aes-256-gcm+ed25519+v2"} (encrypted). The {@code +v2} suffix is required: {@link
   * LicenseFile#verify} rejects anything else, which is what closes the door on pre-v2 files.
   */
  final String alg;

  @JsonCreator
  LicenseFileCertificate(@JsonProperty("enc") String enc, @JsonProperty("sig") String sig,
      @JsonProperty("alg") String alg) {
    this.enc = enc;
    this.sig = sig;
    this.alg = alg;
  }
}
