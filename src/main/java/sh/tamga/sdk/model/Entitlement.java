package sh.tamga.sdk.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * An entitlement resource.
 *
 * <p>Despite being nested under {@code /licenses/{id}/entitlements}, list and get return full
 * entitlement resources, not lightweight junction records.
 *
 * <p>{@link #code()} is the stable, developer-facing identifier and is what
 * {@code TamgaClient.hasEntitlement} matches on. {@link #name()} is a display label that may
 * collide or change independently -- never match on it.
 *
 * <p>The license listing is a union of directly attached and policy-inherited entitlements, and
 * {@link #inherited()} says which. Only that listing carries the flag; the account-, policy- and
 * release-scoped responses have nothing to inherit from and leave it {@code null}.
 *
 * <p>{@link #kind()} is a required, always-present field: {@code FLAG} is the boolean grant every
 * entitlement used to be, and {@code METER} is a named, per-license counter with an independent
 * cap, replacing the retired global {@code uses}/{@code max_uses} mechanism. {@link #maxValue()}
 * and {@link #currentValue()} are meaningful only for a {@code METER} -- present but not enforced
 * for a {@code FLAG}.
 *
 * <p><b>{@link #maxValue()}</b> is the nullable <em>effective</em> cap: the license's own override
 * if it has one, else the policy's default, else {@code null} = unlimited -- the server's response
 * shape differs by scope. The license-scoped listing (this type's usual source) carries both
 * {@code max_value} and {@code current_value}; the policy-scoped listing carries only
 * {@code max_value} (the policy-level default, no {@code current_value} and no {@code inherited} --
 * usage is never pooled at the policy level).
 *
 * <p><b>{@link #currentValue()}</b> is the running count, {@code 0} if never incremented. A
 * {@code 0} does not necessarily mean "never used": it also means "this entitlement is only
 * inherited from the license's policy and has never been directly attached to this license",
 * because only a direct {@code license_entitlements} row carries a counter at all. Check
 * {@link #inherited()} to tell the two apart when that distinction matters.
 */
public final class Entitlement {

  private final String id;
  private final String name;
  private final String code;
  private final Instant created;
  private final Instant updated;
  private final Map<String, Object> metadata;
  private final Boolean inherited;
  private final Kind kind;
  private final Integer maxValue;
  private final Integer currentValue;

  Entitlement(String id, String name, String code, Instant created, Instant updated,
      Map<String, Object> metadata, Boolean inherited, Kind kind, Integer maxValue,
      Integer currentValue) {
    this.id = id;
    this.name = name;
    this.code = code;
    this.created = created;
    this.updated = updated;
    this.metadata = metadata;
    this.inherited = inherited;
    this.kind = kind;
    this.maxValue = maxValue;
    this.currentValue = currentValue;
  }

  /** Decodes a single {@code {id, type, attributes}} entitlement resource node. */
  public static Entitlement fromResourceNode(JsonNode resource) {
    if (resource == null || resource.isNull()) {
      return null;
    }
    JsonNode attrs = resource.path("attributes");
    return new Entitlement(
        WireNodes.text(resource, "id"),
        WireNodes.text(attrs, "name"),
        WireNodes.text(attrs, "code"),
        WireNodes.instant(attrs, "created"),
        WireNodes.instant(attrs, "updated"),
        WireNodes.objectMap(attrs, "metadata"),
        WireNodes.booleanOrNull(attrs, "inherited"),
        Kind.fromWireValue(WireNodes.text(attrs, "kind")),
        WireNodes.integer(attrs, "max_value"),
        WireNodes.integer(attrs, "current_value"));
  }

  /** Returns the entitlement's unique id. */
  public String id() {
    return id;
  }

  /** Returns the display label. Never match on this -- match on {@link #code()}. */
  public String name() {
    return name;
  }

  /** Returns the stable, developer-facing entitlement code. */
  public String code() {
    return code;
  }

  /** Returns when the entitlement was created, or {@code null}. */
  public Instant created() {
    return created;
  }

  /** Returns when the entitlement was last updated, or {@code null}. */
  public Instant updated() {
    return updated;
  }

  /** Returns an unmodifiable view of arbitrary metadata, or {@code null}. */
  public Map<String, Object> metadata() {
    return metadata == null ? null : Collections.unmodifiableMap(metadata);
  }

  /**
   * Returns whether the license holds this entitlement through its policy rather than directly, or
   * {@code null} on a response that does not carry the flag.
   *
   * <p>Two consequences of {@code true}: the entitlement cannot be detached from the license
   * (detaching answers {@code 403 POLICY_ENTITLEMENT}), and
   * {@code TamgaClient.getEntitlement} answers <b>404</b> for it -- that route resolves direct
   * attachments only. List-then-get-each is not a valid pattern on this resource.
   *
   * <p>The one exception: a {@code METER} may be attached directly to a license even when the same
   * entitlement is also granted via the license's policy, because direct attachment is what
   * creates the per-license {@link #currentValue()} row -- it is not treated as a redundant grant
   * the way a second {@code FLAG} attachment would be.
   */
  public Boolean inherited() {
    return inherited;
  }

  /**
   * Returns the entitlement's kind -- {@link Kind#FLAG}, the boolean grant every entitlement used
   * to be, or {@link Kind#METER}, a named per-license counter. Required and always present on the
   * wire; decodes to {@link Kind#UNKNOWN} rather than throwing if a future server-side kind is
   * added.
   */
  public Kind kind() {
    return kind;
  }

  /**
   * Returns the effective cap for a {@link Kind#METER}, or {@code null} for unlimited.
   * Meaningless -- present but not enforced -- for a {@link Kind#FLAG}. {@code null} on a
   * policy-scoped response means the policy-level default itself is unlimited.
   */
  public Integer maxValue() {
    return maxValue;
  }

  /**
   * Returns the running count for a {@link Kind#METER}, or {@code null} on a response that does
   * not carry it at all (the policy-scoped listing never does; usage is not pooled at the policy
   * level). {@code 0} on a license-scoped response distinguishes "never incremented" from "only
   * inherited, never directly attached" only together with {@link #inherited()} -- see this
   * class's Javadoc.
   */
  public Integer currentValue() {
    return currentValue;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof Entitlement)) {
      return false;
    }
    Entitlement that = (Entitlement) other;
    return Objects.equals(id, that.id) && Objects.equals(code, that.code)
        && Objects.equals(name, that.name);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, code, name);
  }

  /**
   * What an entitlement grants: a boolean flag, or a named counter with an independent cap.
   *
   * <p>Replaces the retired single, global, per-license {@code uses}/{@code max_uses} counter --
   * multiple {@code METER} entitlements can now coexist on one license, each tracked
   * independently.
   */
  public enum Kind {
    /** A boolean grant -- present or not. {@link #maxValue()}/{@link #currentValue()} unused. */
    FLAG("flag"),
    /** A named, per-license counter with an independent cap. */
    METER("meter"),
    /**
     * Fallback for any wire value this SDK release does not recognize. Never sent by the server
     * under this name -- it exists so a kind added server-side after this SDK shipped decodes
     * cleanly instead of throwing.
     */
    UNKNOWN("unknown");

    private final String wireValue;

    Kind(String wireValue) {
      this.wireValue = wireValue;
    }

    /**
     * Maps a raw wire string to a constant, falling back to {@link #UNKNOWN} for anything absent
     * or unrecognized -- decoding must never crash on a kind this SDK doesn't yet know about.
     */
    public static Kind fromWireValue(String wireValue) {
      if (wireValue != null) {
        for (Kind kind : values()) {
          if (kind.wireValue.equals(wireValue)) {
            return kind;
          }
        }
      }
      return UNKNOWN;
    }

    /** Returns this kind's lowercase wire value. */
    public String wireValue() {
      return wireValue;
    }
  }
}
