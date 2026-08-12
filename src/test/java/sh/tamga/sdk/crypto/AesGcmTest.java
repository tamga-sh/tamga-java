package sh.tamga.sdk.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class AesGcmTest {

  private static final SecureRandom RANDOM = new SecureRandom();

  private static byte[] randomBytes(int length) {
    byte[] bytes = new byte[length];
    RANDOM.nextBytes(bytes);
    return bytes;
  }

  @Test
  void sealThenOpenRoundTripsThePlaintext() {
    byte[] key = randomBytes(32);
    byte[] nonce = randomBytes(AesGcm.NONCE_LENGTH);
    byte[] plaintext = "license file plaintext payload".getBytes(StandardCharsets.UTF_8);

    AesGcm.SealedData sealed = AesGcm.seal(key, nonce, plaintext);
    byte[] opened = AesGcm.open(key, nonce, sealed.ciphertext(), sealed.tag());

    assertThat(opened).isEqualTo(plaintext);
  }

  @Test
  void openThrowsAuthenticationFailedForTamperedCiphertext() {
    byte[] key = randomBytes(32);
    byte[] nonce = randomBytes(AesGcm.NONCE_LENGTH);
    AesGcm.SealedData sealed = AesGcm.seal(key, nonce, "payload".getBytes(StandardCharsets.UTF_8));
    byte[] tampered = sealed.ciphertext().clone();
    tampered[0] ^= 0xFF;

    assertThatThrownBy(() -> AesGcm.open(key, nonce, tampered, sealed.tag()))
        .isInstanceOf(AesGcm.AuthenticationFailedException.class);
  }

  @Test
  void openThrowsAuthenticationFailedForWrongKey() {
    byte[] key = randomBytes(32);
    byte[] wrongKey = randomBytes(32);
    byte[] nonce = randomBytes(AesGcm.NONCE_LENGTH);
    AesGcm.SealedData sealed = AesGcm.seal(key, nonce, "payload".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> AesGcm.open(wrongKey, nonce, sealed.ciphertext(), sealed.tag()))
        .isInstanceOf(AesGcm.AuthenticationFailedException.class);
  }

  @Test
  void openThrowsAuthenticationFailedForTamperedTag() {
    byte[] key = randomBytes(32);
    byte[] nonce = randomBytes(AesGcm.NONCE_LENGTH);
    AesGcm.SealedData sealed = AesGcm.seal(key, nonce, "payload".getBytes(StandardCharsets.UTF_8));
    byte[] tamperedTag = sealed.tag().clone();
    tamperedTag[0] ^= 0xFF;

    assertThatThrownBy(() -> AesGcm.open(key, nonce, sealed.ciphertext(), tamperedTag))
        .isInstanceOf(AesGcm.AuthenticationFailedException.class);
  }

  @Test
  void openThrowsMalformedInputForShortNonce() {
    byte[] key = randomBytes(32);
    byte[] shortNonce = randomBytes(8);
    byte[] ciphertext = randomBytes(16);
    byte[] tag = randomBytes(AesGcm.TAG_LENGTH);

    assertThatThrownBy(() -> AesGcm.open(key, shortNonce, ciphertext, tag))
        .isInstanceOf(AesGcm.MalformedAesGcmInputException.class);
  }

  @Test
  void openThrowsMalformedInputForWrongLengthTag() {
    byte[] key = randomBytes(32);
    byte[] nonce = randomBytes(AesGcm.NONCE_LENGTH);
    byte[] ciphertext = randomBytes(16);
    byte[] wrongLengthTag = randomBytes(8);

    assertThatThrownBy(() -> AesGcm.open(key, nonce, ciphertext, wrongLengthTag))
        .isInstanceOf(AesGcm.MalformedAesGcmInputException.class);
  }

  @Test
  void sealThrowsMalformedInputForShortNonce() {
    byte[] key = randomBytes(32);
    byte[] shortNonce = randomBytes(8);
    byte[] plaintext = "payload".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> AesGcm.seal(key, shortNonce, plaintext))
        .isInstanceOf(AesGcm.MalformedAesGcmInputException.class);
  }

  @Test
  void sealThrowsMalformedInputForWrongLengthKey() {
    byte[] wrongLengthKey = randomBytes(31);
    byte[] nonce = randomBytes(AesGcm.NONCE_LENGTH);
    byte[] plaintext = "payload".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> AesGcm.seal(wrongLengthKey, nonce, plaintext))
        .isInstanceOf(AesGcm.MalformedAesGcmInputException.class);
  }
}
