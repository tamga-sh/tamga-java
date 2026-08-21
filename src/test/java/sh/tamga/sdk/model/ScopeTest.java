package sh.tamga.sdk.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Scope rendering: unset fields are omitted, not sent as null. */
class ScopeTest {

  @Test
  void anUntouchedScopeIsEmpty() {
    assertThat(Scope.none().isEmpty()).isTrue();
    assertThat(Scope.none().toRequestMap()).isEmpty();
  }

  @Test
  void onlyPopulatedFieldsAreRendered() {
    Map<String, Object> map = Scope.none().withProduct("prod-1").withPolicy("pol-2").toRequestMap();

    assertThat(map).containsOnlyKeys("product", "policy");
    assertThat(map).containsEntry("product", "prod-1");
  }

  @Test
  void theFourEnforcedFieldsRender() {
    Map<String, Object> map = Scope.none()
        .withProduct("p").withPolicy("q").withUser("u").withEnvironment("e")
        .toRequestMap();

    assertThat(map).containsOnlyKeys("product", "policy", "user", "environment");
  }

  @Test
  void theTwoNewlyEnforcedFieldsRender() {
    // fingerprint and entitlements are genuinely checked now, so they must reach the server.
    Map<String, Object> map = Scope.none()
        .withFingerprint("fp").withEntitlements(Arrays.asList("PRO"))
        .toRequestMap();

    assertThat(map).containsOnlyKeys("fingerprint", "entitlements");
  }

  @Test
  @SuppressWarnings("deprecation")
  void versionAndChecksumAreNeverSent() {
    // Sending either makes the server refuse the entire validate call with 422
    // SCOPE_NOT_SUPPORTED before running any check. Dropping them degrades a caller who sets one
    // to a working validate that simply does not apply that constraint, rather than to no verdict
    // at all.
    Map<String, Object> map = Scope.none()
        .withProduct("prod-1").withVersion("1.2.3").withChecksum("abc")
        .toRequestMap();

    assertThat(map).containsOnlyKeys("product");
  }

  @Test
  @SuppressWarnings("deprecation")
  void scopeCarryingOnlyUnsendableFieldsRendersToNothing() {
    assertThat(Scope.none().withVersion("1.2.3").withChecksum("abc").toRequestMap()).isEmpty();
  }

  @Test
  void anEmptyEntitlementListIsOmitted() {
    assertThat(Scope.none().withEntitlements(Arrays.asList()).toRequestMap()).isEmpty();
    assertThat(Scope.none().withEntitlements(null).isEmpty()).isTrue();
  }

  @Test
  void scopesAreImmutableUnderDerivation() {
    Scope base = Scope.none().withProduct("prod-1");
    Scope derived = base.withUser("user-1");

    assertThat(base.toRequestMap()).containsOnlyKeys("product");
    assertThat(derived.toRequestMap()).containsOnlyKeys("product", "user");
  }

  @Test
  void anEntitlementListIsCopiedSoLaterMutationCannotLeakIn() {
    List<String> codes = new ArrayList<>(Arrays.asList("PRO"));
    Scope scope = Scope.none().withEntitlements(codes);

    codes.add("SNEAKED_IN");

    assertThat(scope.toRequestMap())
        .containsEntry("entitlements", Collections.singletonList("PRO"));
  }
}
