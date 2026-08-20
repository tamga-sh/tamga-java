package sh.tamga.sdk.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The request-side option types: what each field renders to on the wire. */
class RequestOptionsTest {

  @SuppressWarnings("unchecked")
  private static Map<String, Object> attributesOf(Map<String, Object> body) {
    Map<String, Object> data = (Map<String, Object>) body.get("data");
    return (Map<String, Object>) data.get("attributes");
  }

  @Test
  void createMachineOptionsRenderEveryOptionalField() {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("team", "platform");

    Map<String, Object> body = CreateMachineOptions.of("fp-1", "lic-1")
        .withName("build box")
        .withIp("10.0.0.1")
        .withHostname("box")
        .withPlatform("linux")
        .withCores(8)
        .withMemory(17_179_869_184L)
        .withDisk(512_000_000_000L)
        .withMetadata(metadata)
        .toRequestBody();

    Map<String, Object> attributes = attributesOf(body);
    assertThat(attributes).containsEntry("fingerprint", "fp-1");
    assertThat(attributes).containsEntry("name", "build box");
    assertThat(attributes).containsEntry("ip", "10.0.0.1");
    assertThat(attributes).containsEntry("hostname", "box");
    assertThat(attributes).containsEntry("platform", "linux");
    assertThat(attributes).containsEntry("cores", 8);
    assertThat(attributes).containsEntry("memory", 17_179_869_184L);
    assertThat(attributes).containsEntry("disk", 512_000_000_000L);
    assertThat(attributes).containsEntry("metadata", metadata);
  }

  @Test
  void createMachineOptionsAreImmutableUnderDerivation() {
    CreateMachineOptions base = CreateMachineOptions.of("fp-1", "lic-1");
    CreateMachineOptions derived = base.withHostname("box");

    assertThat(attributesOf(base.toRequestBody()).get("hostname")).isNull();
    assertThat(attributesOf(derived.toRequestBody())).containsEntry("hostname", "box");
    assertThat(base.fingerprint()).isEqualTo("fp-1");
    assertThat(base.licenseId()).isEqualTo("lic-1");
  }

  @Test
  void machineMetadataIsCopiedSoLaterMutationCannotLeakIn() {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("team", "platform");
    CreateMachineOptions options = CreateMachineOptions.of("fp-1", "lic-1")
        .withMetadata(metadata);

    metadata.put("sneaked", "in");

    assertThat((Map<String, Object>) attributesOf(options.toRequestBody()).get("metadata"))
        .containsOnlyKeys("team");
  }

  @Test
  void componentOptionsRenderFlatBody() {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("slot", "1");

    Map<String, Object> body = CreateComponentOptions.of("mach-1", "cfp", "gpu")
        .withMetadata(metadata)
        .toRequestBody();

    assertThat(body).containsOnlyKeys("machine_id", "fingerprint", "name", "metadata");
    assertThat(body).containsEntry("machine_id", "mach-1");
    assertThat(body).containsEntry("fingerprint", "cfp");
    assertThat(body).containsEntry("name", "gpu");
    assertThat(body).containsEntry("metadata", metadata);
  }

  @Test
  void processOptionsRenderFlatBodyWithStringPid() {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("role", "worker");

    Map<String, Object> body = CreateProcessOptions.of("mach-1", "4242")
        .withMetadata(metadata)
        .toRequestBody();

    assertThat(body).containsOnlyKeys("machine_id", "pid", "metadata");
    assertThat(body.get("pid")).isInstanceOf(String.class).isEqualTo("4242");
    assertThat(CreateProcessOptions.of("mach-1", "1").machineId()).isEqualTo("mach-1");
    assertThat(CreateComponentOptions.of("mach-1", "cfp", "gpu").machineId()).isEqualTo("mach-1");
  }

