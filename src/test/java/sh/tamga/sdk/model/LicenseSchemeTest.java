package sh.tamga.sdk.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LicenseSchemeTest {

  @Test
  void fromWireValueMapsEveryKnownWireStringToItsCase() {
    assertThat(LicenseScheme.fromWireValue("ED25519_SIGN")).isEqualTo(LicenseScheme.ED25519_SIGN);
    assertThat(LicenseScheme.fromWireValue("RSA_2048_PKCS1_SIGN"))
        .isEqualTo(LicenseScheme.RSA_2048_PKCS1_SIGN);
    assertThat(LicenseScheme.fromWireValue("RSA_2048_PKCS1_PSS_SIGN"))
        .isEqualTo(LicenseScheme.RSA_2048_PKCS1_PSS_SIGN);
    assertThat(LicenseScheme.fromWireValue("ECDSA_P256_SIGN"))
        .isEqualTo(LicenseScheme.ECDSA_P256_SIGN);
    assertThat(LicenseScheme.fromWireValue("RSA_2048_JWT_RS256"))
        .isEqualTo(LicenseScheme.RSA_2048_JWT_RS256);
  }

  @Test
  void fromWireValueMapsNullAndEmptyStringToNone() {
    assertThat(LicenseScheme.fromWireValue(null)).isEqualTo(LicenseScheme.NONE);
    assertThat(LicenseScheme.fromWireValue("")).isEqualTo(LicenseScheme.NONE);
  }

  @Test
  void fromWireValueFallsBackToNoneForUnrecognizedStringWithoutThrowing() {
    assertThat(LicenseScheme.fromWireValue("SOME_FUTURE_SCHEME")).isEqualTo(LicenseScheme.NONE);
  }
}
