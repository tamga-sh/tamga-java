package sh.tamga.sdk.checkout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import sh.tamga.sdk.crypto.Ecdsa;
import sh.tamga.sdk.crypto.Ed25519;
import sh.tamga.sdk.crypto.Hkdf;
import sh.tamga.sdk.crypto.Rsa;
import sh.tamga.sdk.error.TamgaCheckoutException;
import sh.tamga.sdk.model.LicenseScheme;
import sh.tamga.sdk.model.Machine;
import sh.tamga.sdk.model.TamgaJsonMapper;

/**
 * Parses, verifies, and decrypts an offline {@code .machine} file. Same inner {@code {enc, sig,
 * alg}} JSON shape as {@link LicenseFile}.
 *
 * <p>GOTCHA: signing scheme is taken from the license's {@code scheme} field ({@link
 * LicenseScheme}), NOT hardcoded Ed25519 like license checkout. This type's {@link #verify}
 * dispatch selects Ed25519/RSA-PKCS1/RSA-PSS/ECDSA-P256 based on a caller-supplied {@link
 * LicenseScheme} parameter -- NEVER by parsing this file's own {@code alg} string, since {@code
 * RSA_2048_PKCS1_SIGN} and {@code RSA_2048_JWT_RS256} both serialize to the same {@code
 * "rsa-sha256"} {@code alg} suffix server-side (an algorithm-confusion risk if dispatch were keyed
 * on the self-declared string instead of the caller's own trusted scheme value). An unset license
 * scheme ({@link LicenseScheme#NONE}) defaults to Ed25519, matching server behavior.
 *
 * <p>{@code RSA_2048_JWT_RS256} is explicitly rejected server-side for machine files ({@code 422
 * SCHEME_NOT_SUPPORTED}) -- this type's verifier does NOT implement or attempt JWT/RS256
 * verification for machine files; it throws
 * {@link TamgaCheckoutException.SchemeNotSupportedException} immediately rather than silently
 * no-op-ing.
 *
 * <p>Encryption key derivation is HKDF-SHA256 ({@code Hkdf}) -- NOT the naive zero-pad/truncate
 * scheme used by license checkout ({@code NaiveKey}). Decryption requires BOTH the license key AND
 * the target machine's fingerprint. GOTCHA: {@code ttl} is server-validated {@code > 0 && <=
 * 31536000} (365 days) -- the SDK's checkout call validates this client-side too, to fail fast, in
 * addition to handling the server's {@code 422 TTL_INVALID}.
 *
 * <p>RSA and ECDSA public keys are expected in X.509 {@code SubjectPublicKeyInfo} DER encoding;
 * Ed25519 public keys are raw 32-byte keys, matching {@link LicenseFile}.
 */
public final class MachineFile {

  private static final String BEGIN_MARKER = "-----BEGIN MACHINE FILE-----";
  private static final String END_MARKER = "-----END MACHINE FILE-----";

  /** The maximum {@code ttl} the server accepts for machine checkout: 365 days in seconds. */
  public static final int MAX_TTL_SECONDS = 31_536_000;

  private final MachineFileCertificate certificate;

  private MachineFile(MachineFileCertificate certificate) {
    this.certificate = certificate;
  }

  /** Parses a PEM-wrapped {@code .machine} file. Does NOT verify the signature. */
  public static MachineFile parse(String pem) {
    String inner = PemEnvelope.strip(pem, BEGIN_MARKER, END_MARKER);
    byte[] jsonBytes = Base64Codec.decodeOrThrow(inner, "Machine file body is not valid base64.");

    MachineFileCertificate certificate;
    try {
      certificate = TamgaJsonMapper.instance().readValue(jsonBytes, MachineFileCertificate.class);
    } catch (IOException e) {
      throw new TamgaCheckoutException.OfflineFileFormatException(
          "Machine file certificate JSON is malformed: " + e.getMessage(), e);
    }
    // SECURITY regression (found by independent review): a well-formed-but-incomplete
    // certificate (missing enc/sig/alg, or explicit JSON nulls for them) previously reached
    // certificate.enc.getBytes(...)/certificate.alg.contains(...) unguarded, throwing an
    // uncaught NullPointerException instead of the documented TamgaCheckoutException -- fail here
    // instead, with the right exception type, before any downstream code assumes non-null.
    if (certificate.enc == null || certificate.sig == null || certificate.alg == null) {
      throw new TamgaCheckoutException.OfflineFileFormatException(
          "Machine file certificate is missing a required field (enc, sig, or alg).");
    }
    return new MachineFile(certificate);
  }

  /**
   * Client-side validation mirroring the server's {@code 422 TTL_INVALID} check -- fails fast
   * before a checkout request is even sent.
   */
  public static void validateTtl(int ttl) {
    if (ttl <= 0 || ttl > MAX_TTL_SECONDS) {
      throw new TamgaCheckoutException.TtlInvalidException(
          "ttl must be > 0 and <= " + MAX_TTL_SECONDS + " (365 days); got " + ttl + ".");
    }
  }

