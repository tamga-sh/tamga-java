package sh.tamga.sdk.support;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.util.Base64;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import sh.tamga.sdk.crypto.AesGcm;

/**
 * Test-only helpers for building real, correctly-shaped PEM-wrapped checkout certificates ({@code
 * .lic}/{@code .machine} file bodies) -- signs with this SDK's own crypto primitives (Ed25519,
 * RSA, ECDSA, AES-GCM), since there is no independent reference-implementation fixture available
 * in this repo. The crypto primitives themselves are already tested directly (including
 * adversarial mismatched-curve cases, see {@code EcdsaTest}) -- these fixtures exist to exercise
 * the parsing/dispatch/decrypt WIRING in {@code LicenseFile}/{@code MachineFile}, not to re-prove
 * the primitives are individually correct.
 */
public final class CheckoutFixture {

  private static final SecureRandom RANDOM = new SecureRandom();

  private CheckoutFixture() {
  }

  /**
   * Builds a base64 {@code enc} payload for a plain (unencrypted) file: base64 of the raw JSON
   * bytes.
   */
  public static String plainEnc(byte[] json) {
    return Base64.getEncoder().encodeToString(json);
  }

  /**
   * Builds a base64 {@code enc} payload for an AES-256-GCM-encrypted file: base64 of {@code
   * nonce || ciphertext || tag}.
   */
  public static String encryptedEnc(byte[] json, byte[] key) {
    byte[] nonce = randomBytes(AesGcm.NONCE_LENGTH);
    AesGcm.SealedData sealed = AesGcm.seal(key, nonce, json);
    byte[] ciphertext = sealed.ciphertext();
    byte[] tag = sealed.tag();
    byte[] combined = new byte[nonce.length + ciphertext.length + tag.length];
    System.arraycopy(nonce, 0, combined, 0, nonce.length);
    System.arraycopy(ciphertext, 0, combined, nonce.length, ciphertext.length);
    System.arraycopy(tag, 0, combined, nonce.length + ciphertext.length, tag.length);
    return Base64.getEncoder().encodeToString(combined);
  }

  /**
   * Signs {@code enc}'s base64 STRING bytes (not decoded bytes) with an Ed25519 key, matching the
   * real wire contract.
   */
  public static String ed25519Sign(String enc, Ed25519PrivateKeyParameters privateKey) {
    byte[] message = enc.getBytes(StandardCharsets.UTF_8);
    Ed25519Signer signer = new Ed25519Signer();
    signer.init(true, privateKey);
    signer.update(message, 0, message.length);
    return Base64.getEncoder().encodeToString(signer.generateSignature());
  }

  /** Signs {@code enc} with ECDSA-P256/SHA-256. */
  public static String ecdsaSign(String enc, PrivateKey privateKey) {
    return sign(enc, privateKey, "SHA256withECDSA", false);
  }

  /** Signs {@code enc} with RSA-2048 PKCS#1 v1.5/SHA-256. */
  public static String rsaPkcs1Sign(String enc, PrivateKey privateKey) {
    return sign(enc, privateKey, "SHA256withRSA", false);
  }

  /** Signs {@code enc} with RSA-2048 PSS/SHA-256. */
  public static String rsaPssSign(String enc, PrivateKey privateKey) {
    return sign(enc, privateKey, "RSASSA-PSS", true);
  }

