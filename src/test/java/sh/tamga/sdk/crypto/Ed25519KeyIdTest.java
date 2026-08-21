package sh.tamga.sdk.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import sh.tamga.sdk.support.MachineFixtures;
import sh.tamga.sdk.support.SigningKeyVectors;

/**
 * Pins {@link Ed25519#keyId(String)} against vectors this SDK did not generate -- see {@link
 * SigningKeyVectors} for why that distinction is the whole point.
 */
class Ed25519KeyIdTest {

  private static List<SigningKeyVectors.Vector> vectors() {
    return SigningKeyVectors.vectors();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("vectors")
  void keyIdReproducesEveryServerVector(SigningKeyVectors.Vector vector) {
    assertThat(Ed25519.keyId(vector.publicKey())).isEqualTo(vector.keyId());
  }

  @Test
  void keyIdHashesTheBase64StringNotTheDecodedKeyBytes() {
    // The trap this method exists to pin, and the reason the positive vectors above are not
    // enough on their own: any implementation that hashes SOMETHING consistently reproduces its
    // own answer. Only the negative value distinguishes the two candidate inputs.
    String publicKey = SigningKeyVectors.negativePublicKey();

    String actual = Ed25519.keyId(publicKey);

    assertThat(actual).isEqualTo(SigningKeyVectors.negativeCorrectKeyId());
    assertThat(actual).isNotEqualTo(SigningKeyVectors.negativeKeyIdIfDecodedFirst());
    // And the wrong value really is what decoding first produces -- so the assertion above is
    // discriminating between two live candidates rather than rejecting an arbitrary string.
    assertThat(hexOfFirstEightBytes(Base64.getDecoder().decode(publicKey)))
        .isEqualTo(SigningKeyVectors.negativeKeyIdIfDecodedFirst());
  }

  @Test
  void keyIdOfTheEmptyStringIsTheUnpublishedAccountSentinel() {
    // check_out_license.rs passes account.ed25519_public_key.unwrap_or_default(), so an account
    // whose key column was never populated signs every file with this one id. Recognising it is
    // the difference between "your key set is stale" and "this server published no key at all".
    assertThat(Ed25519.keyId("")).isEqualTo(Ed25519.UNPUBLISHED_ACCOUNT_KEY_ID);
    assertThat(Ed25519.UNPUBLISHED_ACCOUNT_KEY_ID).isEqualTo("e3b0c44298fc1c14");
  }

  @Test
  void keyIdIsAlwaysSixteenLowercaseHexCharacters() {
    // Eight BYTES, sixteen characters -- not eight characters. Each byte is zero-padded, which the
    // leading_zero_digest vector exercises, and masked to eight bits, which the high_bytes vector
    // exercises: a sign-extended byte would render as ffffffXX and overrun the length.
    for (SigningKeyVectors.Vector vector : vectors()) {
      String keyId = Ed25519.keyId(vector.publicKey());
      assertThat(keyId).hasSize(16);
      assertThat(keyId).matches("[0-9a-f]{16}");
    }
  }

  @Test
  void keyIdDependsOnTheExactPublishedString() {
    // The id is a hash of the string, so re-encoding, padding or trimming it changes the answer.
    // Anything that "normalises" a published key breaks the match it was trying to make.
    String publicKey = SigningKeyVectors.negativePublicKey();

    assertThat(Ed25519.keyId(publicKey)).isNotEqualTo(Ed25519.keyId(publicKey + "\n"));
    assertThat(Ed25519.keyId(publicKey))
        .isNotEqualTo(Ed25519.keyId(publicKey.toLowerCase(Locale.ROOT)));
  }

  private static String hexOfFirstEightBytes(byte[] input) {
    MessageDigest sha256;
    try {
      sha256 = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    byte[] digest = sha256.digest(input);
    StringBuilder hex = new StringBuilder();
    for (int i = 0; i < 8; i++) {
      hex.append(String.format("%02x", digest[i]));
    }
    return hex.toString();
  }

  @Test
  void keyIdReproducesEveryServerProducedFixturesId() {
    // A second, independent corroboration of the HASH RULE from server-produced data, across four
    // key encodings the vectors above do not cover (a 65-byte SEC1 point, PKCS#1 RSA, Ed25519).
    //
    // The hash rule is the ONLY thing these entries may be used for. Their kids are not what
    // production emits for a non-Ed25519 file -- check_out_machine.rs derives the claim from the
    // account's Ed25519 key whatever scheme signed the bytes, so the server emits one kid where
    // this corpus carries four. See MachineFixtures.Fixture#kid.
    for (MachineFixtures.Fixture fixture : MachineFixtures.all()) {
      assertThat(Ed25519.keyId(fixture.publicKeyBase64()))
          .as("%s", fixture.name())
          .isEqualTo(fixture.kid());
    }
  }

  @Test
  void keyIdMatchesAnIndependentDigestOverTheStringsUtf8Bytes() {
    String publicKey = SigningKeyVectors.negativePublicKey();

    String independent = hexOfFirstEightBytes(publicKey.getBytes(StandardCharsets.UTF_8));

    assertThat(Ed25519.keyId(publicKey)).isEqualTo(independent);
  }
}