  @Test
  void metadataDefaultsToAnEmptyObjectRatherThanNull() {
    assertThat(attributesOf(CreateMachineOptions.of("fp", "lic").toRequestBody()))
        .containsEntry("metadata", new LinkedHashMap<String, Object>());
    assertThat(CreateComponentOptions.of("m", "f", "n").toRequestBody())
        .containsEntry("metadata", new LinkedHashMap<String, Object>());
    assertThat(CreateProcessOptions.of("m", "1").toRequestBody())
        .containsEntry("metadata", new LinkedHashMap<String, Object>());
    assertThat(CreateMachineOptions.of("fp", "lic").withMetadata(null).toRequestBody()).isNotNull();
  }

  @Test
  void checkOutOptionsCarryTheirSettings() {
    CheckOutOptions options = CheckOutOptions.defaults()
        .withTtl(3600)
        .withEncrypt(true)
        .withUsePost(true);

    assertThat(options.ttl()).isEqualTo(3600);
    assertThat(options.encrypt()).isTrue();
    assertThat(options.usingPost()).isTrue();
    assertThat(CheckOutOptions.defaults().ttl()).isNull();
    assertThat(CheckOutOptions.defaults().encrypt()).isFalse();
    assertThat(CheckOutOptions.defaults().usingPost()).isFalse();
  }

  @Test
  void validateOptionsCarryTheirSettings() {
    Scope scope = Scope.none().withProduct("prod-1");
    ValidateOptions options = ValidateOptions.defaults().withScope(scope).withSkipTouch(true);

    assertThat(options.scope()).isSameAs(scope);
    assertThat(options.skipTouch()).isTrue();
    assertThat(ValidateOptions.defaults().scope()).isNull();
    assertThat(ValidateOptions.defaults().skipTouch()).isFalse();
  }

  @Test
  void listOptionsCarryTheirSettings() {
    ListOptions options = ListOptions.ofLimit(25).after("cursor-9");

    assertThat(options.pageSize()).isEqualTo(25);
    assertThat(options.afterCursor()).isEqualTo("cursor-9");
    assertThat(ListOptions.defaults().pageSize()).isZero();
    assertThat(ListOptions.defaults().afterCursor()).isNull();
    assertThat(ListOptions.defaults().limit(10).pageSize()).isEqualTo(10);
  }

  @Test
  void resultTypesExposeTheirParts() {
    ValidationMeta meta = ValidationMeta.fromJson(
        TamgaJsonMapper.instance().createObjectNode().put("valid", true).put("code", "VALID"));
    License license = License.fromResourceNode(
        TamgaJsonMapper.instance().createObjectNode().put("id", "lic-1"));
    Machine machine = Machine.fromResourceNode(
        TamgaJsonMapper.instance().createObjectNode().put("id", "mach-1"));

    ValidationResult validation = new ValidationResult(license, meta);
    assertThat(validation.license()).isSameAs(license);
    assertThat(validation.meta()).isSameAs(meta);
    assertThat(validation.valid()).isTrue();
    assertThat(new ValidationResult(license, null).valid()).isFalse();

    ActivationResult activation = new ActivationResult(machine, meta);
    assertThat(activation.machine()).isSameAs(machine);
    assertThat(activation.meta()).isSameAs(meta);

    OfflineProofResult proof = new OfflineProofResult(machine, "v1x0.abc");
    assertThat(proof.machine()).isSameAs(machine);
    assertThat(proof.proof()).isEqualTo("v1x0.abc");
  }

  @Test
  void pageCopiesItsItemsAndExposesThemUnmodifiably() {
    java.util.List<String> items = new java.util.ArrayList<>();
    items.add("a");
    Page<String> page = new Page<>("cursor-1", items);

    items.add("sneaked-in");

    assertThat(page.items()).containsExactly("a");
    assertThat(page.nextCursor()).isEqualTo("cursor-1");
    assertThat(new Page<String>(null, null).items()).isEmpty();
    org.assertj.core.api.Assertions
        .assertThatThrownBy(() -> page.items().add("injected"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
