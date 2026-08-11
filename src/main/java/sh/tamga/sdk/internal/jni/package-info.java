/**
 * JNI binding layer: native library loading and the native method declarations that cross into
 * {@code tamga-c}'s C ABI for the 4 crypto/offline-verification primitives this SDK delegates
 * instead of reimplementing.
 *
 * <p><b>STUB -- scaffolding only.</b> No native calls are wired yet. See {@code
 * docs/plans/tamga-java.plan.md} Section B for the full task breakdown.
 *
 * <p><b>security-reviewer MANDATORY on every change to this package</b> -- it is the only place
 * raw pointers, native buffers, and untrusted native return codes cross into the JVM. See this
 * repository's {@code CLAUDE.md} "Crypto-Boundary Rule" before adding anything here.
 */
package sh.tamga.sdk.internal.jni;
