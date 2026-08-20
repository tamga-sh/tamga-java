package sh.tamga.sdk.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Decoding and value semantics for the resource models the client returns. */
class ResourceModelsTest {

  private static JsonNode node(String json) throws IOException {
    return TamgaJsonMapper.instance().readTree(json);
  }

  @Test
  void entitlementDecodesEveryField() throws Exception {
    Entitlement entitlement = Entitlement.fromResourceNode(node(
        "{\"id\":\"ent-1\",\"type\":\"entitlements\",\"attributes\":{\"code\":\"PRO\","
            + "\"name\":\"Pro plan\",\"created\":\"2026-08-20T10:00:00Z\","
            + "\"updated\":\"2026-08-21T11:00:00Z\",\"metadata\":{\"tier\":\"gold\"}}}"));

    assertThat(entitlement.id()).isEqualTo("ent-1");
    assertThat(entitlement.code()).isEqualTo("PRO");
    assertThat(entitlement.name()).isEqualTo("Pro plan");
    assertThat(entitlement.created()).isEqualTo(Instant.parse("2026-08-20T10:00:00Z"));
    assertThat(entitlement.updated()).isEqualTo(Instant.parse("2026-08-21T11:00:00Z"));
    assertThat(entitlement.metadata()).containsEntry("tier", "gold");
  }

  @Test
  void componentDecodesEveryField() throws Exception {
    Component component = Component.fromResourceNode(node(
        "{\"id\":\"comp-1\",\"type\":\"components\",\"attributes\":{\"fingerprint\":\"cfp\","
            + "\"name\":\"gpu\",\"machine_id\":\"mach-1\",\"created\":\"2026-08-20T10:00:00Z\","
            + "\"updated\":\"2026-08-21T11:00:00Z\",\"metadata\":{\"slot\":\"1\"}}}"));

    assertThat(component.id()).isEqualTo("comp-1");
    assertThat(component.fingerprint()).isEqualTo("cfp");
    assertThat(component.name()).isEqualTo("gpu");
    assertThat(component.machineId()).isEqualTo("mach-1");
    assertThat(component.created()).isNotNull();
    assertThat(component.updated()).isNotNull();
    assertThat(component.metadata()).containsEntry("slot", "1");
  }

  @Test
  void processDecodesEveryField() throws Exception {
    Process process = Process.fromResourceNode(node(
        "{\"id\":\"proc-1\",\"type\":\"processes\",\"attributes\":{\"pid\":\"4242\","
            + "\"machine_id\":\"mach-1\",\"last_heartbeat_at\":\"2026-08-20T10:00:00Z\","
            + "\"created\":\"2026-08-20T09:00:00Z\",\"updated\":\"2026-08-20T10:00:00Z\","
            + "\"metadata\":{\"role\":\"worker\"}}}"));

    assertThat(process.id()).isEqualTo("proc-1");
    assertThat(process.pid()).isEqualTo("4242");
    assertThat(process.machineId()).isEqualTo("mach-1");
    assertThat(process.lastHeartbeatAt()).isNotNull();
    assertThat(process.created()).isNotNull();
    assertThat(process.updated()).isNotNull();
    assertThat(process.metadata()).containsEntry("role", "worker");
  }

  @Test
  void resourcesCompareByIdentity() throws Exception {
    Entitlement first = Entitlement.fromResourceNode(node(
        "{\"id\":\"ent-1\",\"attributes\":{\"code\":\"PRO\",\"name\":\"Pro\"}}"));
    Entitlement same = Entitlement.fromResourceNode(node(
        "{\"id\":\"ent-1\",\"attributes\":{\"code\":\"PRO\",\"name\":\"Pro\"}}"));
    Entitlement other = Entitlement.fromResourceNode(node(
        "{\"id\":\"ent-2\",\"attributes\":{\"code\":\"BETA\",\"name\":\"Beta\"}}"));

    assertThat(first).isEqualTo(same).hasSameHashCodeAs(same);
    assertThat(first).isNotEqualTo(other);
    assertThat(first).isNotEqualTo("not an entitlement");
    assertThat(first).isEqualTo(first);
  }

  @Test
  void componentsAndProcessesCompareByIdentity() throws Exception {
    Component component = Component.fromResourceNode(node(
        "{\"id\":\"comp-1\",\"attributes\":{\"fingerprint\":\"cfp\",\"machine_id\":\"m-1\"}}"));
    Component sameComponent = Component.fromResourceNode(node(
        "{\"id\":\"comp-1\",\"attributes\":{\"fingerprint\":\"cfp\",\"machine_id\":\"m-1\"}}"));
    Process process = Process.fromResourceNode(node(
        "{\"id\":\"proc-1\",\"attributes\":{\"pid\":\"1\",\"machine_id\":\"m-1\"}}"));
    Process sameProcess = Process.fromResourceNode(node(
        "{\"id\":\"proc-1\",\"attributes\":{\"pid\":\"1\",\"machine_id\":\"m-1\"}}"));

    assertThat(component).isEqualTo(sameComponent).hasSameHashCodeAs(sameComponent);
    assertThat(component).isNotEqualTo("not a component");
    assertThat(process).isEqualTo(sameProcess).hasSameHashCodeAs(sameProcess);
    assertThat(process).isNotEqualTo("not a process");
  }

