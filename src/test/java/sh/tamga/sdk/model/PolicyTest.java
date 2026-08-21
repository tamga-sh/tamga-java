package sh.tamga.sdk.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Policy decoding, with emphasis on the two bogus real-world defaults. */
class PolicyTest {

  private static Policy parse(String attributes) throws IOException {
    JsonNode node = TamgaJsonMapper.instance()
        .readTree("{\"id\":\"pol-1\",\"type\":\"policies\",\"attributes\":" + attributes + "}");
    return Policy.fromResourceNode(node);
  }

  @Test
  void freshPolicyBogusOverageDefaultNormalizesToNoOverage() throws Exception {
    // Freshly created policies really do report this string, and it is not a real variant. Read
    // literally it looks maximally restrictive; the server treats it as no restriction at all.
    Policy policy = parse("{\"overage_strategy\":\"DENY_ACCESS\"}");

    assertThat(policy.overageStrategyRaw()).isEqualTo("DENY_ACCESS");
    assertThat(policy.effectiveOverageStrategy())
        .isEqualTo(Policy.OverageStrategy.NO_OVERAGE);
  }

  @Test
  void freshPolicyBogusResurrectionDefaultNormalizesToNoRevive() throws Exception {
    Policy policy = parse("{\"heartbeat_resurrection_strategy\":\"NO_RESURRECTION\"}");

    assertThat(policy.heartbeatResurrectionStrategyRaw()).isEqualTo("NO_RESURRECTION");
    assertThat(policy.effectiveResurrectionStrategy())
        .isEqualTo(Policy.HeartbeatResurrectionStrategy.NO_REVIVE);
  }

  @Test
  void realOverageVariantsSurviveNormalization() throws Exception {
    assertThat(parse("{\"overage_strategy\":\"ALLOW_1_5X_OVERAGE\"}").effectiveOverageStrategy())
        .isEqualTo(Policy.OverageStrategy.ALLOW_1_5X_OVERAGE);
    assertThat(parse("{\"overage_strategy\":\"ALWAYS_ALLOW_OVERAGE\"}").effectiveOverageStrategy())
        .isEqualTo(Policy.OverageStrategy.ALWAYS_ALLOW_OVERAGE);
  }

  @Test
  void overageMultipliersMatchTheServerArithmetic() {
    assertThat(Policy.OverageStrategy.NO_OVERAGE.allows(10, 10)).isTrue();
    assertThat(Policy.OverageStrategy.NO_OVERAGE.allows(11, 10)).isFalse();

    assertThat(Policy.OverageStrategy.ALLOW_1_25X_OVERAGE.allows(12, 10)).isTrue();
    assertThat(Policy.OverageStrategy.ALLOW_1_25X_OVERAGE.allows(13, 10)).isFalse();

    assertThat(Policy.OverageStrategy.ALLOW_1_5X_OVERAGE.allows(15, 10)).isTrue();
    assertThat(Policy.OverageStrategy.ALLOW_1_5X_OVERAGE.allows(16, 10)).isFalse();

    assertThat(Policy.OverageStrategy.ALLOW_2X_OVERAGE.allows(20, 10)).isTrue();
    assertThat(Policy.OverageStrategy.ALLOW_2X_OVERAGE.allows(21, 10)).isFalse();

    assertThat(Policy.OverageStrategy.ALWAYS_ALLOW_OVERAGE.allows(Long.MAX_VALUE, 1)).isTrue();
  }

  @Test
  void checkInIntervalWireValuesAreLowercaseAdverbs() throws Exception {
    // The one casing exception in an otherwise uppercase protocol -- and the spelling is the
    // adverb the server's own accepted set uses ("daily"), not the bare noun this SDK expected
    // before any policy read existed to disprove it. A real policy decoded to null under the old
    // mapping.
    assertThat(parse("{\"check_in_interval\":\"monthly\"}").checkInInterval())
        .isEqualTo(Policy.CheckInInterval.MONTH);
    assertThat(parse("{\"check_in_interval\":\"daily\"}").checkInInterval())
        .isEqualTo(Policy.CheckInInterval.DAY);
    assertThat(parse("{\"check_in_interval\":\"weekly\"}").checkInInterval())
        .isEqualTo(Policy.CheckInInterval.WEEK);
    assertThat(parse("{\"check_in_interval\":\"yearly\"}").checkInInterval())
        .isEqualTo(Policy.CheckInInterval.YEAR);
    assertThat(Policy.CheckInInterval.DAY.wireValue()).isEqualTo("daily");
    assertThat(parse("{\"check_in_interval\":\"MONTHLY\"}").checkInInterval()).isNull();
  }

