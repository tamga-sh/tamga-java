package sh.tamga.sdk.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SigningKeyTest {

  private static final String ZERO_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

  private static SigningKey decode(String json) throws IOException {
    return SigningKey.fromResourceNode(new ObjectMapper().readTree(json));
  }

  @Test
  void theResourceIdIsTheKeyIdNotUuid() throws IOException {
    // The server sets id: k.kid -- the same value an offline file's kid claim carries. Reading it
    // as an opaque uuid and hashing the key locally instead would disagree with the server the
    // moment the two ever differ.
    SigningKey key = decode("{\"type\":\"signing-keys\",\"id\":\"51643eac9777b63a\","
        + "\"attributes\":{\"algorithm\":\"ed25519\",\"publicKey\":\"" + ZERO_KEY + "\","
        + "\"status\":\"retired\",\"created\":\"2026-01-01T00:00:00Z\","
        + "\"retired\":\"2026-06-01T00:00:00Z\"}}");

    assertThat(key.keyId()).isEqualTo("51643eac9777b63a");
    assertThat(key.algorithm()).isEqualTo("ed25519");
    assertThat(key.publicKey()).isEqualTo(ZERO_KEY);
    assertThat(key.status()).isEqualTo("retired");
    assertThat(key.isRetired()).isTrue();
    assertThat(key.created()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    assertThat(key.retired()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
  }

  @Test
  void thePublicKeyFieldIsCamelCaseAloneInSnakeCaseBag() throws IOException {
    // accounts/serializer.rs renames exactly one field. Reading public_key here yields null and
    // nothing else complains -- the same trap the release resource sets from the other direction.
    SigningKey snakeCase = decode("{\"type\":\"signing-keys\",\"id\":\"51643eac9777b63a\","
        + "\"attributes\":{\"algorithm\":\"ed25519\",\"public_key\":\"" + ZERO_KEY + "\","
        + "\"status\":\"active\"}}");

    assertThat(snakeCase.publicKey()).isNull();
  }

  @Test
  void anActiveKeyOmitsRetiredEntirelyRatherThanNullingIt() throws IOException {
    SigningKey key = decode("{\"type\":\"signing-keys\",\"id\":\"51643eac9777b63a\","
        + "\"attributes\":{\"algorithm\":\"ed25519\",\"publicKey\":\"" + ZERO_KEY + "\","
        + "\"status\":\"active\",\"created\":\"2026-01-01T00:00:00Z\"}}");

    assertThat(key.retired()).isNull();
    assertThat(key.isRetired()).isFalse();
  }

  @Test
  void anUnknownAlgorithmOrStatusStillDecodes() throws IOException {
    // Open strings, not enums: a future algorithm must not fail the whole key set and strand every
    // file the account has already signed.
    SigningKey key = decode("{\"type\":\"signing-keys\",\"id\":\"0011223344556677\","
        + "\"attributes\":{\"algorithm\":\"ml-dsa-44\",\"publicKey\":\"AAAA\","
        + "\"status\":\"compromised\"}}");

    assertThat(key.algorithm()).isEqualTo("ml-dsa-44");
    assertThat(key.status()).isEqualTo("compromised");
    assertThat(key.isRetired()).isFalse();
  }

  @Test
  void missingOrNullResourceDecodesToNull() throws IOException {
    assertThat(SigningKey.fromResourceNode(null)).isNull();
    assertThat(decode("null")).isNull();
  }

  @Test
  void pinnedKeyCarriesTheDefaultsItsAccessorsReport() {
    SigningKey key = SigningKey.ed25519("51643eac9777b63a", ZERO_KEY);

    assertThat(key.keyId()).isEqualTo("51643eac9777b63a");
    assertThat(key.algorithm()).isEqualTo(SigningKey.ED25519_ALGORITHM);
    assertThat(key.status()).isEqualTo(SigningKey.ACTIVE_STATUS);
    assertThat(key.publicKey()).isEqualTo(ZERO_KEY);
    assertThat(key.created()).isNull();
    assertThat(key.retired()).isNull();
  }

  @Test
  void equalityIsByIdentityFieldsNotTimestamps() {
    SigningKey first = SigningKey.ed25519("51643eac9777b63a", ZERO_KEY);
    SigningKey second = SigningKey.ed25519("51643eac9777b63a", ZERO_KEY);
    SigningKey other = SigningKey.ed25519("0011223344556677", ZERO_KEY);

    assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    assertThat(first).isNotEqualTo(other);
    assertThat(first).isNotEqualTo("51643eac9777b63a");
    assertThat(first).isEqualTo(first);
  }
}
