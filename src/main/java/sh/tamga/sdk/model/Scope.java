package sh.tamga.sdk.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional constraints sent as {@code meta.scope} on a validate-by-id request. Every field is
 * optional; an unset field means "no constraint, skip this check" and is omitted from the request
 * body entirely rather than sent as null.
 *
 * <p><b>Six fields are enforced server-side:</b> {@code product}, {@code policy}, {@code user},
 * {@code environment}, {@code fingerprint} and {@code entitlements}. The last two used to be
 * parsed and ignored and are now genuinely checked, which makes
 * {@link ValidationCode#FINGERPRINT_SCOPE_MISMATCH} and
 * {@link ValidationCode#ENTITLEMENTS_MISSING} reachable verdicts.
 *
 * <p><b>{@code version} and {@code checksum} are refused outright.</b> The server answers
 * {@code 422 SCOPE_NOT_SUPPORTED} the moment either is present, before any validation runs, so the
 * caller gets no verdict at all rather than an ignored constraint. This class therefore no longer
 * sends them: {@link #withVersion} and {@link #withChecksum} are deprecated and their values are
 * dropped at serialization, which degrades a caller who sets one to a working validate rather than
 * a hard failure.
 *
 * <p>Instances are immutable; each {@code with*} method returns a new object.
 */
public final class Scope {

  private final String product;
  private final String policy;
  private final String user;
  private final String environment;
  private final String fingerprint;
  private final String version;
  private final String checksum;
  private final List<String> entitlements;

  private Scope(String product, String policy, String user, String environment, String fingerprint,
      String version, String checksum, List<String> entitlements) {
    this.product = product;
    this.policy = policy;
    this.user = user;
    this.environment = environment;
    this.fingerprint = fingerprint;
    this.version = version;
    this.checksum = checksum;
    this.entitlements = entitlements;
  }

  /** Returns an empty scope that constrains nothing. */
  public static Scope none() {
    return new Scope(null, null, null, null, null, null, null, null);
  }

  /** Returns a copy constrained to the given product id. Enforced server-side. */
  public Scope withProduct(String value) {
    return new Scope(value, policy, user, environment, fingerprint, version, checksum,
        entitlements);
  }

  /** Returns a copy constrained to the given policy id. Enforced server-side. */
  public Scope withPolicy(String value) {
    return new Scope(product, value, user, environment, fingerprint, version, checksum,
        entitlements);
  }

  /** Returns a copy constrained to the given user id. Enforced server-side. */
  public Scope withUser(String value) {
    return new Scope(product, policy, value, environment, fingerprint, version, checksum,
        entitlements);
  }

  /** Returns a copy constrained to the given environment id. Enforced server-side. */
  public Scope withEnvironment(String value) {
    return new Scope(product, policy, user, value, fingerprint, version, checksum, entitlements);
  }

  /**
   * Returns a copy constrained to the given machine fingerprint. <b>Enforced</b> server-side: it
   * must match some machine of the license, whatever that machine's heartbeat status, or
   * validation answers {@link ValidationCode#FINGERPRINT_SCOPE_MISMATCH}.
   */
  public Scope withFingerprint(String value) {
    return new Scope(product, policy, user, environment, value, version, checksum, entitlements);
  }

  /**
   * Returns a copy carrying a version.
   *
   * @deprecated The server rejects the entire validate call with {@code 422 SCOPE_NOT_SUPPORTED}
   *     when this field is present, so the value is deliberately <b>not sent</b>. Setting it has
   *     no effect. Retained so existing call sites keep compiling.
   */
  @Deprecated
  public Scope withVersion(String value) {
    return new Scope(product, policy, user, environment, fingerprint, value, checksum,
        entitlements);
  }

  /**
   * Returns a copy carrying a checksum.
   *
   * @deprecated The server rejects the entire validate call with {@code 422 SCOPE_NOT_SUPPORTED}
   *     when this field is present, so the value is deliberately <b>not sent</b>. Setting it has
   *     no effect. Retained so existing call sites keep compiling.
   */
  @Deprecated
  public Scope withChecksum(String value) {
    return new Scope(product, policy, user, environment, fingerprint, version, value,
        entitlements);
  }

  /**
   * Returns a copy requiring the license to hold every one of these entitlement <b>codes</b>, not
   * entitlement ids. <b>Enforced</b> server-side: the comparison is case-insensitive and
   * de-duplicated, policy-inherited entitlements count, and an empty list asserts nothing. A
   * license missing any of them validates as {@link ValidationCode#ENTITLEMENTS_MISSING}.
   */
  public Scope withEntitlements(List<String> values) {
    return new Scope(product, policy, user, environment, fingerprint, version, checksum,
        values == null ? null : new ArrayList<>(values));
  }

  /**
   * Returns whether every field is unset.
   *
   * <p>Note this still counts the two unsendable fields: a scope carrying only a {@code version}
   * is not empty, yet renders to an empty map. Decide on {@link #toRequestMap} being empty rather
   * than on this method when the question is "is there anything to send".
   */
  public boolean isEmpty() {
    return product == null && policy == null && user == null && environment == null
        && fingerprint == null && version == null && checksum == null
        && (entitlements == null || entitlements.isEmpty());
  }

  /**
   * Renders this scope as the request-body map, omitting every unset field. Returns an empty map
   * when nothing is set -- or when only {@code version}/{@code checksum} are set; callers omit the
   * {@code scope} key entirely in that case.
   *
   * <p>{@code version} and {@code checksum} are never rendered: sending either makes the server
   * refuse the whole validate call with {@code 422 SCOPE_NOT_SUPPORTED}, so dropping them turns a
   * total failure into a validate that simply does not apply that constraint.
   */
  public Map<String, Object> toRequestMap() {
    Map<String, Object> map = new LinkedHashMap<>();
    putIfPresent(map, "product", product);
    putIfPresent(map, "policy", policy);
    putIfPresent(map, "user", user);
    putIfPresent(map, "environment", environment);
    putIfPresent(map, "fingerprint", fingerprint);
    if (entitlements != null && !entitlements.isEmpty()) {
      map.put("entitlements", Collections.unmodifiableList(new ArrayList<>(entitlements)));
    }
    return map;
  }

  private static void putIfPresent(Map<String, Object> map, String key, String value) {
    if (value != null) {
      map.put(key, value);
    }
  }
}