  @Test
  void checkInIntervalStillAcceptsTheNounSpellingOnInput() throws Exception {
    // Tolerated so a value persisted against the earlier, incorrect mapping keeps parsing. The
    // server never emits these.
    assertThat(parse("{\"check_in_interval\":\"month\"}").checkInInterval())
        .isEqualTo(Policy.CheckInInterval.MONTH);
    assertThat(parse("{\"check_in_interval\":\"day\"}").checkInInterval())
        .isEqualTo(Policy.CheckInInterval.DAY);
    assertThat(parse("{\"check_in_interval\":\"nonsense\"}").checkInInterval()).isNull();
  }

  @Test
  void theEffectiveHeartbeatWindowFallsBackToTenMinutes() throws Exception {
    // Mirrors Policy::effective_heartbeat_duration_secs -- the policy value, else 600.
    assertThat(parse("{}").effectiveHeartbeatWindow()).isEqualTo(Duration.ofSeconds(600));
    assertThat(parse("{\"heartbeat_duration\":null}").effectiveHeartbeatWindow())
        .isEqualTo(Policy.DEFAULT_HEARTBEAT_WINDOW);
    assertThat(parse("{\"heartbeat_duration\":120}").effectiveHeartbeatWindow())
        .isEqualTo(Duration.ofSeconds(120));
  }

  @Test
  void theCullStrategyDefaultsToDeactivating() throws Exception {
    assertThat(parse("{}").effectiveCullStrategy())
        .isEqualTo(Policy.HeartbeatCullStrategy.DEACTIVATE_DEAD);
    assertThat(parse("{\"heartbeat_cull_strategy\":\"KEEP_DEAD\"}").effectiveCullStrategy())
        .isEqualTo(Policy.HeartbeatCullStrategy.KEEP_DEAD);
  }

  @Test
  void scalarAttributesDecode() throws Exception {
    Policy policy = parse("{\"name\":\"Pro\",\"product_id\":\"prod-1\",\"max_machines\":5,"
        + "\"max_cores\":8,\"duration\":31536000,\"require_check_in\":true,"
        + "\"require_heartbeat\":true,\"protected\":true,\"floating\":true,"
        + "\"heartbeat_duration\":900,\"created\":\"2026-08-20T10:00:00Z\"}");

    assertThat(policy.id()).isEqualTo("pol-1");
    assertThat(policy.name()).isEqualTo("Pro");
    assertThat(policy.productId()).isEqualTo("prod-1");
    assertThat(policy.maxMachines()).isEqualTo(5);
    assertThat(policy.maxCores()).isEqualTo(8);
    assertThat(policy.duration()).isEqualTo(31_536_000L);
    assertThat(policy.requireCheckIn()).isTrue();
    assertThat(policy.requireHeartbeat()).isTrue();
    assertThat(policy.isProtected()).isTrue();
    assertThat(policy.floating()).isTrue();
    assertThat(policy.strict()).isFalse();
    assertThat(policy.created()).isNotNull();
    // This is the effective heartbeat window; the server falls back to 600s only when it is null.
    assertThat(policy.heartbeatDuration()).isEqualTo(900);
  }

  @Test
  void absentAndNullAttributesDecodeToNullRatherThanThrowing() throws Exception {
    Policy policy = parse("{\"max_machines\":null,\"created\":\"not-a-timestamp\"}");

    assertThat(policy.maxMachines()).isNull();
    assertThat(policy.maxUses()).isNull();
    assertThat(policy.created()).isNull();
    assertThat(policy.metadata()).isNull();
  }

