package sh.tamga.sdk.internal.jni;

/**
 * {@code TamgaNative.java}
 *
 * <p><b>STUB -- scaffolding only.</b> No native method declarations exist yet -- they cannot be
 * written correctly until {@code tamga-c} publishes its v0.1 release with a frozen {@code
 * tamga.h} exported-symbol table (see this repository's plan-file banner). Adding native method
 * signatures here now, ahead of that freeze, is exactly how this binding silently drifts from
 * {@code tamga-c}'s actual ABI -- do not hand-transcribe partial/unstable {@code tamga.h}
 * declarations.
 *
 * <p><b>security-reviewer MANDATORY</b> on every change to this class -- see {@code
 * docs/plans/tamga-java.plan.md} Section B.
 *
 * <p>Intended contents once implemented -- EXACTLY 4 native operations, no more (everything else
 * in this SDK is hand-rolled Java, never delegated to {@code tamga-c}; see this repository's
 * {@code CLAUDE.md} "Crypto-Boundary Rule"):
 *
 * <ol>
 *   <li>{@code static native boolean ed25519Verify(byte[] publicKey, byte[] message, byte[]
 *       signature)} -- binds the Ed25519 verify primitive used by license-file checkout (§F) and,
 *       transitively, machine-file checkout when {@code scheme == ED25519_SIGN} (§G).
 *   <li>{@code static native byte[] aesGcm256Decrypt(byte[] key, byte[] nonce, byte[]
 *       ciphertextAndTag)} -- binds AES-256-GCM open, used by both license-file (§F, naive key
 *       derivation) and machine-file (§G, HKDF-derived key) decryption.
 *   <li>{@code static native byte[] hkdfSha256Derive(byte[] ikm, byte[] salt, byte[] info, int
 *       outputLength)} -- binds HKDF-SHA256, used only by machine-file checkout (§G) to derive the
 *       AES key from {@code salt="tamga:machine-file-key-v1"}, {@code ikm=<license key>}, {@code
 *       info=<machine fingerprint>}.
 *   <li>{@code static native boolean signatureVerify(int scheme, byte[] publicKey, byte[] message,
 *       byte[] signature)} -- multi-algorithm dispatcher covering {@code RSA_2048_PKCS1_SIGN},
 *       {@code RSA_2048_PKCS1_PSS_SIGN}, {@code ECDSA_P256_SIGN} (machine-file checkout, §G) and
 *       the fixed RSA-2048 PKCS#1v1.5/SHA-256 scheme used unconditionally by offline-proof
 *       verification (§I) regardless of the license's own {@code scheme}.
 * </ol>
 *
 * <p>Every native method is intended to be stateless/reentrant and safe to call from multiple
 * threads concurrently (no shared native-side mutable state) -- or, once {@code tamga-c}'s actual
 * concurrency contract is known, this Javadoc must document that contract precisely instead of
 * assuming it.
 *
 * <p>Loaded by {@link NativeLibraryLoader}, never directly via {@code System.loadLibrary}. Public
 * (not package-private) because {@code sh.tamga.sdk.checkout} and {@code sh.tamga.sdk.proof} --
 * separate packages -- call into it directly; it is still an {@code internal} package by
 * convention/Javadoc exclusion, not by Java-level access control, since Java has no
 * cross-package-but-same-module visibility modifier that fits this shape.
 */
public final class TamgaNative {

  private TamgaNative() {
    // Intentionally empty. Implementation deferred to a future session per
    // docs/plans/tamga-java.plan.md Section B, blocked on tamga-c v0.1.
  }
}