  @Test
  void nullResourceNodeDecodesToNull() {
    assertThat(Entitlement.fromResourceNode(null)).isNull();
    assertThat(Component.fromResourceNode(null)).isNull();
    assertThat(Process.fromResourceNode(null)).isNull();
    assertThat(License.fromResourceNode(null)).isNull();
    assertThat(Machine.fromResourceNode(null)).isNull();
  }

  @Test
  void absentAttributesDecodeToNullRatherThanThrowing() throws Exception {
    Entitlement entitlement = Entitlement.fromResourceNode(node("{\"id\":\"ent-1\"}"));
    Process process = Process.fromResourceNode(node("{\"id\":\"proc-1\"}"));

    assertThat(entitlement.code()).isNull();
    assertThat(entitlement.metadata()).isNull();
    assertThat(process.pid()).isNull();
    assertThat(process.lastHeartbeatAt()).isNull();
  }

  @Test
  void anUnparseableTimestampDecodesToNull() throws Exception {
    // Response decoding must never throw on a field the server reshaped.
    Entitlement entitlement = Entitlement.fromResourceNode(node(
        "{\"id\":\"ent-1\",\"attributes\":{\"created\":\"not-a-timestamp\",\"updated\":null}}"));

    assertThat(entitlement.created()).isNull();
    assertThat(entitlement.updated()).isNull();
  }

  @Test
  void licenseDecodesTheFullApiResource() throws Exception {
    License license = License.fromResourceNode(node(
        "{\"id\":\"lic-1\",\"type\":\"licenses\",\"attributes\":{\"key\":\"K\",\"name\":\"Acme\","
            + "\"status\":\"ACTIVE\",\"scheme\":\"ED25519_SIGN\",\"max_machines\":5,"
            + "\"max_users\":3,\"max_uses\":100,\"machines_count\":2,\"uses\":7,"
            + "\"protected\":true,\"floating\":true,\"strict\":true,\"encrypted\":true,"
            + "\"suspended\":true,\"created\":\"2026-08-20T10:00:00Z\","
            + "\"updated\":\"2026-08-21T10:00:00Z\",\"expiry\":\"2027-08-20T10:00:00Z\","
            + "\"last_check_out_at\":\"2026-08-20T12:00:00Z\"}}"));

    assertThat(license.name()).isEqualTo("Acme");
    assertThat(license.status()).isEqualTo("ACTIVE");
    assertThat(license.scheme()).isEqualTo("ED25519_SIGN");
    assertThat(license.maxMachines()).isEqualTo(5);
    assertThat(license.maxUsers()).isEqualTo(3);
    assertThat(license.maxUses()).isEqualTo(100);
    assertThat(license.machinesCount()).isEqualTo(2);
    assertThat(license.uses()).isEqualTo(7);
    assertThat(license.isProtected()).isTrue();
    assertThat(license.floating()).isTrue();
    assertThat(license.strict()).isTrue();
    assertThat(license.encrypted()).isTrue();
    assertThat(license.suspended()).isTrue();
    assertThat(license.created()).isNotNull();
    assertThat(license.updated()).isNotNull();
    assertThat(license.lastCheckOutAt()).isNotNull();
  }

  @Test
  void machineDecodesTheFullApiResource() throws Exception {
    Machine machine = Machine.fromResourceNode(node(
        "{\"id\":\"mach-1\",\"type\":\"machines\",\"attributes\":{\"fingerprint\":\"fp\","
            + "\"ip\":\"10.0.0.1\",\"hostname\":\"box\",\"cores\":8,\"memory\":17179869184,"
            + "\"disk\":512000000000,\"next_heartbeat_at\":\"2026-08-20T10:10:00Z\","
            + "\"created\":\"2026-08-20T09:00:00Z\",\"updated\":\"2026-08-20T10:00:00Z\"}}"));

    assertThat(machine.ip()).isEqualTo("10.0.0.1");
    assertThat(machine.hostname()).isEqualTo("box");
    assertThat(machine.cores()).isEqualTo(8);
    assertThat(machine.memory()).isEqualTo(17_179_869_184L);
    assertThat(machine.disk()).isEqualTo(512_000_000_000L);
    assertThat(machine.nextHeartbeatAt()).isNotNull();
    assertThat(machine.created()).isNotNull();
    assertThat(machine.updated()).isNotNull();
  }

  @Test
  void metadataIsExposedAsAnUnmodifiableView() throws Exception {
    Entitlement entitlement = Entitlement.fromResourceNode(node(
        "{\"id\":\"ent-1\",\"attributes\":{\"metadata\":{\"tier\":\"gold\"}}}"));
    Map<String, Object> metadata = entitlement.metadata();

    assertThat(metadata).isNotNull();
    org.assertj.core.api.Assertions
        .assertThatThrownBy(() -> metadata.put("injected", "value"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
