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
 * <p>Encryption key derivation is HKDF-SHA256 ({@link sh.tamga.sdk.crypto.Hkdf}), the same
 * primitive license checkout uses -- but with different, non-interchangeable parameters: salt
 * {@code "tamga:machine-file-key-v1"} and {@code info} = the machine fingerprint here, versus salt
 * {@code "tamga:license-file-key-v1"} and {@code info} = {@code "license-file"} there. Decryption
 * therefore requires BOTH the license key AND the target machine's fingerprint. GOTCHA: {@code
 * ttl} is server-validated {@code > 0 && <= 31536000} (365 days) -- {@link #validateTtl} mirrors
 * that bound client-side so a checkout request can fail fast instead of round-tripping to a
 * {@code 422 TTL_INVALID}.
 *
 * <p>Public keys are accepted in whichever encoding the server hands out for the scheme: Ed25519
 * raw 32 bytes, ECDSA-P256 either a raw 65-byte uncompressed point or X.509 {@code
 * SubjectPublicKeyInfo} DER, RSA either PKCS#1 {@code RSAPublicKey} DER or SPKI. See {@link
 * sh.tamga.sdk.crypto.Ecdsa} and {@link sh.tamga.sdk.crypto.Rsa} for which server path emits which.
 *
 * <p>{@code alg} is {@code <encoding>+<signing-suffix>+v2} and the {@code +v2} marker is mandatory
 * -- see {@link MachineFileAlg}. The signing suffix is a cross-check against the caller's scheme,
 * never a verifier selector.
 *
 * <p>An encrypted machine file's {@code enc} is {@code "<nonce_b64>.<ciphertext_b64>"}, NOT the
 * single {@code base64(nonce||ciphertext||tag)} blob a {@code .lic} file uses -- see {@link
 * EncryptedPayloadDecryptor}.
 *
 * <p>Expiry: the signed payload carries {@code meta.iat}/{@code exp}/{@code jti}/{@code kid} just
 * as a {@code .lic} file does, and {@link #verifyWithClaims} enforces {@code exp} with the same
 * 60-second clock-skew tolerance ({@link LicenseFile#CLOCK_SKEW_TOLERANCE_SECONDS}) and raises the
 * same {@link TamgaCheckoutException.LicenseFileExpiredException}. The {@code ttl}/{@code expiry}
 * echoed back in the JSON:API checkout envelope are metadata-only and unsigned -- never enforce
 * against those.
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
   * <p>The {@code alg} string is still parsed and validated first ({@link MachineFileAlg}): it
   * must be {@code <encoding>+<signing-suffix>+v2}, its encoding must be one this SDK implements,
   * and its signing suffix must agree with {@code scheme}. A file without the {@code +v2} marker
   * is rejected outright -- see {@link MachineFileAlg} for why there is no v1 fallback.
   *
   * @throws TamgaCheckoutException.SchemeNotSupportedException if {@code scheme} is {@link
   *     LicenseScheme#RSA_2048_JWT_RS256} -- never implemented for machine files.
   * @throws TamgaCheckoutException.UnsupportedAlgorithmException if {@code alg} is malformed, not
   *     {@code v2}, or contradicts {@code scheme}.
   */
  public boolean verify(LicenseScheme scheme, byte[] publicKey) {
    // Up front, before alg is even looked at: the one scheme this SDK refuses to implement.
    if (scheme == LicenseScheme.RSA_2048_JWT_RS256) {
      throw new TamgaCheckoutException.SchemeNotSupportedException(
          "RSA_2048_JWT_RS256 is rejected server-side for machine files (422 "
              + "SCHEME_NOT_SUPPORTED) and is not implemented client-side either -- this SDK "
              + "never attempts JWT/RS256 verification.");
    }
    MachineFileAlg.parse(certificate.alg, scheme);

    byte[] signature = Base64Codec.decodeOrNull(certificate.sig);
    byte[] message = certificate.enc.getBytes(StandardCharsets.UTF_8);

    switch (scheme) {
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
   * {@code enc} payload, parses the embedded {@code {"data": <MachineResource>, "meta": {...}}}
   * JSON, and enforces the signed {@code exp} claim against the local clock.
   *
   * <p>Uses {@link System#currentTimeMillis()}. An application that holds a server-supplied
   * timestamp should call {@link #verifyWithClaims} instead -- the local clock is under the user's
   * control, and winding it back is the obvious way to revive an expired file.
   *
   * @param scheme the license's signing scheme -- drives verifier dispatch, see type-level
   *     remarks.
   * @param licenseKey HKDF input keying material for an encrypted file.
   * @param fingerprint the target machine's fingerprint -- HKDF {@code info} for an encrypted
   *     file. Decryption fails closed (AES-GCM auth failure) if this doesn't match the machine
   *     the file was issued for.
   * @throws TamgaCheckoutException.LicenseFileExpiredException if the file's signed {@code exp}
   *     has passed -- the same distinct outcome the {@code .lic} path uses, so a caller can tell
   *     "expired, fetch a fresh one" from "forged or corrupt".
   */
  public Machine verifyAndDecrypt(LicenseScheme scheme, byte[] publicKey, String licenseKey,
      String fingerprint) {
    return verifyWithClaims(scheme, publicKey, licenseKey, fingerprint,
        System.currentTimeMillis() / 1000L).machine();
  }

  /**
   * As {@link #verifyAndDecrypt}, also returning the signed claims and taking the current time
   * from the caller.
   *
   * <p>Two uses for {@code nowUnixSeconds}, both mirroring {@link LicenseFile#verifyWithClaims}.
   * Tests get determinism. And an application that keeps a server-supplied timestamp -- the
   * recommended defence against a user winding the system clock back to revive an expired file --
   * can pass that instead of trusting the local clock.
   *
   * <p>Expiry is enforced either way; it is not opt-in. A file whose checkout carried no {@code
   * ttl} has no {@code exp} at all and genuinely never expires -- that absence is legitimate, not
   * an error.
   *
   * <p>Order is load-bearing: signature first, over the {@code enc} string exactly as it appears
   * in the certificate, and only then any decode, split or decrypt. Nothing attacker-controlled
   * gets parsed before it has been authenticated.
   */
  public Machine.MachineWithClaims verifyWithClaims(LicenseScheme scheme, byte[] publicKey,
      String licenseKey, String fingerprint, long nowUnixSeconds) {
    if (!verify(scheme, publicKey)) {
      throw new TamgaCheckoutException.SignatureVerificationException();
    }

    // Already validated by verify() above; re-parsed here only to read the encoding prefix.
    MachineFileAlg alg = MachineFileAlg.parse(certificate.alg, scheme);

    byte[] jsonBytes;
    if (alg.isEncrypted()) {
      byte[] key = Hkdf.deriveMachineFileKey(licenseKey, fingerprint);
      jsonBytes = EncryptedPayloadDecryptor.decryptDotSeparated(certificate.enc, key,
          "Encrypted machine file");
    } else {
      jsonBytes =
          Base64Codec.decodeOrThrow(certificate.enc, "Machine file 'enc' is not valid base64.");
    }

    Machine.MachineWithClaims result;
    try {
      result = Machine.parseResourcePayloadWithClaims(jsonBytes);
    } catch (IOException e) {
      throw new TamgaCheckoutException.OfflineFileFormatException(
          "Machine file payload JSON is malformed: " + e.getMessage(), e);
    }

    // The signature proves the file is authentic. It does not prove it is still valid -- that is
    // this check, and skipping it is what made a checked-out machine permanent.
    Long expiresAt = result.claims().expiresAt();
    if (expiresAt != null
        && nowUnixSeconds - LicenseFile.CLOCK_SKEW_TOLERANCE_SECONDS > expiresAt) {
      throw new TamgaCheckoutException.LicenseFileExpiredException(expiresAt);
    }

    return result;
  }
}
