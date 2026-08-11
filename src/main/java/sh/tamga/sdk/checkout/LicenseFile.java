package sh.tamga.sdk.checkout;

/**
 * {@code LicenseFile.java}
 *
 * <p><b>STUB -- scaffolding only.</b> No implementation yet.
 *
 * <p>Offline license file ({@code .lic}) parse/verify/decrypt -- {@code docs/sdk.md} §4. This is
 * the highest-stakes file in the whole SDK; a <b>security-reviewer pass is MANDATORY</b> before
 * this section is marked done (see {@code docs/plans/tamga-java.plan.md} Section F).
 *
 * <p>File format: PEM-style wrapper {@code -----BEGIN LICENSE FILE-----} / {@code -----END
 * LICENSE FILE-----} around base64 of {@code { "enc": "<base64>", "sig": "<base64 ed25519 sig>",
 * "alg": "<algorithm string>" }}.
 *
 * <p>{@code alg} is always exactly {@code "base64+ed25519"} (plain) or {@code
 * "aes-256-gcm+ed25519"} (encrypted) -- Ed25519 ONLY for the checkout signature, independent of
 * the license's own key {@code scheme}.
 *
 * <p>Intended verification pipeline, EXACT order (crypto delegates to {@code
 * sh.tamga.sdk.internal.jni.TamgaNative} -- this class itself must stay pure Java, no direct
 * crypto):
 *
 * <ol>
 *   <li>Strip PEM markers, base64-decode the outer envelope.
 *   <li>Parse the resulting {@code {enc, sig, alg}} JSON.
 *   <li>Base64-decode {@code sig}.
 *   <li><b>CRITICAL:</b> Ed25519-verify {@code sig} against {@code enc}'s ASCII/UTF-8 bytes OF THE
 *       BASE64 STRING ITSELF -- NOT the decoded bytes. This is the single most load-bearing gotcha
 *       in the whole checkout flow; flag it in a code comment at the exact call site (not just
 *       here) when implemented, since it is the one place a naive "decode-then-verify"
 *       reimplementation would silently produce a verifier that rejects every valid file.
 *   <li>Base64-decode {@code enc} to get the actual payload bytes.
 *   <li>If {@code alg} contains {@code "aes-256-gcm"}: split nonce(12B)/ciphertext+tag(16B) and
 *       AES-256-GCM-open via {@code TamgaNative.aesGcm256Decrypt} using the license-key-derived
 *       key.
 *   <li>Parse the resulting bytes as {@code {"data": <License>}} JSON.
 * </ol>
 *
 * <p><b>CRITICAL:</b> the AES key (when encrypted) is the license key's raw UTF-8 bytes,
 * zero-padded or truncated to exactly 32 bytes -- this is explicitly NOT a hash or KDF. A verifier
 * that runs the key through SHA-256 or any other KDF will silently produce the wrong key and fail
 * to decrypt. (Contrast with {@link MachineFile}, which DOES use a real HKDF-SHA256 -- don't let
 * the two derivations bleed into each other.)
 *
 * <p>{@code includes} is always {@code []} today -- do not build a "checkout with embedded
 * relationships" feature around it. Response {@code id} is a fresh UUIDv7 per call, not
 * idempotent -- two checkout calls yield two different certificates. {@code ttl}/{@code expiry}
 * are metadata only, NOT embedded in the signed payload and NOT re-checked server-side on any
 * later validation -- offline-file expiry enforcement (an {@code isExpired(Clock)} method) is
 * entirely this SDK's client-side responsibility.
 *
 * <p>Intended public API: {@code LicenseFile.parse(String pemText)}, {@code
 * verify(byte[] accountPublicKey)}, {@code decrypt(String licenseKey)}, {@code getData()}, and a
 * convenience full-pipeline {@code open(pemText, accountPublicKey, licenseKeyOrNull)}.
 */
public final class LicenseFile {

  private LicenseFile() {
    // Intentionally empty. Implementation deferred to a future session per
    // docs/plans/tamga-java.plan.md Section F, blocked on tamga-c v0.1.
  }
}
