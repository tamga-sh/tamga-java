package sh.tamga.sdk.proof;

/**
 * {@code OfflineProof.java}
 *
 * <p><b>STUB -- scaffolding only.</b> No implementation yet.
 *
 * <p>Machine offline proof (air-gapped verification) -- {@code docs/sdk.md} §7.
 *
 * <p>Intended contents once implemented (crypto delegates to {@code
 * sh.tamga.sdk.internal.jni.TamgaNative} -- this class itself must stay pure Java):
 *
 * <ul>
 *   <li>Parses {@code meta.proof = "v1x0.<base64 signature>"}: splits the version prefix ({@code
 *       v1x0}) from the base64-encoded RSA signature, exposes both, and rejects an unrecognized
 *       version prefix with a typed exception.
 *   <li>{@code verify(UUID accountId, UUID machineId, String fingerprint, Map<String, Object>
 *       dataset, byte[] rsaPublicKey)} -- reconstructs the EXACT signed payload {@code
 *       {"account":{"id":...},"machine":{"id":...,"fingerprint":...},"dataset":<dataset>}} and
 *       calls {@code TamgaNative.signatureVerify} with the FIXED RSA-2048 PKCS#1 v1.5/SHA-256
 *       scheme -- ALWAYS this scheme, regardless of the license's own {@code scheme} (unlike
 *       {@code sh.tamga.sdk.checkout.MachineFile}, there is no scheme dispatch here).
 * </ul>
 *
 * <p><b>CRITICAL:</b> payload reconstruction must use an explicit ordered serializer ({@code
 * LinkedHashMap} plus a canonical Jackson writer configuration, or a hand-built JSON string
 * builder) rather than a plain Jackson POJO relying on field-declaration or alphabetical ordering
 * -- field order matters for the signature to validate, and reflection-based serialization order
 * is not a contract Jackson guarantees across versions. The exact key order observed from the live
 * server is {@code account.id} → {@code machine.id} → {@code machine.fingerprint} → {@code
 * dataset}; re-verify against {@code tamga-c}'s reference serializer once available, since this is
 * reproduced by convention today, not by a shared schema. This is the same class of bug as the
 * base64-string-vs-decoded-bytes trap in {@code sh.tamga.sdk.checkout.LicenseFile} -- a
 * functionally "equivalent" JSON payload with different key order will fail server-side
 * verification.
 */
public final class OfflineProof {

  private OfflineProof() {
    // Intentionally empty. Implementation deferred to a future session per
    // docs/plans/tamga-java.plan.md Section I, blocked on tamga-c v0.1.
  }
}
