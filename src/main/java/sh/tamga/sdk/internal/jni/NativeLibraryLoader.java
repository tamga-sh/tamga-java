package sh.tamga.sdk.internal.jni;

/**
 * {@code NativeLibraryLoader.java}
 *
 * <p><b>STUB -- scaffolding only.</b> No loading logic exists yet.
 *
 * <p><b>security-reviewer MANDATORY</b> on every change to this class -- see {@code
 * docs/plans/tamga-java.plan.md} Section B.
 *
 * <p>Intended contents once implemented:
 *
 * <ul>
 *   <li>Maps {@code os.name}/{@code os.arch} system properties to one of {@code linux-x86_64},
 *       {@code macos-x86_64}, {@code macos-aarch64}, {@code windows-x86_64} (the 4 directories
 *       under {@code src/main/resources/native/}). Throws a clear {@code
 *       UnsupportedPlatformException} -- not a raw {@code UnsatisfiedLinkError} -- for anything
 *       else (e.g. {@code os.arch=riscv64}), naming the unsupported combination in the message.
 *   <li>Extracts the matched native library from the classpath resource ({@code
 *       /native/{platform}/...}) to a temp file ({@code Files.createTempFile}, {@code
 *       deleteOnExit}) and {@code System.load()}s the ABSOLUTE path -- never {@code
 *       System.loadLibrary}, since the library is resource-embedded and not on {@code
 *       java.library.path}.
 *   <li>An idempotent/thread-safe single-load guard ({@code AtomicBoolean} or a lazy holder) so
 *       repeated {@code TamgaClient} construction doesn't re-extract/re-load per instance.
 *   <li>A version handshake: after load, calls a native version-query function and compares
 *       against the {@code tamga.h} ABI version this JAR was built against, throwing {@code
 *       TamgaNativeVersionMismatchException} on mismatch instead of silently proceeding.
 * </ul>
 *
 * <p>Android AAR + NDK cross-compile ({@code arm64-v8a}/{@code armeabi-v7a}/{@code x86_64}) is
 * explicit backlog -- v0.1 resolves desktop-JVM platform strings only. Do not add Android
 * platform-detection branches until that work is actually scheduled.
 *
 * <p>Public (not package-private) because {@code sh.tamga.sdk.TamgaClient} (a different package)
 * triggers the load; see {@link TamgaNative}'s Javadoc for why this package is "internal" by
 * convention rather than by Java access control.
 */
public final class NativeLibraryLoader {

  private NativeLibraryLoader() {
    // Intentionally empty. Implementation deferred to a future session per
    // docs/plans/tamga-java.plan.md Section B, blocked on tamga-c v0.1.
  }
}