  @Test
  void everyModelledAttributeIsReadable() throws Exception {
    // The full attribute set a policy can carry, so each accessor is exercised
    // against a real decode rather than left to rot behind an untested getter.
    Policy policy = parse("{\"name\":\"Pro\",\"product_id\":\"prod-1\","
        + "\"scheme\":\"ED25519_SIGN\",\"max_machines\":5,\"max_cores\":8,"
        + "\"max_processes\":16,\"max_users\":4,\"max_uses\":100,\"duration\":31536000,"
        + "\"heartbeat_duration\":900,\"check_in_interval\":\"week\","
        + "\"check_in_interval_count\":2,\"overage_strategy\":\"ALLOW_2X_OVERAGE\","
        + "\"heartbeat_cull_strategy\":\"KEEP_DEAD\","
        + "\"heartbeat_resurrection_strategy\":\"5_MINUTE_REVIVE\","
        + "\"machine_uniqueness_strategy\":\"UNIQUE_PER_LICENSE\","
        + "\"expiration_strategy\":\"MAINTAIN_ACCESS\",\"expiration_basis\":\"FROM_CREATION\","
        + "\"renewal_basis\":\"FROM_NOW\",\"authentication_strategy\":\"MIXED\","
        + "\"use_pool\":true,\"encrypted\":true,\"require_check_in\":true,"
        + "\"require_heartbeat\":true,\"protected\":true,\"floating\":true,\"strict\":true,"
        + "\"created\":\"2026-08-20T10:00:00Z\",\"updated\":\"2026-08-21T10:00:00Z\","
        + "\"metadata\":{\"tier\":\"gold\"}}");

    assertThat(policy.scheme()).isEqualTo("ED25519_SIGN");
    assertThat(policy.maxProcesses()).isEqualTo(16);
    assertThat(policy.maxUsers()).isEqualTo(4);
    assertThat(policy.maxUses()).isEqualTo(100);
    assertThat(policy.checkInIntervalCount()).isEqualTo(2);
    assertThat(policy.checkInInterval()).isEqualTo(Policy.CheckInInterval.WEEK);
    assertThat(policy.heartbeatCullStrategyRaw()).isEqualTo("KEEP_DEAD");
    assertThat(policy.heartbeatResurrectionStrategyRaw()).isEqualTo("5_MINUTE_REVIVE");
    assertThat(policy.effectiveResurrectionStrategy())
        .isEqualTo(Policy.HeartbeatResurrectionStrategy.REVIVE_5_MINUTE);
    assertThat(policy.machineUniquenessStrategy()).isEqualTo("UNIQUE_PER_LICENSE");
    assertThat(policy.expirationStrategy()).isEqualTo(Policy.ExpirationStrategy.MAINTAIN_ACCESS);
    assertThat(policy.expirationBasis()).isEqualTo("FROM_CREATION");
    assertThat(policy.renewalBasis()).isEqualTo(Policy.RenewalBasis.FROM_NOW);
    assertThat(policy.authenticationStrategy())
        .isEqualTo(Policy.AuthenticationStrategy.MIXED);
    assertThat(policy.usePool()).isTrue();
    assertThat(policy.encrypted()).isTrue();
    assertThat(policy.updated()).isNotNull();
    assertThat(policy.metadata()).containsEntry("tier", "gold");
  }

  @Test
  void policyMetadataIsExposedAsUnmodifiableView() throws Exception {
    Policy policy = parse("{\"metadata\":{\"tier\":\"gold\"}}");
    java.util.Map<String, Object> metadata = policy.metadata();

    org.assertj.core.api.Assertions
        .assertThatThrownBy(() -> metadata.put("injected", "value"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void theDocumentedFreeTextConstantsAreTheServersOwn() {
    // Free text server-side, so these are constants rather than an enum -- but
    // the values still have to match what the server actually emits.
    assertThat(Policy.ExpirationStrategy.RESTRICT_ACCESS).isEqualTo("RESTRICT_ACCESS");
    assertThat(Policy.ExpirationStrategy.ALLOW_ACCESS).isEqualTo("ALLOW_ACCESS");
    assertThat(Policy.RenewalBasis.FROM_EXPIRY).isEqualTo("FROM_EXPIRY");
    assertThat(Policy.AuthenticationStrategy.TOKEN).isEqualTo("TOKEN");
    assertThat(Policy.AuthenticationStrategy.LICENSE).isEqualTo("LICENSE");
    assertThat(Policy.HeartbeatResurrectionStrategy.ALWAYS_REVIVE.wireValue())
        .isEqualTo("ALWAYS_REVIVE");
  }

  @Test
  void nullResourceNodeDecodesToNull() {
    assertThat(Policy.fromResourceNode(null)).isNull();
  }
}
