package sh.tamga.sdk.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MachineTest {

  private static Machine makeMachine(String fingerprint) {
    return new Machine("mach_1", fingerprint, null, null, HeartbeatStatus.NOT_STARTED, null, null,
        null);
  }

  @Test
  void equalMachinesCompareEqual() {
    assertThat(makeMachine("fp-1")).isEqualTo(makeMachine("fp-1"));
    assertThat(makeMachine("fp-1").hashCode()).isEqualTo(makeMachine("fp-1").hashCode());
  }

  @Test
  void differingMachinesCompareUnequal() {
    assertThat(makeMachine("fp-1")).isNotEqualTo(makeMachine("fp-2"));
  }

  @Test
  void machineIsNotEqualToUnrelatedType() {
    assertThat(makeMachine("fp-1")).isNotEqualTo("not a machine");
  }

  @Test
  void parseResourcePayloadHandlesMissingAttributes() throws Exception {
    String json = "{\"data\":{\"id\":\"mach_999\",\"type\":\"machines\"}}";

    Machine machine = Machine.parseResourcePayload(json.getBytes(StandardCharsets.UTF_8));

    assertThat(machine.id()).isEqualTo("mach_999");
    assertThat(machine.fingerprint()).isNull();
    assertThat(machine.heartbeatStatus()).isEqualTo(HeartbeatStatus.NOT_STARTED);
    assertThat(machine.metadata()).isNull();
  }
}
