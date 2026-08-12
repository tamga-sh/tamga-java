# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

`tamga-java` is the official Java SDK for Tamga (`sh.tamga:tamga-sdk` on Maven Central) — license
activation, offline license/machine verification, and machine/component/process management for
Java and (eventually) Android applications. It is one of two SDKs (with `tamga-swift`) that do not
reimplement Tamga's cryptographic verification logic natively; instead it binds to `tamga-c`, the
Rust reference implementation exposed through a stable C ABI, via JNI. Full task breakdown and
current build status: [`docs/plans/tamga-java.plan.md`](docs/plans/tamga-java.plan.md). Protocol/
feature spec this SDK is built against — every field name, endpoint, and enum value comes from
here: [`tamga-api/docs/sdk.md`](https://github.com/tamga-sh/tamga-api/blob/main/docs/sdk.md).

**Current state: scaffold only.** Gradle build, module layout, CI/release workflow shape, and
doc-comment stub classes exist. No HTTP transport, no JNI/crypto wiring, no business logic. Do not
assume any method on `TamgaClient` does anything yet — see the Javadoc at the top of each stub
class for what it will eventually do. `./gradlew check` still fails at the JaCoCo coverage gate
locally (0% — there is no real code to cover yet); that is expected and will self-resolve once
Section D onward lands real logic with real tests. **The 80% minimum itself must never be loosened
to work around this** — CI instead skips the Checkstyle/SpotBugs/`check` steps entirely for
PRs that touch only `.md` files (see `.github/workflows/ci.yml`'s "Detect doc-only changes" step),
since a coverage gate has nothing meaningful to say about a diff containing no code. Any PR that
touches `src/`, `jni/`, or the build config still runs the real gate at the real 80% threshold.

## Crypto-Boundary Rule (read before touching `internal/jni/`)

Only **four** operations cross the JNI boundary into `tamga-c`:

1. Ed25519 verify (license checkout signature check)
2. AES-256-GCM open (license file decrypt)
3. HKDF-SHA256 derive (machine file decrypt key derivation)
4. Multi-scheme verify — Ed25519/RSA-PKCS1/RSA-PSS/ECDSA-P256 (machine checkout) and RSA-PKCS1v15
   (offline proof)

**Everything else is hand-rolled, idiomatic Java.** HTTP transport is built directly on OkHttp —
`tamga-c` is never used for networking, JSON:API decoding, or the public client API surface. This
mirrors `tamga-swift` (its FFI layer wraps the same 4 crypto ops; the rest is plain Swift on
`URLSession`). If you find yourself calling `TamgaNative` from outside `internal/jni`,
`checkout/`, or `proof/`, stop — those are the only three packages allowed to touch it.

## Architecture

```
tamga-java/
├── settings.gradle.kts                     # rootProject.name = "tamga-sdk", single module
├── build.gradle.kts                        # group = "sh.tamga", artifactId = "tamga-sdk"
├── gradle/wrapper/                         # pinned Gradle wrapper (8.14.5; build JDK = Temurin 17)
├── config/
│   ├── checkstyle/google_checks.xml        # Google Java Style, wired via the checkstyle plugin
│   └── spotbugs/exclude.xml                # near-empty; see file header before adding excludes
├── src/
│   ├── main/
│   │   ├── java/sh/tamga/sdk/
│   │   │   ├── TamgaClient.java            # entry point; builder requires accountId + baseUrl
│   │   │   ├── Transport.java              # OkHttp-based transport — hand-rolled, NOT tamga-c
│   │   │   ├── model/                      # ValidationCode, License, Machine, Policy, ...
│   │   │   ├── internal/jni/               # TamgaNative + NativeLibraryLoader (public, "internal"
│   │   │   │                               #   by convention only — see TamgaNative's Javadoc)
│   │   │   ├── checkout/                   # LicenseFile, MachineFile — PEM parse/verify/decrypt
│   │   │   ├── proof/                      # OfflineProof — RSA-2048 PKCS#1v1.5, exact-order payload
│   │   │   └── error/                      # TamgaError + TamgaApiException and typed subclasses
│   │   └── resources/native/               # built cdylib/dll artifacts land here per platform
│   │       ├── linux-x86_64/               # (empty in v0.1 scaffold — see README in each dir)
│   │       ├── macos-x86_64/
│   │       ├── macos-aarch64/
│   │       └── windows-x86_64/
│   └── test/java/sh/tamga/sdk/             # JUnit 5 + AssertJ + Mockito
├── jni/                                     # C JNI glue — lives HERE, not in tamga-c
│   ├── CMakeLists.txt                      # links against tamga-c's cdylib (once it exists)
│   └── tamga_jni.c                         # implements the 4 crypto native methods
└── .github/workflows/
    ├── ci.yml                              # checkstyle + spotbugs + check (JUnit5/JaCoCo) + codecov
    └── release.yml                         # release-please + publishToMavenCentral on release
```

There is no server here and no `tamga-web`-equivalent binary — `tamga-sdk` is the one artifact
consumers depend on; `jni/` is a native build step feeding its resources, not a separate published
module.

## Dev Commands

```bash
./gradlew build              # compile + package (sources jar + javadoc jar via withSourcesJar/withJavadocJar)
./gradlew test                # JUnit 5 only, no coverage gate
./gradlew check               # checkstyleMain/Test + spotbugsMain/Test + test + jacocoTestCoverageVerification (80% gate)
./gradlew checkstyleMain checkstyleTest   # lint only
./gradlew spotbugsMain spotbugsTest       # static analysis only
./gradlew jacocoTestReport                # HTML/XML coverage report without the gate
```

There is no `just`-style task runner in this repo (unlike `tamga-api`) — the Gradle wrapper
(`./gradlew`, never a locally-installed `gradle`) is the whole toolchain. Always use the wrapper:
it pins the exact Gradle version (`gradle/wrapper/gradle-wrapper.properties`) this repo builds
against, and that pin matters — see "Gradle/Checkstyle version coupling" below.

**JNI native build** (`jni/CMakeLists.txt`) is a separate, non-Gradle build step. It configures
and compiles standalone today (verified: `cmake -S jni -B build-dir && cmake --build build-dir`
produces a linkable `libtamga_jni` on macOS) but does not yet link against a real `tamga-c` cdylib
— that requires `tamga-c` v0.1's release artifacts, per the blocker below.

## GOTCHAS — from `docs/sdk.md`'s "Known Server-Side Gaps"

These are real, verified discrepancies between what the server *appears* to support and what it
actually does. Building this SDK's UX around the wrong side of any of these will either silently
no-op or advertise a guarantee the server doesn't enforce. Only the gaps relevant to this SDK's
scope (license validation, checkout, machine management, offline proof) are listed — see the
source doc for the full set, including analytics/EE items that don't touch this SDK at all.

- **Auto-update/release-checking is explicitly out of scope for v1.** `GET
  /releases/actions/upgrade` crashes at runtime server-side (queries a `release_artifacts` table
  and columns that don't exist in any migration) and even once fixed has no working
  download-URL endpoint. Do not build any "check for update" client feature against it.
- **No auth is enforced server-side on license or machine endpoints today.** Still always send
  `Authorization: License <key>` (forward-compatible) — just don't build client-side logic that
  assumes a bad/missing credential gets rejected right now.
- **Only 14 of 24 `ValidationCode` values are reachable.** Model all 24 with lenient/unknown-value
  decoding (`@JsonEnumDefaultValue` on `UNKNOWN`), but don't build UI/UX around the 10 that are
  declared and never emitted (`BANNED`, `ENTITLEMENTS_MISSING`, `TOO_MANY_USERS`,
  `HEARTBEAT_DEAD`, `HEARTBEAT_NOT_STARTED`, `FINGERPRINT_SCOPE_MISMATCH`,
  `COMPONENTS_SCOPE_MISMATCH`, `CHECKSUM_SCOPE_MISMATCH`, `VERSION_SCOPE_MISMATCH`, and
  `NOT_FOUND`, which surfaces as an HTTP 404 instead of this code). Same applies to
  `ValidationScope`'s `entitlements`/`fingerprint`/`version`/`checksum` fields — build the request
  field, don't advertise it as a functioning constraint.
- **No client-side 429/backoff handling.** `429 TOO_MANY_REQUESTS` is declared in the server's
  error enum but has no constructor and is never returned by any code path today. Do not add
  retry/backoff logic that waits for a 429 that will never come.
- **`Tamga-Environment` request header does nothing server-side.** It's a planned EE feature with
  no request-parsing code path yet. Don't expose a client-facing "environment" option that implies
  it's honored today.
- **Fresh policies default to non-existent enum variants.** `overage_strategy` defaults to the
  literal string `"DENY_ACCESS"` and `heartbeat_resurrection_strategy` to `"NO_RESURRECTION"` —
  neither is a real variant. The server silently treats both as the "no restriction" variant
  (`NO_OVERAGE`/`NO_REVIVE`). Deserializers here must not crash on these strings and must not
  invent fake enum cases implying restrictive behavior the server doesn't actually have.
- **Heartbeat windows are hardcoded, not policy-driven.** Machine heartbeat window is a hardcoded
  600s regardless of `policy.heartbeat_duration`; process heartbeat window is a hardcoded 30s with
  no resurrection grace period at all. Any heartbeat-scheduler helper should derive its ping
  interval from these hardcoded constants, not from a policy value the server ignores.
- **License checkout's AES key derivation is NOT a KDF.** It's the raw UTF-8 bytes of the license
  key, zero-padded or truncated to exactly 32 bytes. Running it through SHA-256 or any real KDF
  produces a key that silently fails to decrypt. Machine checkout, by contrast, *does* use a real
  HKDF-SHA256 — don't let the two crypto paths bleed into each other (see `LicenseFile` vs.
  `MachineFile` Javadoc).
- **The license-checkout Ed25519 signature covers the base64 *string bytes* of `enc`, not its
  decoded bytes.** This is the single most common implementation bug across every Tamga SDK. See
  the `CRITICAL:` note in `checkout/LicenseFile.java`'s Javadoc, and flag it again at the exact
  call site when implemented.
- **`RSA_2048_JWT_RS256` is rejected for machine files.** `MachineFile.verify` must throw before
  attempting a native call for this scheme, matching the server's `422 SCHEME_NOT_SUPPORTED` — do
  not implement a JWT verification path for it.
- **Offline-proof field order is load-bearing.** The RSA signature in `proof/OfflineProof.java`
  covers a specific server-produced key order (`account.id` → `machine.id` →
  `machine.fingerprint` → `dataset`); a reflection-ordered Jackson POJO is not a safe
  serialization strategy here — see that file's Javadoc.

## Testing

- **JUnit 5 + AssertJ + Mockito**, run via `useJUnitPlatform()`. `src/test/java/sh/tamga/sdk/`
  mirrors `src/main/java/sh/tamga/sdk/` package-for-package.
- CI gates on **80% instruction coverage** via `jacocoTestCoverageVerification` (wired in
  `build.gradle.kts`, `violationRules { rule { limit { minimum = 0.80 } } }`) — a failing coverage
  gate fails the job the same way a failing test does. Run `./gradlew check` locally before
  pushing.
- Static analysis (Checkstyle: Google Java Style via `config/checkstyle/google_checks.xml`;
  SpotBugs: default effort/threshold) runs **before** the test/coverage steps in CI so lint
  failures short-circuit the more expensive build — see `.github/workflows/ci.yml`'s header
  comment.
- CI matrix is **Temurin 17/21 × ubuntu/macos/windows-latest** — not redundant repetition. Once
  Section B's JNI binding lands, the native build differs meaningfully per OS (different C
  toolchain, different shared-library extension: `.so`/`.dylib`/`.dll`); today, with no native
  build wired, all three legs run the identical pure-Java path.
- Section B's integration tests (native round-trips against `tamga-c` test vectors) are
  CI-native-artifact-gated — they skip locally when no built `.so`/`.dylib`/`.dll` is present,
  they do not fail. Don't "fix" a skip into a failure by assuming the artifact should always be
  there.

## Critical Dependency Notes

- **`tamga-c`'s ABI-freeze commitment is a hard blocker for this repo.** `tamga.h` must be frozen
  for one full release cycle with exported-symbol-table and struct-layout semver guarantees before
  `internal/jni/TamgaNative.java`'s native method declarations and `jni/tamga_jni.c`'s glue can be
  written for real. Until then, both are intentionally empty stubs — do not hand-transcribe
  partial/unstable `tamga.h` declarations into either; that's how this SDK silently drifts from
  `tamga-c`'s actual runtime ABI. See the plan file's banner for the exact blocker.
- **Gradle/Checkstyle version coupling is tighter than it looks.** Checkstyle's Ant task is
  compiled against a specific minimum JRE class-file version per major release — Checkstyle 13.x
  requires a JDK 21+ *runtime* to execute (independent of what bytecode level it lints), which
  fails outright under this repo's Temurin 17 build toolchain with "compiled by a more recent
  version of the Java Runtime". `checkstyle.toolVersion` is pinned to **12.3.1** in
  `build.gradle.kts` for exactly this reason — verified locally, not a guess. Don't bump it past
  the 12.x line without also reconsidering the JDK 17 toolchain pin (or bumping both together
  deliberately). The Gradle wrapper itself is pinned to **8.14.5**, one line above the minimum
  needed for `com.vanniktech.maven.publish` 0.37.0's Maven-publish integration (an older 8.11.x
  wrapper fails at configuration time with `Unresolved reference` / missing `ProjectLayout` APIs)
  — bumping the `vanniktech` plugin version may require bumping the wrapper again; check both
  together, not the plugin alone.
