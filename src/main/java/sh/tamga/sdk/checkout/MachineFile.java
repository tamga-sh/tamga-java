package sh.tamga.sdk.checkout;

/**
 * {@code MachineFile.java}
 *
 * <p><b>STUB -- scaffolding only.</b> No implementation yet.
 *
 * <p>Offline machine file parse/verify/decrypt -- {@code docs/sdk.md} §6. Same boundary-crossing
 * correctness class as {@link LicenseFile}, plus scheme-dispatch and HKDF derivation add extra
 * surface for subtle mistakes. A <b>security-reviewer pass is MANDATORY</b> before this section is
 * marked done (see {@code docs/plans/tamga-java.plan.md} Section G).
 *
 * <p>File format: PEM-style wrapper {@code -----BEGIN MACHINE FILE-----} / {@code -----END
 * MACHINE FILE-----}, reusing the same inner {@code {enc, sig, alg}} JSON structure as {@link
 * LicenseFile} -- extract the shared PEM/JSON parsing into a small internal helper rather than
 * duplicating it (DRY per {@code ecc:java-coding-standards}).
 *
 * <p>Key differences from {@link LicenseFile}:
 *
 * <ul>
 *   <li><b>Signing scheme is dynamic</b>, taken from the license's {@code scheme} field
 *       (Ed25519/RSA-PKCS1/RSA-PSS/ECDSA-P256), not hardcoded to Ed25519. Dispatches to {@code
 *       TamgaNative.signatureVerify(scheme, ...)} with the matching scheme constant. {@code
 *       RSA_2048_JWT_RS256} is explicitly rejected for machine files -- verify must throw a typed
 *       exception BEFORE attempting a native call, since the server itself rejects this scheme
 *       ({@code 422 SCHEME_NOT_SUPPORTED}). Do not implement a JWT verification path for this
 *       scheme.
 *   <li><b>Encryption key derivation is a real KDF</b> (unlike {@link LicenseFile}'s naive
 *       zero-pad/truncate): {@code TamgaNative.hkdfSha256Derive(ikm=licenseKey.getBytes(UTF_8),
 *       salt="tamga:machine-file-key-v1".getBytes(UTF_8),
 *       info=machineFingerprint.getBytes(UTF_8), outputLength=32)}. The literal salt string is
 *       load-bearing -- a typo there silently breaks every decrypt. Flag it in a code comment at
 *       the exact call site when implemented, not just here.
 *   <li>{@code decrypt(...)} requires BOTH the license key and the target machine's fingerprint
 *       (unlike {@code LicenseFile.decrypt}, which needs only the license key) -- document this
 *       asymmetry prominently once implemented; it's an easy copy-paste mistake from {@link
 *       LicenseFile}.
 * </ul>
 *
 * <p>{@code ttlSeconds} for checkout must be client-side pre-validated ({@code > 0 && <=
 * 31536000}, i.e. 365 days) before the network call, mirroring the server's {@code 422
 * TTL_INVALID} rule -- this validation lives on the client ({@code TamgaClient.checkOutMachine*},
 * §G task list), not in this class, but {@code MachineFile} consumers should not assume an
 * out-of-range TTL was caught upstream.
 *
 * <p>Intended public API: {@code MachineFile.parse(String pemText)}, {@code
 * verify(License license, byte[] publicKey)}, {@code decrypt(String licenseKey, String
 * machineFingerprint)}, {@code getData()}, and a convenience full-pipeline {@code
 * open(pemText, license, publicKey, licenseKeyOrNull, fingerprintOrNull)}.
 */
public final class MachineFile {

  private MachineFile() {
    // Intentionally empty. Implementation deferred to a future session per
    // docs/plans/tamga-java.plan.md Section G, blocked on tamga-c v0.1.
  }
}
