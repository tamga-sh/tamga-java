package sh.tamga.sdk.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HkdfTest {

  @Test
  void deriveMachineFileKeyProducesA32ByteKey() {
    byte[] key = Hkdf.deriveMachineFileKey("TAMGA-LICENSE-KEY", "machine-fingerprint");

    assertThat(key).hasSize(32);
  }

  @Test
  void deriveMachineFileKeyIsDeterministicForTheSameInputs() {
    byte[] key1 = Hkdf.deriveMachineFileKey("TAMGA-LICENSE-KEY", "fp");
    byte[] key2 = Hkdf.deriveMachineFileKey("TAMGA-LICENSE-KEY", "fp");

    assertThat(key1).isEqualTo(key2);
  }

  @Test
  void deriveMachineFileKeyDiffersWhenTheLicenseKeyDiffers() {
    byte[] key1 = Hkdf.deriveMachineFileKey("KEY-ONE", "fp");
    byte[] key2 = Hkdf.deriveMachineFileKey("KEY-TWO", "fp");

    assertThat(key1).isNotEqualTo(key2);
  }

  @Test
  void deriveMachineFileKeyDiffersWhenTheFingerprintDiffers() {
    byte[] key1 = Hkdf.deriveMachineFileKey("KEY", "fingerprint-one");
    byte[] key2 = Hkdf.deriveMachineFileKey("KEY", "fingerprint-two");

    assertThat(key1).isNotEqualTo(key2);
  }

  @Test
  void theTwoDerivationsNeverCollide() {
    // GOTCHA regression: both formats use HKDF-SHA256, so the only thing keeping them apart is
    // the salt and info -- a change that accidentally aligned those would silently let one file
    // type decrypt as the other. The two must never produce the same key for the same license key.
    byte[] machineFileKey = Hkdf.deriveMachineFileKey("SAME-LICENSE-KEY", "fp");
    byte[] licenseFileKey = Hkdf.deriveLicenseFileKey("SAME-LICENSE-KEY");

    assertThat(machineFileKey).isNotEqualTo(licenseFileKey);
  }

  @Test
  void deriveHandlesAnEmptySaltPerRfc5869() {
    // RFC 5869: an absent/empty salt is treated as HashLen zero bytes, not
    // rejected outright -- SecretKeySpec itself rejects a zero-length key
    // array, so this exercises the internal zero-fill fallback.
    byte[] ikm = "ikm".getBytes(StandardCharsets.UTF_8);
    byte[] info = "info".getBytes(StandardCharsets.UTF_8);
    byte[] derived = Hkdf.derive(ikm, new byte[0], info, 32);

    assertThat(derived).hasSize(32);
  }

  @Test
  void deriveSupportsOutputLongerThanOneHashBlock() {
    // 32-byte hash blocks -- 50 bytes requires 2 expand iterations.
    byte[] ikm = "ikm".getBytes(StandardCharsets.UTF_8);
    byte[] salt = "salt".getBytes(StandardCharsets.UTF_8);
    byte[] info = "info".getBytes(StandardCharsets.UTF_8);
    byte[] derived = Hkdf.derive(ikm, salt, info, 50);

    assertThat(derived).hasSize(50);
  }
}
