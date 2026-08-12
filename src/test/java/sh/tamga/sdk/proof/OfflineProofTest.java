package sh.tamga.sdk.proof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sh.tamga.sdk.error.TamgaCheckoutException;

class OfflineProofTest {

  private static KeyPair generateKeyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }

  private static String sign(String payload, java.security.PrivateKey privateKey) throws Exception {
    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(privateKey);
    signer.update(payload.getBytes(StandardCharsets.UTF_8));
    return Base64.getEncoder().encodeToString(signer.sign());
  }

  @Test
  void parseSplitsTheVersionPrefixFromTheSignature() {
    OfflineProof proof = OfflineProof.parse("v1x0.c29tZS1zaWduYXR1cmU=");

    assertThat(proof).isNotNull();
  }

  @Test
  void parseThrowsForMissingVersionPrefix() {
    assertThatThrownBy(() -> OfflineProof.parse("v2x0.c29tZQ=="))
        .isInstanceOf(TamgaCheckoutException.UnsupportedAlgorithmException.class);
  }

  @Test
  void parseThrowsForEmptySignatureAfterPrefix() {
    assertThatThrownBy(() -> OfflineProof.parse("v1x0."))
        .isInstanceOf(TamgaCheckoutException.OfflineFileFormatException.class);
  }

  @Test
  void buildSignedPayloadProducesTheDocumentedCanonicalFieldOrder() {
    Map<String, Object> dataset = new LinkedHashMap<>();
    dataset.put("z", 1);
    dataset.put("a", 2);

    String payload = OfflineProof.buildSignedPayload("acc1", "mach1", "fp1", dataset);

    String expected = "{\"account\":{\"id\":\"acc1\"},\"dataset\":{\"a\":2,\"z\":1},"
        + "\"machine\":{\"fingerprint\":\"fp1\",\"id\":\"mach1\"}}";
    assertThat(payload).isEqualTo(expected);
  }

  @Test
  void verifyReturnsTrueForValidSignatureOverCanonicalPayload() throws Exception {
    KeyPair keyPair = generateKeyPair();
    Map<String, Object> dataset = new LinkedHashMap<>();
    dataset.put("seats", 5);
    String payload = OfflineProof.buildSignedPayload("acc1", "mach1", "fp1", dataset);
    String signature = sign(payload, keyPair.getPrivate());

    OfflineProof proof = OfflineProof.parse("v1x0." + signature);
    byte[] publicKey = keyPair.getPublic().getEncoded();

    assertThat(proof.verify(publicKey, "acc1", "mach1", "fp1", dataset)).isTrue();
  }

  @Test
  void verifyReturnsFalseWhenDatasetWasAlteredAfterSigning() throws Exception {
    KeyPair keyPair = generateKeyPair();
    Map<String, Object> originalDataset = new LinkedHashMap<>();
    originalDataset.put("seats", 5);
    String payload = OfflineProof.buildSignedPayload("acc1", "mach1", "fp1", originalDataset);
    String signature = sign(payload, keyPair.getPrivate());

    OfflineProof proof = OfflineProof.parse("v1x0." + signature);
    byte[] publicKey = keyPair.getPublic().getEncoded();

    Map<String, Object> tamperedDataset = new LinkedHashMap<>();
    tamperedDataset.put("seats", 999);

    assertThat(proof.verify(publicKey, "acc1", "mach1", "fp1", tamperedDataset)).isFalse();
  }

  @Test
  void verifyReturnsFalseForMismatchedKey() throws Exception {
    KeyPair keyPair = generateKeyPair();
    KeyPair otherKeyPair = generateKeyPair();
    Map<String, Object> dataset = new LinkedHashMap<>();
    String payload = OfflineProof.buildSignedPayload("acc1", "mach1", "fp1", dataset);
    String signature = sign(payload, keyPair.getPrivate());

    OfflineProof proof = OfflineProof.parse("v1x0." + signature);
    byte[] wrongPublicKey = otherKeyPair.getPublic().getEncoded();

    assertThat(proof.verify(wrongPublicKey, "acc1", "mach1", "fp1", dataset)).isFalse();
  }

  @Test
  void verifyReturnsFalseNotCrashForMalformedBase64Signature() {
    OfflineProof proof = OfflineProof.parse("v1x0.not valid base64!!!");
    Map<String, Object> dataset = new LinkedHashMap<>();

    boolean result = proof.verify(new byte[] {1, 2, 3}, "acc1", "mach1", "fp1", dataset);

    assertThat(result).isFalse();
  }
}
