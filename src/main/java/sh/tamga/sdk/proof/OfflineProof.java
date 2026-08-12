package sh.tamga.sdk.proof;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import sh.tamga.sdk.crypto.Rsa;
import sh.tamga.sdk.error.TamgaCheckoutException;
import sh.tamga.sdk.model.CanonicalJson;

/**
 * Machine offline proof (air-gapped verification).
 *
 * <p><b>Scope note:</b> the {@code generate-offline-proof} HTTP call itself is part of {@code
 * TamgaClient}'s HTTP-facing surface and stays deferred to a future session. {@code OfflineProof}
 * below covers the proof-STRING parsing/verification this architecture pivot needed: the canonical
 * JSON signed-payload construction and RSA-2048 PKCS#1 v1.5/SHA-256 signature check.
 *
 * <p>Proof signing is ALWAYS RSA-2048 PKCS#1 v1.5/SHA-256, regardless of the license's {@code
 * LicenseScheme} -- this type never dispatches by scheme, unlike {@code
 * checkout.MachineFile}.
 *
 * <p>{@code meta.proof} has the shape {@code "v1x0.<base64 signature>"} -- {@link #parse} splits
 * the version prefix from the signature and rejects malformed/missing-prefix strings.
 *
 * <p><b>CRITICAL</b> -- canonical payload field order: the signed payload is
 * {@code {"account":{"id":...},"machine":{"id":...,"fingerprint":...},"dataset":...}} in literal
 * source-code order is WRONG. The server builds this payload via {@code serde_json::json!(...)},
 * whose backing {@code serde_json::Map} is {@code BTreeMap}-backed (the {@code preserve_order}/
 * {@code indexmap} Cargo feature is enabled on neither {@code tamga-api} nor {@code tamga-rust}),
 * so the actual wire bytes are recursively alphabetically key-sorted at every nesting level, not
 * literal source order: {@code {"account":{"id":...},"dataset":{...sorted...},"machine":{
 * "fingerprint":...,"id":...}}} -- note {@code dataset} sorts before {@code machine}, and inside
 * {@code machine}, {@code fingerprint} sorts before {@code id}. This applies recursively to
 * whatever keys the caller's own {@code dataset} object contains too. {@link #buildSignedPayload}
 * implements this via {@link CanonicalJson}, a canonical (alphabetical, recursive) JSON writer,
 * rather than a fixed-property-order type.
 */
public final class OfflineProof {

  /** The only version prefix this SDK recognizes. */
  public static final String VERSION_PREFIX = "v1x0.";

  private final String rawSignatureBase64;

  private OfflineProof(String rawSignatureBase64) {
    this.rawSignatureBase64 = rawSignatureBase64;
  }

  /**
   * Parses a {@code meta.proof} string, splitting the {@code "v1x0."} version prefix from the
   * base64 signature.
   *
   * @throws TamgaCheckoutException.UnsupportedAlgorithmException if the string is missing the
   *     expected version prefix.
   * @throws TamgaCheckoutException.OfflineFileFormatException if the prefix is present but the
   *     remaining signature is empty.
   */
  public static OfflineProof parse(String proof) {
    if (!proof.startsWith(VERSION_PREFIX)) {
      throw new TamgaCheckoutException.UnsupportedAlgorithmException(
          "Unrecognized offline proof format: expected the '" + VERSION_PREFIX + "' prefix.");
    }

    String signature = proof.substring(VERSION_PREFIX.length());
    if (signature.isEmpty()) {
      throw new TamgaCheckoutException.OfflineFileFormatException(
          "Offline proof signature was empty after the version prefix.");
    }

    return new OfflineProof(signature);
  }

  /**
   * Builds the exact canonical JSON byte string the server signs -- recursively alphabetically
   * key-sorted, matching {@code serde_json::json!()}'s {@code BTreeMap}-backed output. See
   * type-level remarks for why this is NOT literal source order.
   */
  public static String buildSignedPayload(String accountId, String machineId, String fingerprint,
      Map<String, Object> dataset) {
    Map<String, Object> account = new LinkedHashMap<>();
    account.put("id", accountId);

    Map<String, Object> machine = new LinkedHashMap<>();
    machine.put("id", machineId);
    machine.put("fingerprint", fingerprint);

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("account", account);
    payload.put("machine", machine);
    payload.put("dataset", dataset);

    return CanonicalJson.serialize(payload);
  }

  /**
   * Verifies this proof's RSA-2048 PKCS#1 v1.5/SHA-256 signature against the reconstructed
   * canonical payload. Fails closed (returns {@code false}) on any mismatch, including a {@code
   * dataset} that was altered post-signing.
   */
  public boolean verify(byte[] publicKeyDer, String accountId, String machineId, String fingerprint,
      Map<String, Object> dataset) {
    byte[] signature;
    try {
      signature = Base64.getDecoder().decode(rawSignatureBase64);
    } catch (IllegalArgumentException e) {
      return false;
    }

    String payload = buildSignedPayload(accountId, machineId, fingerprint, dataset);
    byte[] message = payload.getBytes(StandardCharsets.UTF_8);
    return Rsa.verifyPkcs1(publicKeyDer, message, signature);
  }
}