  /**
   * Verifies the signature against the account's public key, dispatching by the caller-supplied
   * {@code scheme} -- NEVER by parsing this file's own {@code alg} string. See type-level remarks
   * for the algorithm-confusion rationale.
   *
   * @throws TamgaCheckoutException.SchemeNotSupportedException if {@code scheme} is {@link
   *     LicenseScheme#RSA_2048_JWT_RS256} -- never implemented for machine files.
   */
  public boolean verify(LicenseScheme scheme, byte[] publicKey) {
    byte[] signature = Base64Codec.decodeOrNull(certificate.sig);
    byte[] message = certificate.enc.getBytes(StandardCharsets.UTF_8);

    switch (scheme) {
      case RSA_2048_JWT_RS256:
        throw new TamgaCheckoutException.SchemeNotSupportedException(
            "RSA_2048_JWT_RS256 is rejected server-side for machine files (422 "
                + "SCHEME_NOT_SUPPORTED) and is not implemented client-side either -- this SDK "
                + "never attempts JWT/RS256 verification.");
      case NONE:
      case ED25519_SIGN:
        return signature != null && Ed25519.verify(publicKey, message, signature);
      case RSA_2048_PKCS1_SIGN:
        return signature != null && Rsa.verifyPkcs1(publicKey, message, signature);
      case RSA_2048_PKCS1_PSS_SIGN:
        return signature != null && Rsa.verifyPss(publicKey, message, signature);
      case ECDSA_P256_SIGN:
        return signature != null && Ecdsa.verify(publicKey, message, signature);
      default:
        throw new TamgaCheckoutException.UnsupportedAlgorithmException(
            "Unrecognized license scheme: " + scheme);
    }
  }

  /**
   * Full verify pipeline: verifies the signature (fails closed), then decrypts (if {@code alg}
   * indicates AES-256-GCM, using the HKDF-derived key -- see {@code Hkdf}) or plain-decodes the
   * {@code enc} payload, and parses the embedded {@code {"data": <MachineResource>}} JSON.
   *
   * @param scheme the license's signing scheme -- drives verifier dispatch, see type-level
   *     remarks.
   * @param licenseKey HKDF input keying material for an encrypted file.
   * @param fingerprint the target machine's fingerprint -- HKDF {@code info} for an encrypted
   *     file. Decryption fails closed (AES-GCM auth failure) if this doesn't match the machine
   *     the file was issued for.
   */
  public Machine verifyAndDecrypt(LicenseScheme scheme, byte[] publicKey, String licenseKey,
      String fingerprint) {
    if (!verify(scheme, publicKey)) {
      throw new TamgaCheckoutException.SignatureVerificationException();
    }

    byte[] payloadBytes =
        Base64Codec.decodeOrThrow(certificate.enc, "Machine file 'enc' is not valid base64.");

    // Substring matching, NOT exact equality, is intentional and required here -- unlike
    // LicenseFile's fixed 2-literal alg space, MachineFile's alg is a compound
    // encryption-prefix + signature-suffix string across 5 possible schemes (e.g.
    // "aes-256-gcm+ed25519", "rsa-sha256", "ecdsa-sha256"), so there is no single fixed literal
    // set to match exactly. alg is never used for signature-scheme dispatch (see verify above)
    // -- only for this encrypted-vs-plain payload gating.
    // BUGFIX (found by independent review): only "aes-256-gcm" reliably identifies an encrypted
    // payload. A plain (unencrypted) non-Ed25519 file's alg is just its signature suffix (e.g.
    // "rsa-sha256", "ecdsa-sha256") with no "base64" substring at all, so a previous
    // `else if (contains("base64"))` gate incorrectly fell through to the unsupported-algorithm
    // branch and rejected legitimately-signed plain RSA/ECDSA machine files. Treating "not
    // aes-256-gcm" as "plain" is security-neutral: alg is already unauthenticated (only `enc` is
    // covered by the signature verified above), and both branches fail closed regardless -- an
    // encrypted payload misread as plain fails JSON parsing (ciphertext is not valid JSON), and a
    // plain payload misread as encrypted fails the AES-GCM authentication tag check.
    byte[] jsonBytes;
    if (certificate.alg.contains("aes-256-gcm")) {
      byte[] key = Hkdf.deriveMachineFileKey(licenseKey, fingerprint);
      jsonBytes = EncryptedPayloadDecryptor.decrypt(payloadBytes, key, "Encrypted machine file");
    } else {
      jsonBytes = payloadBytes;
    }

    try {
      return Machine.parseResourcePayload(jsonBytes);
    } catch (IOException e) {
      throw new TamgaCheckoutException.OfflineFileFormatException(
          "Machine file payload JSON is malformed: " + e.getMessage(), e);
    }
  }
}