- **`com.vanniktech.maven.publish`'s `publishToMavenCentral()` takes no argument** on the pinned
  0.37.0. Older docs/examples show `publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)` — that
  enum was removed once the plugin dropped legacy-OSSRH support and defaulted fully to the Central
  Publisher Portal. Do not reintroduce a `SonatypeHost` argument; it will fail to resolve.
- **Google Java Style is 2-space indent, not 4.** `config/checkstyle/google_checks.xml`'s
  `Indentation` module (`basicOffset=2`) is stricter than the informal Java default most
  contributors reach for — every `.java` file in this repo uses 2-space indent (`.editorconfig`
  enforces this per-extension; `.kt`/`.kts` stay at the conventional 4-space since Checkstyle never
  lints Kotlin). Checking this in an editor without EditorConfig support will silently reintroduce
  4-space indent and fail `checkstyleMain`/`checkstyleTest`.
- **Bytecode target vs. build toolchain are deliberately different versions.** `sourceCompatibility`/
  `targetCompatibility` stay at Java 11 (consumers aren't forced onto a newer JVM) while the build
  toolchain is pinned to Temurin 17 (JNI resource packaging and eventual Android/AGP tooling
  benefit from a newer build JDK). Don't "simplify" these into the same value.

## Branch & Commit Convention

Branches: `feat/*`, `fix/*`, `chore/*`, `refactor/*`, `docs/*`
Commits: [Conventional Commits](https://www.conventionalcommits.org/) format (`feat: …`, `fix: …`,
etc.) — `release-please` (release-type: `simple`) parses these directly to drive `CHANGELOG.md`
and cut tags; `com.palantir.git-version` then derives the actual build version from that tag at
build time (no hand-bumped version file anywhere in this repo). A commit that doesn't follow the
convention is invisible to the release automation, not just a style nit.
