package sh.tamga.sdk.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The value types the new endpoint surface introduced, including their null and empty edges. */
class EndpointModelsTest {

  private static JsonNode parse(String json) throws IOException {
    return TamgaJsonMapper.instance().readTree(json);
  }

  // ------------------------------------------------------------- OffsetPage

  @Test
  void offsetPageCopiesItsItemsAndTolerFatesNullList() {
    List<String> source = Arrays.asList("a", "b");
    OffsetPage<String> page = new OffsetPage<>(source, 1, 25, 2, 1);

    assertThat(page.items()).containsExactly("a", "b");
    assertThat(new OffsetPage<String>(null, 0, 0, 0, 0).items()).isEmpty();
  }

  @Test
  void offsetPageReadsTheServersMixedCasingInPageMeta() throws Exception {
    // number/size/total are bare lowercase; total_pages is renamed to totalPages. A decoder that
    // assumes one convention for the whole object reads three fields and misses the fourth.
    OffsetPage<String> page = OffsetPage.fromMetaNode(Collections.singletonList("x"),
        parse("{\"page\":{\"number\":3,\"size\":10,\"total\":97,\"totalPages\":10}}"));

    assertThat(page.number()).isEqualTo(3);
    assertThat(page.size()).isEqualTo(10);
    assertThat(page.total()).isEqualTo(97L);
    assertThat(page.totalPages()).isEqualTo(10);
    assertThat(page.hasNextPage()).isTrue();
  }

  @Test
  void offsetPageReportsNoNextPageOnTheLastOrUnknownPage() throws Exception {
    assertThat(OffsetPage.fromMetaNode(Collections.<String>emptyList(),
        parse("{\"page\":{\"number\":10,\"size\":10,\"total\":97,\"totalPages\":10}}"))
        .hasNextPage()).isFalse();
    assertThat(OffsetPage.fromMetaNode(Collections.<String>emptyList(), parse("{}"))
        .hasNextPage()).isFalse();
    assertThat(OffsetPage.fromMetaNode(Collections.<String>emptyList(), null)
        .total()).isZero();
  }

  // ------------------------------------------------------ MachineListOptions

  @Test
  void machineListOptionsExposeThePageItWillRequest() {
    MachineListOptions options = MachineListOptions.defaults().page(4).size(50);

    assertThat(options.pageNumber()).isEqualTo(4);
    assertThat(options.pageSize()).isEqualTo(50);
    assertThat(MachineListOptions.defaults().pageNumber()).isZero();
    assertThat(MachineListOptions.defaults().pageSize()).isZero();
    // 25 is the SERVER's default for an absent page[size]; this client never omits it, and sends
    // the server maximum when the caller does not choose. The two constants are not the same fact.
    assertThat(MachineListOptions.SERVER_DEFAULT_PAGE_SIZE).isEqualTo(25);
    assertThat(MachineListOptions.MAX_PAGE_SIZE).isEqualTo(100);
    assertThat(MachineListOptions.defaults().toQuery(MachineListOptions.MAX_PAGE_SIZE))
        .containsEntry("page[size]", "100");
  }

  @Test
  void machineListOptionsDropNullAndEmptyFilterValues() {
    Map<String, String> query = MachineListOptions.defaults()
        .licenseIds(Arrays.asList("lic-1", null, "", "lic-2"))
        .platforms(Arrays.asList(null, ""))
        .toQuery(100);

    assertThat(query).containsEntry("filter[license]", "lic-1,lic-2");
    // A list that renders to nothing must not send an empty parameter the server would parse.
    assertThat(query).doesNotContainKey("filter[platform]");
  }

  @Test
  void machineListOptionsWithSingleNullLicenseIdSendNoFilter() {
    assertThat(MachineListOptions.defaults().licenseId(null).toQuery(100))
        .doesNotContainKey("filter[license]");
    assertThat(MachineListOptions.defaults().licenseId("lic-9").toQuery(100))
        .containsEntry("filter[license]", "lic-9");
  }

  @Test
  void machineListOptionsFoldDirectionIntoTheSortParameter() {
    // The server reads a leading '-' as descending and lets it override `order`, so sending both
    // could contradict itself.
    assertThat(MachineListOptions.defaults().sort("name").toQuery(100))
        .containsEntry("sort", "name").doesNotContainKey("order");
    assertThat(MachineListOptions.defaults().sort("name").descending(true).toQuery(100))
        .containsEntry("sort", "-name").doesNotContainKey("order");
    assertThat(MachineListOptions.defaults().descending(true).toQuery(100))
        .containsEntry("order", "desc").doesNotContainKey("sort");
    assertThat(MachineListOptions.defaults().sort("").descending(false).toQuery(100))
        .doesNotContainKey("sort");
  }

  @Test
  void machineListOptionsAlwaysSendBothOffsetParameters() {
    assertThat(MachineListOptions.defaults().toQuery(100))
        .containsEntry("page[number]", "1")
        .containsEntry("page[size]", "100");
    assertThat(MachineListOptions.defaults().page(-3).toQuery(7))
        .containsEntry("page[number]", "1")
        .containsEntry("page[size]", "7");
  }

  // ---------------------------------------------------- UpdateMachineOptions

