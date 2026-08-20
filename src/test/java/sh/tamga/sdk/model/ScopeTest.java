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
  void theFourUnenforcedFieldsStillRenderForForwardCompatibility() {
    // Sent, parsed server-side, then ignored. Modelled so a future server that honours them needs
    // no SDK change, but never advertised as a working constraint.
    Map<String, Object> map = Scope.none()
        .withFingerprint("fp").withVersion("1.2.3").withChecksum("abc")
        .withEntitlements(Arrays.asList("PRO"))
        .toRequestMap();

    assertThat(map).containsOnlyKeys("fingerprint", "version", "checksum", "entitlements");
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
