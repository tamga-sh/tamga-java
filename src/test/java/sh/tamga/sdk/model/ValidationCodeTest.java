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
  void exactlySixteenCodesAreReachable() {
    long reachable = 0;
    for (ValidationCode code : ValidationCode.values()) {
      if (code.reachable()) {
        reachable++;
      }
    }

    assertThat(reachable).isEqualTo(16);
  }

  @Test
  void theTwoNewlyEnforcedScopeCodesAreReachable() {
    // scope.entitlements and scope.fingerprint used to be parsed and ignored. They are now
    // genuinely checked, so these two verdicts really do arrive.
    assertThat(ValidationCode.ENTITLEMENTS_MISSING.reachable()).isTrue();
    assertThat(ValidationCode.FINGERPRINT_SCOPE_MISMATCH.reachable()).isTrue();
  }

  @Test
  void unreachableCodesAreFlaggedAsSuch() {
    // The handler returns a bare HTTP 404 rather than emitting NOT_FOUND. The version/checksum
    // codes are unreachable for the opposite reason to before: setting either scope field now
    // fails the whole call with 422 SCOPE_NOT_SUPPORTED, so no verdict is ever produced.
    assertThat(ValidationCode.NOT_FOUND.reachable()).isFalse();
    assertThat(ValidationCode.BANNED.reachable()).isFalse();
    assertThat(ValidationCode.CHECKSUM_SCOPE_MISMATCH.reachable()).isFalse();
    assertThat(ValidationCode.VERSION_SCOPE_MISMATCH.reachable()).isFalse();
    assertThat(ValidationCode.COMPONENTS_SCOPE_MISMATCH.reachable()).isFalse();
    assertThat(ValidationCode.UNKNOWN.reachable()).isFalse();
  }

  @Test
  void createTimeLimitCodesMapOntoTheValidationVocabulary() {
    // POST /machines rejects with its own four names; validation reports the same four conditions
    // under different ones. activateMachine reports one outcome, so the mapping has to exist.
    assertThat(ValidationCode.fromMachineLimitErrorCode("MACHINE_LIMIT_EXCEEDED"))
        .isEqualTo(ValidationCode.TOO_MANY_MACHINES);
    assertThat(ValidationCode.fromMachineLimitErrorCode("CORE_LIMIT_EXCEEDED"))
        .isEqualTo(ValidationCode.TOO_MANY_CORES);
    assertThat(ValidationCode.fromMachineLimitErrorCode("MEMORY_LIMIT_EXCEEDED"))
        .isEqualTo(ValidationCode.TOO_MUCH_MEMORY);
    assertThat(ValidationCode.fromMachineLimitErrorCode("DISK_LIMIT_EXCEEDED"))
        .isEqualTo(ValidationCode.TOO_MUCH_DISK);
  }

  @Test
  void nonLimitErrorCodesMapToNothing() {
    // FINGERPRINT_TAKEN is checked before the limits and is not one: translating it would report
    // "buy more seats" for a machine that is already activated.
    assertThat(ValidationCode.fromMachineLimitErrorCode("FINGERPRINT_TAKEN")).isNull();
    assertThat(ValidationCode.fromMachineLimitErrorCode("UNAUTHORIZED")).isNull();
    assertThat(ValidationCode.fromMachineLimitErrorCode(null)).isNull();
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
