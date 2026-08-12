package sh.tamga.sdk.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HeartbeatStatusTest {

  @Test
  void fromWireValueMapsEveryKnownWireStringToItsCase() {
    assertThat(HeartbeatStatus.fromWireValue("NOT_STARTED")).isEqualTo(HeartbeatStatus.NOT_STARTED);
    assertThat(HeartbeatStatus.fromWireValue("ALIVE")).isEqualTo(HeartbeatStatus.ALIVE);
    assertThat(HeartbeatStatus.fromWireValue("DEAD")).isEqualTo(HeartbeatStatus.DEAD);
    assertThat(HeartbeatStatus.fromWireValue("RESURRECTED")).isEqualTo(HeartbeatStatus.RESURRECTED);
  }

  @Test
  void fromWireValueMapsNullToNotStarted() {
    assertThat(HeartbeatStatus.fromWireValue(null)).isEqualTo(HeartbeatStatus.NOT_STARTED);
  }

  @Test
  void fromWireValueFallsBackToNotStartedForUnrecognizedStringWithoutThrowing() {
    HeartbeatStatus status = HeartbeatStatus.fromWireValue("SOME_FUTURE_STATUS");

    assertThat(status).isEqualTo(HeartbeatStatus.NOT_STARTED);
  }
}
