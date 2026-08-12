package sh.tamga.sdk.checkout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import sh.tamga.sdk.crypto.Ed25519;
import sh.tamga.sdk.crypto.NaiveKey;
import sh.tamga.sdk.error.TamgaCheckoutException;
import sh.tamga.sdk.model.License;
import sh.tamga.sdk.model.TamgaJsonMapper;

/**
 * Parses, verifies, and decrypts an offline {@code .lic} license file:
 *
 * <pre>
 * -----BEGIN LICENSE FILE-----
 * &lt;base64 of JSON: { "enc": "&lt;base64&gt;", "sig": "&lt;base64 ed25519 sig&gt;",
 *                     "alg": "..." }&gt;
 * -----END LICENSE FILE-----
 * </pre>
 *
 * <p>{@code alg} is exactly {@code "base64+ed25519"} (plain) or {@code "aes-256-gcm+ed25519"}
 * (encrypted) -- Ed25519 ONLY for the checkout signature, independent of the license's own {@code
 * scheme} (contrast with {@link MachineFile}, which dispatches by scheme).
 *
 * <p><b>CRITICAL</b> -- the single most consequential correctness trap in this SDK: the Ed25519
 * signature covers {@code enc}'s ASCII/UTF-8 bytes of the BASE64 STRING ITSELF, NOT the
 * base64-decoded bytes. Get the byte source wrong and every {@code .lic} file either fails
 * verification (safe but broken) or, worse, a bug that skips verification silently accepts forged
 * files. See the {@code CRITICAL:} comment at the call site in {@link #verify}.
 *
 * <p>GOTCHA: {@code includes} is always {@code []} server-side -- this SDK does not model an
 * "embedded relationships via checkout" feature. GOTCHA: checkout {@code id} is a fresh UUIDv7 per
 * call, not idempotent. GOTCHA: {@code ttl}/{@code expiry} (returned alongside the certificate by
 * the JSON:API checkout response, not carried inside the file itself) are metadata-only, NOT
 * embedded in the signed payload and NOT re-checked server-side on later validation -- expiry
 * enforcement for an offline file is entirely this SDK's client-side responsibility.
 */
public final class LicenseFile {

  private static final String BEGIN_MARKER = "-----BEGIN LICENSE FILE-----";
  private static final String END_MARKER = "-----END LICENSE FILE-----";
  private static final String ALG_PLAIN = "base64+ed25519";
  private static final String ALG_ENCRYPTED = "aes-256-gcm+ed25519";

  private final LicenseFileCertificate certificate;

  private LicenseFile(LicenseFileCertificate certificate) {
    this.certificate = certificate;
  }

  /**
   * Parses a PEM-wrapped {@code .lic} file. Does NOT verify the signature -- call {@link
   * #verify}/{@link #verifyAndDecrypt} separately.
   */
  public static LicenseFile parse(String pem) {
    String inner = PemEnvelope.strip(pem, BEGIN_MARKER, END_MARKER);
    byte[] jsonBytes = Base64Codec.decodeOrThrow(inner, "License file body is not valid base64.");

    LicenseFileCertificate certificate;
    try {
      certificate = TamgaJsonMapper.instance().readValue(jsonBytes, LicenseFileCertificate.class);
    } catch (IOException e) {
      throw new TamgaCheckoutException.OfflineFileFormatException(
          "License file certificate JSON is malformed: " + e.getMessage(), e);
    }
    // SECURITY regression (found by independent review): a well-formed-but-incomplete
    // certificate (missing enc/sig/alg, or explicit JSON nulls for them) previously reached
    // certificate.enc.getBytes(...)/Base64.getDecoder().decode(null) unguarded, throwing an
    // uncaught NullPointerException instead of the documented TamgaCheckoutException -- fail here
    // instead, with the right exception type, before any downstream code assumes non-null.
    if (certificate.enc == null || certificate.sig == null || certificate.alg == null) {
      throw new TamgaCheckoutException.OfflineFileFormatException(
          "License file certificate is missing a required field (enc, sig, or alg).");
    }
    return new LicenseFile(certificate);
  }

  /**
   * Verifies the Ed25519 signature against the account's raw 32-byte Ed25519 public key. Returns
   * {@code false} rather than throwing on a verification failure specifically -- callers that need
   * a fail-closed exception should use {@link #verifyAndDecrypt}.
   *
   * @throws TamgaCheckoutException.UnsupportedAlgorithmException if {@code alg} is not one of the
   *     two documented ed25519 literals.
   */
  public boolean verify(byte[] publicKey) {
    // Exact match against the two documented literal values -- LicenseFile's alg is always
    // ed25519 and always one of exactly these two literals, unlike MachineFile's compound
    // encryption-prefix/signature-suffix alg space across 5 schemes.
    if (!ALG_PLAIN.equals(certificate.alg) && !ALG_ENCRYPTED.equals(certificate.alg)) {
      throw new TamgaCheckoutException.UnsupportedAlgorithmException(
          "Unsupported license file algorithm: '" + certificate.alg + "'. Only ed25519-signed "
              + "license files are supported.");
    }

    byte[] signature = Base64Codec.decodeOrNull(certificate.sig);
    if (signature == null) {
      return false;
    }

    // CRITICAL: sign/verify over enc's base64 STRING bytes (UTF-8 of the string itself), NOT the
    // base64-decoded payload bytes. See type-level remarks.
    byte[] message = certificate.enc.getBytes(StandardCharsets.UTF_8);
    return Ed25519.verify(publicKey, message, signature);
  }

  /**
   * Full verify pipeline: verifies the Ed25519 signature (fails closed), then decrypts (if {@code
   * alg} indicates AES-256-GCM) or plain-decodes the {@code enc} payload, and parses the embedded
   * {@code {"data": <LicenseResource>}} JSON into a {@link License}.
   *
   * @param licenseKey used to derive the AES-256-GCM key (via {@code NaiveKey}) for an encrypted
   *     file. Ignored for a plain (unencrypted) file, but still required for a uniform call shape
   *     across both cases.
   */
  public License verifyAndDecrypt(byte[] publicKey, String licenseKey) {
    if (!verify(publicKey)) {
      throw new TamgaCheckoutException.SignatureVerificationException();
    }

    byte[] payloadBytes =
        Base64Codec.decodeOrThrow(certificate.enc, "License file 'enc' is not valid base64.");

    byte[] jsonBytes;
    if (ALG_ENCRYPTED.equals(certificate.alg)) {
      byte[] key = NaiveKey.derive(licenseKey);
      jsonBytes = EncryptedPayloadDecryptor.decrypt(payloadBytes, key, "Encrypted license file");
    } else if (ALG_PLAIN.equals(certificate.alg)) {
      jsonBytes = payloadBytes;
    } else {
      // Defensive: unreachable in practice since verify() above already validated alg is one of
      // these two exact literals and throws immediately if that fails.
      throw new TamgaCheckoutException.UnsupportedAlgorithmException(
          "Unsupported license file algorithm: '" + certificate.alg + "'.");
    }

    try {
      return License.parseResourcePayload(jsonBytes);
    } catch (IOException e) {
      throw new TamgaCheckoutException.OfflineFileFormatException(
          "License file payload JSON is malformed: " + e.getMessage(), e);
    }
  }
}
