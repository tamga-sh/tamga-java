package sh.tamga.sdk.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class NaiveKeyTest {

  @Test
  void deriveZeroPadsLicenseKeyShorterThan32Bytes() {
    byte[] key = NaiveKey.derive("short");

    assertThat(key).hasSize(32);
    assertThat(new String(key, 0, 5, StandardCharsets.UTF_8)).isEqualTo("short");
    for (int i = 5; i < 32; i++) {
      assertThat(key[i]).isZero();
    }
  }

  @Test
  void deriveTruncatesLicenseKeyLongerThan32Bytes() {
    String longKey = "x".repeat(40);
    byte[] key = NaiveKey.derive(longKey);

    assertThat(key).hasSize(32);
    assertThat(key).isEqualTo(Arrays.copyOfRange(longKey.getBytes(StandardCharsets.UTF_8), 0, 32));
  }

  @Test
  void derivePassesThroughLicenseKeyExactly32BytesLong() {
    String exactKey = "x".repeat(32);

    byte[] key = NaiveKey.derive(exactKey);

    assertThat(key).isEqualTo(exactKey.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void deriveNeverHashesTheLicenseKeysOwnBytes() {
    byte[] key = NaiveKey.derive("TAMGA-KEY");
    byte[] expectedPrefix = "TAMGA-KEY".getBytes(StandardCharsets.UTF_8);

    assertThat(Arrays.copyOfRange(key, 0, expectedPrefix.length)).isEqualTo(expectedPrefix);
  }
}