  private static String sign(String enc, PrivateKey privateKey, String algorithm, boolean pss) {
    try {
      byte[] message = enc.getBytes(StandardCharsets.UTF_8);
      Signature signer = Signature.getInstance(algorithm);
      if (pss) {
        PSSParameterSpec pssParams =
            new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1);
        signer.setParameter(pssParams);
      }
      signer.initSign(privateKey);
      signer.update(message);
      return Base64.getEncoder().encodeToString(signer.sign());
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("CheckoutFixture signing unexpectedly failed", e);
    }
  }

  /** {@code wrapInPem} pre-bound to {@code .lic} file markers. */
  public static String wrapLicensePem(String enc, String sig, String alg) {
    return wrapInPem(enc, sig, alg, "-----BEGIN LICENSE FILE-----", "-----END LICENSE FILE-----");
  }

  /** {@code wrapInPem} pre-bound to {@code .machine} file markers. */
  public static String wrapMachinePem(String enc, String sig, String alg) {
    return wrapInPem(enc, sig, alg, "-----BEGIN MACHINE FILE-----", "-----END MACHINE FILE-----");
  }

  private static String wrapInPem(String enc, String sig, String alg, String beginMarker,
      String endMarker) {
    String json = "{\"enc\":\"" + enc + "\",\"sig\":\"" + sig + "\",\"alg\":\"" + alg + "\"}";
    String body = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    return beginMarker + "\n" + body + "\n" + endMarker;
  }

  /** A minimal, valid {@code {"data": {...}}} license-resource JSON payload. */
  public static byte[] licensePayloadJson(String key) {
    return licensePayloadJson(key, null);
  }

  /**
   * As {@link #licensePayloadJson(String)}, with an {@code exp} claim.
   *
   * <p>Format v2 puts the claims inside the signed bytes; a payload without them is a v1 file and
   * no longer verifies. {@code exp} is omitted when {@code null}, matching a checkout made without
   * a {@code ttl}.
   */
  public static byte[] licensePayloadJson(String key, Long exp) {
    String expField = exp == null ? "" : ",\"exp\":" + exp;
    String json = "{\"data\":{\"id\":\"lic_123\",\"type\":\"licenses\",\"attributes\":{"
        + "\"key\":\"" + key + "\",\"suspended\":false,\"uses\":0}},"
        + "\"meta\":{\"iat\":1767225600,\"jti\":\"test-jti\",\"kid\":\"test-kid\"" + expField + "}}";
    return json.getBytes(StandardCharsets.UTF_8);
  }

  /**
   * A {@code {"data": {...}}} license-resource payload exercising every field {@code License}
   * models, including timestamps and metadata.
   */
  public static byte[] fullLicensePayloadJson(String key) {
    String json = "{\"data\":{\"id\":\"lic_123\",\"type\":\"licenses\",\"attributes\":{"
        + "\"key\":\"" + key + "\",\"suspended\":false,\"uses\":3,"
        + "\"expiry\":\"2027-01-01T00:00:00Z\","
        + "\"last_validated_at\":\"2026-08-01T12:00:00.500Z\","
        + "\"last_check_in_at\":\"2026-07-15T09:30:00Z\","
        + "\"metadata\":{\"seats\":5,\"tier\":\"pro\",\"trial\":false,\"note\":null}}},"
        + "\"meta\":{\"iat\":1767225600,\"jti\":\"test-jti\",\"kid\":\"test-kid\"}}";
    return json.getBytes(StandardCharsets.UTF_8);
  }

  /** A minimal, valid {@code {"data": {...}}} machine-resource JSON payload. */
  public static byte[] machinePayloadJson(String fingerprint) {
    String json = "{\"data\":{\"id\":\"mach_123\",\"type\":\"machines\",\"attributes\":{"
        + "\"fingerprint\":\"" + fingerprint + "\",\"heartbeat_status\":\"NOT_STARTED\"}}}";
    return json.getBytes(StandardCharsets.UTF_8);
  }

  /**
   * A {@code {"data": {...}}} machine-resource payload exercising every field {@code Machine}
   * models, including timestamps and metadata.
   */
  public static byte[] fullMachinePayloadJson(String fingerprint) {
    String json = "{\"data\":{\"id\":\"mach_123\",\"type\":\"machines\",\"attributes\":{"
        + "\"fingerprint\":\"" + fingerprint + "\",\"name\":\"build-server-01\","
        + "\"platform\":\"linux-x86_64\",\"heartbeat_status\":\"ALIVE\","
        + "\"last_heartbeat_at\":\"2026-08-01T12:00:00.500Z\","
        + "\"last_check_out_at\":\"2026-07-15T09:30:00Z\","
        + "\"metadata\":{\"region\":\"eu-west-1\",\"cores\":8,\"gpu\":false}}}}";
    return json.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] randomBytes(int length) {
    byte[] bytes = new byte[length];
    RANDOM.nextBytes(bytes);
    return bytes;
  }
}
