package sh.tamga.sdk.model;

/**
 * {@code License.java}
 *
 * <p><b>STUB -- scaffolding only.</b> No implementation yet.
 *
 * <p>Intended contents once implemented: an immutable resource model matching the JSON:API
 * {@code licenses} resource shape from {@code docs/sdk.md} §2/§4 -- attributes ({@code key},
 * {@code name}, {@code expiry}, {@code suspended}, {@code scheme}, {@code uses}, ...) plus the
 * {@code meta} block returned alongside validation responses. Deserialized by both {@code
 * TamgaClient}'s validation/check-in/checkout responses (§D-F) and {@code
 * sh.tamga.sdk.checkout.LicenseFile#getData()} (§F), which parses an embedded {@code
 * {"data": <License>}} payload out of a verified/decrypted offline file -- both paths must
 * deserialize to the exact same type.
 *
 * <p>Follows the immutable-model convention from {@code ecc:java-coding-standards}: no setters,
 * construction via a builder or all-args constructor, {@code Optional}-typed accessors only for
 * fields that are genuinely absent-vs-null on the wire (not for every nullable field).
 */
public final class License {

  private License() {
    // Intentionally empty. Implementation deferred to a future session per
    // docs/plans/tamga-java.plan.md Section D.
  }
}