  @Test
  void updateMachineOptionsOmitUnsetFieldsRatherThanNullingThem() {
    // Omission and an explicit null are indistinguishable to a COALESCE merge, so the shorter body
    // is the honest one -- and neither can clear a column.
    Map<String, Object> body = UpdateMachineOptions.none().withName("n").toRequestBody();

    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) body.get("data");
    @SuppressWarnings("unchecked")
    Map<String, Object> attributes = (Map<String, Object>) data.get("attributes");
    assertThat(data).containsEntry("type", "machines");
    assertThat(attributes).containsOnlyKeys("name");
  }

  @Test
  void updateMachineOptionsCopyMetadataAndAcceptNull() {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("k", "v");
    UpdateMachineOptions options = UpdateMachineOptions.none().withMetadata(metadata);
    metadata.put("k", "mutated");

    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) options.toRequestBody().get("data");
    @SuppressWarnings("unchecked")
    Map<String, Object> attributes = (Map<String, Object>) data.get("attributes");
    @SuppressWarnings("unchecked")
    Map<String, Object> stored = (Map<String, Object>) attributes.get("metadata");
    assertThat(stored).containsEntry("k", "v");

    @SuppressWarnings("unchecked")
    Map<String, Object> cleared = (Map<String, Object>)
        ((Map<String, Object>) UpdateMachineOptions.none().withMetadata(null).toRequestBody()
            .get("data")).get("attributes");
    assertThat(cleared).isEmpty();
  }

  // ---------------------------------------------------------------- Release

  @Test
  void releaseDecodesTheCamelCaseAttributeNames() throws Exception {
    Release release = Release.fromResourceNode(parse(
        "{\"id\":\"rel-1\",\"type\":\"releases\",\"attributes\":{\"productId\":\"prod-1\","
            + "\"version\":\"2.0.0\"}}"));

    assertThat(release.productId()).isEqualTo("prod-1");
    assertThat(release.version()).isEqualTo("2.0.0");
  }

  @Test
  void releaseTreatsSnakeCaseProductIdAsAbsent() throws Exception {
    // Guards the exact mistake this resource invites: the rest of the API is snake_case, this one
    // is not, and reading product_id here silently yields null.
    Release release = Release.fromResourceNode(parse(
        "{\"id\":\"rel-1\",\"attributes\":{\"product_id\":\"prod-1\"}}"));

    assertThat(release.productId()).isNull();
  }

  @Test
  void releaseToleratesAbsentResourceAndAbsentOptionalFields() throws Exception {
    assertThat(Release.fromResourceNode(null)).isNull();
    assertThat(Release.fromResourceNode(parse("null"))).isNull();

    Release bare = Release.fromResourceNode(parse("{\"id\":\"rel-1\",\"attributes\":{}}"));
    assertThat(bare.tag()).isNull();
    assertThat(bare.metadata()).isNull();
    assertThat(bare.name()).isNull();
    assertThat(bare.created()).isNull();
  }

  // ----------------------------------------------------- upgrade check types

  @Test
  void upgradeCheckOptionsExposeWhatTheyAreAsking() {
    UpgradeCheckOptions options =
        UpgradeCheckOptions.of("prod-1", "linux", "deb", "1.2.3", "stable");

    assertThat(options.productId()).isEqualTo("prod-1");
    assertThat(options.version()).isEqualTo("1.2.3");
    assertThat(options.toQuery()).containsEntry("channel", "stable")
        .doesNotContainKey("constraint");
    assertThat(options.withConstraint("").toQuery()).doesNotContainKey("constraint");
    assertThat(options.withConstraint("^1.0.0").toQuery())
        .containsEntry("constraint", "^1.0.0");
  }

  @Test
  void upgradeCheckResultNoneMeansNothingIsAvailableToYou() {
    // Never "you are up to date": 204 also covers "a newer release exists that you may not have".
    assertThat(UpgradeCheckResult.none().updateOffered()).isFalse();
    assertThat(UpgradeCheckResult.none().release()).isNull();
    assertThat(UpgradeCheckResult.of(null).updateOffered()).isFalse();
  }

  @Test
  void upgradeCheckResultOfCarriesTheRelease() throws Exception {
    Release release = Release.fromResourceNode(parse("{\"id\":\"rel-1\",\"attributes\":{}}"));

    UpgradeCheckResult result = UpgradeCheckResult.of(release);

    assertThat(result.updateOffered()).isTrue();
    assertThat(result.release()).isSameAs(release);
  }

  // ----------------------------------------------------------- HealthStatus

  @Test
  void healthStatusDecodesTheFlatBodyAndToleratesAbsentOne() throws Exception {
    HealthStatus health =
        HealthStatus.fromJson(parse("{\"status\":\"ok\",\"version\":\"1\",\"uptime_secs\":9}"));

    assertThat(health.status()).isEqualTo("ok");
    assertThat(health.version()).isEqualTo("1");
    assertThat(health.uptimeSeconds()).isEqualTo(9L);
    assertThat(HealthStatus.fromJson(null)).isNull();
    assertThat(HealthStatus.fromJson(parse("null"))).isNull();
    assertThat(new HealthStatus("ok", "1", 5L).uptimeSeconds()).isEqualTo(5L);
  }

  // ------------------------------------------------------- ActivationOptions

  @Test
  void activationOptionsDefaultToRaisingTheConflict() {
    assertThat(ActivationOptions.defaults().reusesTakenFingerprint()).isFalse();
    assertThat(ActivationOptions.defaults().reuseTakenFingerprint(true).reusesTakenFingerprint())
        .isTrue();
    // Setting the value it already holds returns the same instance rather than allocating.
    assertThat(ActivationOptions.defaults().reuseTakenFingerprint(false))
        .isSameAs(ActivationOptions.defaults());
    ActivationOptions reusing = ActivationOptions.defaults().reuseTakenFingerprint(true);
    assertThat(reusing.reuseTakenFingerprint(true)).isSameAs(reusing);
    assertThat(reusing.reuseTakenFingerprint(false).reusesTakenFingerprint()).isFalse();
  }
}
