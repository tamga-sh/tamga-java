package sh.tamga.sdk.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import org.junit.jupiter.api.Test;

class RsaTest {

  private static KeyPair generateKeyPair(int bits) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(bits);
    return generator.generateKeyPair();
  }

  private static byte[] signPkcs1(KeyPair keyPair, byte[] message) throws Exception {
    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(keyPair.getPrivate());
    signer.update(message);
    return signer.sign();
  }

  private static byte[] signPss(KeyPair keyPair, byte[] message) throws Exception {
    Signature signer = Signature.getInstance("RSASSA-PSS");
    signer.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));
    signer.initSign(keyPair.getPrivate());
    signer.update(message);
    return signer.sign();
  }

  @Test
  void verifyPkcs1ReturnsTrueForValidSignature() throws Exception {
    KeyPair keyPair = generateKeyPair(2048);
    byte[] message = "tamga offline proof payload".getBytes(StandardCharsets.UTF_8);
    byte[] signature = signPkcs1(keyPair, message);

    assertThat(Rsa.verifyPkcs1(keyPair.getPublic().getEncoded(), message, signature)).isTrue();
  }

  @Test
  void verifyPssReturnsTrueForValidSignature() throws Exception {
    KeyPair keyPair = generateKeyPair(2048);
    byte[] message = "tamga machine file payload".getBytes(StandardCharsets.UTF_8);
    byte[] signature = signPss(keyPair, message);

    assertThat(Rsa.verifyPss(keyPair.getPublic().getEncoded(), message, signature)).isTrue();
  }

  @Test
  void verifyPkcs1DoesNotAcceptPssSignature() throws Exception {
    KeyPair keyPair = generateKeyPair(2048);
    byte[] message = "message".getBytes(StandardCharsets.UTF_8);
    byte[] pssSignature = signPss(keyPair, message);

    assertThat(Rsa.verifyPkcs1(keyPair.getPublic().getEncoded(), message, pssSignature)).isFalse();
  }

  @Test
  void verifyPssDoesNotAcceptPkcs1Signature() throws Exception {
    KeyPair keyPair = generateKeyPair(2048);
    byte[] message = "message".getBytes(StandardCharsets.UTF_8);
    byte[] pkcs1Signature = signPkcs1(keyPair, message);

    assertThat(Rsa.verifyPss(keyPair.getPublic().getEncoded(), message, pkcs1Signature)).isFalse();
  }

  @Test
  void verifyPkcs1ReturnsFalseForTamperedMessage() throws Exception {
    KeyPair keyPair = generateKeyPair(2048);
    byte[] signature = signPkcs1(keyPair, "original".getBytes(StandardCharsets.UTF_8));
    byte[] tamperedMessage = "tampered".getBytes(StandardCharsets.UTF_8);

    boolean result = Rsa.verifyPkcs1(keyPair.getPublic().getEncoded(), tamperedMessage, signature);

    assertThat(result).isFalse();
  }

  @Test
  void verifyPkcs1ReturnsFalseForMismatchedKey() throws Exception {
    KeyPair keyPair = generateKeyPair(2048);
    KeyPair otherKeyPair = generateKeyPair(2048);
    byte[] message = "message".getBytes(StandardCharsets.UTF_8);
    byte[] signature = signPkcs1(keyPair, message);

    boolean result = Rsa.verifyPkcs1(otherKeyPair.getPublic().getEncoded(), message, signature);

    assertThat(result).isFalse();
  }

  @Test
  void verifyPkcs1ReturnsFalseNotCrashForMalformedPublicKey() {
    byte[] message = "message".getBytes(StandardCharsets.UTF_8);

    assertThat(Rsa.verifyPkcs1(new byte[] {1, 2, 3}, message, new byte[] {4, 5, 6})).isFalse();
  }

  /**
   * GOTCHA regression: the exact bug already found and fixed in this SDK family's Python
   * implementation -- an RSA verifier that never checks key size would silently accept a
   * signature from a weaker-than-documented key. A real 1024-bit key's otherwise-valid signature
   * must be rejected.
   */
  @Test
  void verifyPkcs1RejectsValidSignatureFrom1024BitKey() throws Exception {
    KeyPair weakKeyPair = generateKeyPair(1024);
    byte[] message = "message".getBytes(StandardCharsets.UTF_8);
    byte[] signature = signPkcs1(weakKeyPair, message);

    boolean result = Rsa.verifyPkcs1(weakKeyPair.getPublic().getEncoded(), message, signature);

    assertThat(result).isFalse();
  }
}
