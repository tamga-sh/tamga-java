package sh.tamga.sdk.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The validation-code vocabulary, including which values the server can actually emit. */
class ValidationCodeTest {

  @Test
  void allTwentyFourWireValuesAreModelledPlusTheUnknownFallback() {
    assertThat(ValidationCode.values()).hasSize(25);
  }

  @Test
  void exactlyFourteenCodesAreReachable() {
    long reachable = 0;
    for (ValidationCode code : ValidationCode.values()) {
      if (code.reachable()) {
        reachable++;
      }
    }

    assertThat(reachable).isEqualTo(14);
  }

  @Test
  void unreachableCodesAreFlaggedAsSuch() {
    // The handler returns a bare HTTP 404 rather than emitting NOT_FOUND, and the four scope
    // fields these correspond to are parsed server-side but never checked.
    assertThat(ValidationCode.NOT_FOUND.reachable()).isFalse();
    assertThat(ValidationCode.BANNED.reachable()).isFalse();
    assertThat(ValidationCode.FINGERPRINT_SCOPE_MISMATCH.reachable()).isFalse();
    assertThat(ValidationCode.CHECKSUM_SCOPE_MISMATCH.reachable()).isFalse();
    assertThat(ValidationCode.VERSION_SCOPE_MISMATCH.reachable()).isFalse();
    assertThat(ValidationCode.COMPONENTS_SCOPE_MISMATCH.reachable()).isFalse();
    assertThat(ValidationCode.UNKNOWN.reachable()).isFalse();
  }

  @Test
  void theOverLimitSetIsExactlyTheFiveResourceLimits() {
    assertThat(ValidationCode.TOO_MANY_MACHINES.overLimit()).isTrue();
    assertThat(ValidationCode.TOO_MANY_CORES.overLimit()).isTrue();
    assertThat(ValidationCode.TOO_MUCH_MEMORY.overLimit()).isTrue();
    assertThat(ValidationCode.TOO_MUCH_DISK.overLimit()).isTrue();
    assertThat(ValidationCode.TOO_MANY_PROCESSES.overLimit()).isTrue();

    // Uses are compared strictly and are never subject to an overage strategy, so exceeding them
    // is not a rollback trigger.
    assertThat(ValidationCode.TOO_MANY_USES.overLimit()).isFalse();
    assertThat(ValidationCode.VALID.overLimit()).isFalse();
    assertThat(ValidationCode.EXPIRED.overLimit()).isFalse();
  }

  @Test
  void knownWireValuesRoundTrip() {
    assertThat(ValidationCode.fromWireValue("VALID")).isEqualTo(ValidationCode.VALID);
    assertThat(ValidationCode.fromWireValue("TOO_MANY_MACHINES"))
        .isEqualTo(ValidationCode.TOO_MANY_MACHINES);
  }

  @Test
  void anUnknownOrMissingWireValueDecodesLeniently() {
    // A server-side addition must never break a released SDK.
    assertThat(ValidationCode.fromWireValue("INVENTED_NEXT_YEAR"))
        .isEqualTo(ValidationCode.UNKNOWN);
    assertThat(ValidationCode.fromWireValue(null)).isEqualTo(ValidationCode.UNKNOWN);
    assertThat(ValidationCode.fromWireValue("")).isEqualTo(ValidationCode.UNKNOWN);
  }
}
