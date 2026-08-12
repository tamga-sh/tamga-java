package sh.tamga.sdk.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class LicenseTest {

  private static License makeLicense(String key) {
    return new License("lic_1", key, false, null, 0, null, null, null);
  }

  @Test
  void equalLicensesCompareEqual() {
    assertThat(makeLicense("KEY-1")).isEqualTo(makeLicense("KEY-1"));
    assertThat(makeLicense("KEY-1").hashCode()).isEqualTo(makeLicense("KEY-1").hashCode());
  }

  @Test
  void differingLicensesCompareUnequal() {
    assertThat(makeLicense("KEY-1")).isNotEqualTo(makeLicense("KEY-2"));
  }

  @Test
  void licenseIsNotEqualToUnrelatedType() {
    assertThat(makeLicense("KEY-1")).isNotEqualTo("not a license");
  }

  @Test
  void parseResourcePayloadHandlesMissingAttributes() throws Exception {
    String json = "{\"data\":{\"id\":\"lic_999\",\"type\":\"licenses\"}}";

    License license = License.parseResourcePayload(json.getBytes(StandardCharsets.UTF_8));

    assertThat(license.id()).isEqualTo("lic_999");
    assertThat(license.key()).isNull();
    assertThat(license.suspended()).isFalse();
    assertThat(license.metadata()).isNull();
  }
}
