package sh.tamga.sdk.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.junit.jupiter.api.Test;

class Ed25519Test {

  private static Ed25519PrivateKeyParameters generateKey() {
    return new Ed25519PrivateKeyParameters(new SecureRandom());
  }

  private static byte[] sign(Ed25519PrivateKeyParameters privateKey, byte[] message) {
    Ed25519Signer signer = new Ed25519Signer();
    signer.init(true, privateKey);
    signer.update(message, 0, message.length);
    return signer.generateSignature();
  }

  @Test
  void verifyReturnsTrueForValidSignature() {
    Ed25519PrivateKeyParameters privateKey = generateKey();
    byte[] publicKey = privateKey.generatePublicKey().getEncoded();
    byte[] message = "tamga license file payload".getBytes(StandardCharsets.UTF_8);
    byte[] signature = sign(privateKey, message);

    assertThat(Ed25519.verify(publicKey, message, signature)).isTrue();
  }

  @Test
  void verifyReturnsFalseForTamperedMessage() {
    Ed25519PrivateKeyParameters privateKey = generateKey();
    byte[] publicKey = privateKey.generatePublicKey().getEncoded();
    byte[] signature = sign(privateKey, "original message".getBytes(StandardCharsets.UTF_8));
    byte[] tamperedMessage = "tampered message".getBytes(StandardCharsets.UTF_8);

    assertThat(Ed25519.verify(publicKey, tamperedMessage, signature)).isFalse();
  }

  @Test
  void verifyReturnsFalseForMismatchedKey() {
    Ed25519PrivateKeyParameters privateKey = generateKey();
    Ed25519PrivateKeyParameters otherKey = generateKey();
    byte[] message = "message".getBytes(StandardCharsets.UTF_8);
    byte[] signature = sign(privateKey, message);

    boolean result = Ed25519.verify(otherKey.generatePublicKey().getEncoded(), message, signature);

    assertThat(result).isFalse();
  }

  @Test
  void verifyReturnsFalseNotCrashForMalformedPublicKeyLength() {
    byte[] message = "message".getBytes(StandardCharsets.UTF_8);

    assertThat(Ed25519.verify(new byte[10], message, new byte[64])).isFalse();
  }

  @Test
  void verifyReturnsFalseNotCrashForMalformedSignatureLength() {
    Ed25519PrivateKeyParameters privateKey = generateKey();
    byte[] publicKey = privateKey.generatePublicKey().getEncoded();
    byte[] message = "message".getBytes(StandardCharsets.UTF_8);

    assertThat(Ed25519.verify(publicKey, message, new byte[10])).isFalse();
  }
}
