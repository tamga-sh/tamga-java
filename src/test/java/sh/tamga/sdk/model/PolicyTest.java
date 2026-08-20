package sh.tamga.sdk.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
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
  void checkInIntervalWireValuesAreLowercase() throws Exception {
    // The one casing exception in an otherwise uppercase protocol.
    assertThat(parse("{\"check_in_interval\":\"month\"}").checkInInterval())
        .isEqualTo(Policy.CheckInInterval.MONTH);
    assertThat(Policy.CheckInInterval.DAY.wireValue()).isEqualTo("day");
    assertThat(parse("{\"check_in_interval\":\"MONTH\"}").checkInInterval()).isNull();
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
    // Present on the wire, but the server ignores it: the heartbeat window is a fixed 600s.
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
  void nullResourceNodeDecodesToNull() {
    assertThat(Policy.fromResourceNode(null)).isNull();
  }
}
