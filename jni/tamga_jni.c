/*
 * jni/tamga_jni.c
 *
 * STUB -- scaffolding only. No real JNI glue implemented yet. This translation unit exists so the
 * native build target is syntactically valid; the empty bodies below are placeholders for the
 * native methods declared in `TamgaNative.java` (real bodies land in
 * docs/plans/tamga-java.plan.md Section B, blocked on tamga-c v0.1 publishing a frozen tamga.h).
 *
 * security-reviewer MANDATORY on every change to this file once real bodies are added -- see
 * this repository's CLAUDE.md "Crypto-Boundary Rule". In particular, every real implementation
 * must:
 *   - marshal jbyteArray <-> uint8_t* via GetByteArrayElements/ReleaseByteArrayElements (or
 *     GetPrimitiveArrayCritical if profiling justifies it), releasing on ALL exit paths including
 *     early-return error paths;
 *   - bounds/null-check every jbyteArray argument before dereferencing (reject zero-length
 *     keys/nonces/signatures with a Java exception, not a native crash);
 *   - translate tamga-c error codes into a Java-visible signal (Throw/ThrowNew a
 *     TamgaNativeException, or an out-param status) -- never let a native crash propagate as the
 *     JVM's only diagnostic.
 */

#include <jni.h>

/*
 * Placeholder native method stubs. Exact JNI method names/signatures cannot be finalized until
 * `TamgaNative.java`'s native method declarations are written against tamga-c's frozen v0.1
 * tamga.h (see that file's Javadoc). No `JNIEXPORT`/`JNIEXPORT` symbols are declared here yet to
 * avoid committing to a signature ahead of that freeze.
 */
