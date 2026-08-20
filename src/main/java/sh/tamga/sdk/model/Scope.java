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
 * <p><b>Only {@code product}, {@code policy}, {@code user} and {@code environment} are enforced
 * server-side.</b> The remaining four ({@code fingerprint}, {@code version}, {@code checksum},
 * {@code entitlements}) are parsed and then silently ignored. They are modeled here for
 * forward-compatibility -- never document or surface them as constraints that currently work.
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

  /** Returns a copy carrying a fingerprint. Sent but <b>not enforced</b> server-side. */
  public Scope withFingerprint(String value) {
    return new Scope(product, policy, user, environment, value, version, checksum, entitlements);
  }

  /** Returns a copy carrying a version. Sent but <b>not enforced</b> server-side. */
  public Scope withVersion(String value) {
    return new Scope(product, policy, user, environment, fingerprint, value, checksum,
        entitlements);
  }

  /** Returns a copy carrying a checksum. Sent but <b>not enforced</b> server-side. */
  public Scope withChecksum(String value) {
    return new Scope(product, policy, user, environment, fingerprint, version, value,
        entitlements);
  }

  /** Returns a copy carrying entitlement codes. Sent but <b>not enforced</b> server-side. */
  public Scope withEntitlements(List<String> values) {
    return new Scope(product, policy, user, environment, fingerprint, version, checksum,
        values == null ? null : new ArrayList<>(values));
  }

  /** Returns whether every field is unset, in which case {@code scope} is omitted entirely. */
  public boolean isEmpty() {
    return product == null && policy == null && user == null && environment == null
        && fingerprint == null && version == null && checksum == null
        && (entitlements == null || entitlements.isEmpty());
  }

  /**
   * Renders this scope as the request-body map, omitting every unset field. Returns an empty map
   * when nothing is set; callers omit the {@code scope} key entirely in that case.
   */
  public Map<String, Object> toRequestMap() {
    Map<String, Object> map = new LinkedHashMap<>();
    putIfPresent(map, "product", product);
    putIfPresent(map, "policy", policy);
    putIfPresent(map, "user", user);
    putIfPresent(map, "environment", environment);
    putIfPresent(map, "fingerprint", fingerprint);
    putIfPresent(map, "version", version);
    putIfPresent(map, "checksum", checksum);
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
