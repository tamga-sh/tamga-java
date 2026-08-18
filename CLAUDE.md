# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

`tamga-java` is the official Java SDK for Tamga (`sh.tamga:tamga-sdk` on Maven Central) — license
activation, offline license/machine verification, and machine/component/process management for
Java and (eventually) Android applications. Cryptographic verification is implemented natively in
Java — see "Crypto Architecture" below for why, and how this differs from the JNI-binding design
this repo started with. The protocol/feature spec this SDK is built against — every field name,
endpoint, and enum value comes from it — is the Tamga API protocol specification, referenced
throughout this file by that name. It is maintained privately and is not linkable from public
documentation.

**Current state: crypto/checkout/proof are real; HTTP client surface is still stub.**
`crypto/` (Ed25519, AES-256-GCM, HKDF-SHA256, ECDSA-P256, RSA PKCS1/PSS),
`checkout/` (`LicenseFile`, `MachineFile`), and `proof/` (`OfflineProof` +
`CanonicalJson`) are implemented and tested (118 tests, 96%+ instruction coverage). The
HTTP-facing surface (`TamgaClient`'s endpoint methods, `Transport.java`, the full JSON:API error
model, entitlement caching, heartbeat scheduling, the full `Policy`/`ValidationCode` types) is
still stub — see each of those files' own doc comments for what's deferred. Do not assume any
method on `TamgaClient` does anything yet.

## Crypto Architecture

Every cryptographic operation is implemented natively in Java, in `sh.tamga.sdk.crypto`:

1. Ed25519 verify (license checkout signature check) — BouncyCastle's lightweight API
   (`Ed25519Signer`/`Ed25519PublicKeyParameters`), not registered as a JCA `Provider`.
2. AES-256-GCM open/seal (license/machine file decrypt) — `javax.crypto.Cipher`, JDK built-in.
3. HKDF-SHA256 derive (BOTH license-file and machine-file decrypt key derivation, with different
   salt/`info` per format) — hand-rolled RFC 5869 over `javax.crypto.Mac`, JDK built-in.
4. ECDSA-P256 verify and RSA PKCS1/PSS verify (machine checkout + offline proof) —
   `java.security.Signature`/`KeyFactory`, JDK built-in.

**Everything else is hand-rolled, idiomatic Java, unchanged from before this pivot.** HTTP
transport is built directly on OkHttp — never used for crypto. If you find yourself importing
`org.bouncycastle.*` from outside `sh.tamga.sdk.crypto`, or importing `sh.tamga.sdk.crypto` from
outside `crypto/`, `checkout/`, or `proof/`, stop — those are the only packages allowed to touch
either.

### Why native, not bound to tamga-c

This repo originally planned to bind to `tamga-c` (the Rust reference implementation) via JNI, the
same way `tamga-swift` originally planned an FFI binding to the same library. Both pivoted to
native reimplementation for the same reason: a cross-repo security audit found a real
curve-confusion vulnerability class (an ECDSA verifier that never pins the expected curve, so it
runs verification math using whatever curve an attacker-supplied key claims to be) independently
present in 3 of 5 from-scratch SDK reimplementations at the time — real evidence that
reimplementation carries real risk — but the binding architecture's own cross-platform CI/build
cost proved substantial in practice for `tamga-swift`. Given that trade-off, both repos moved to
native reimplementation, with the explicit mitigation that every crypto primitive gets the same
rigor the audit was checking for in the first place.

That mitigation paid off directly here: **empirically confirmed** (via a real compiled/executed
probe, not assumed) that `KeyFactory.getInstance("EC").generatePublic(...)` does not validate a
parsed X.509 `SubjectPublicKeyInfo`'s declared curve OID — the exact same curve-confusion gap
class, this time inside the JDK's own `java.security` package. `Ecdsa.verify` closes this with an
explicit post-parse comparison of the parsed key's `ECParameterSpec` against P-256's canonical
parameters (curve/generator/order/cofactor individually — `ECParameterSpec` itself does not
override `equals()`, confirmed empirically). See `Ecdsa.java`'s Javadoc for the full writeup.

A second, independent finding from the same discipline: Java's `String.compareTo()` genuinely
diverges from UTF-8 byte order for the same class of adversarial pair that broke tamga-js's
`canonicalJson.ts` (an astral-plane character sorts on the wrong side of a BMP private-use
character under UTF-16 code-unit order vs. UTF-8 byte order) — confirmed via a real probe, not
assumed. `CanonicalJson` sorts object keys via `Arrays.compareUnsigned` over each key's UTF-8
bytes explicitly, never relying on `String.compareTo()`/`TreeMap`'s natural ordering. See
`CanonicalJson.java`'s Javadoc.

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
│   │   └── java/sh/tamga/sdk/
│   │       ├── TamgaClient.java            # entry point; builder requires accountId + baseUrl
│   │       ├── Transport.java              # OkHttp-based transport — hand-rolled
│   │       ├── model/                      # License, Machine, LicenseScheme, HeartbeatStatus,
│   │       │                               #   CanonicalJson, TamgaJsonMapper, ValidationCode
│   │       │                               #   (ValidationCode/Policy still stub)
│   │       ├── crypto/                     # Ed25519, AesGcm, Hkdf, Ecdsa, Rsa
│   │       ├── checkout/                   # LicenseFile, MachineFile — PEM parse/verify/decrypt
│   │       ├── proof/                      # OfflineProof — RSA-2048 PKCS#1v1.5, exact-order payload
│   │       └── error/                      # TamgaCheckoutException (real) + TamgaError (still stub)
│   └── test/java/sh/tamga/sdk/             # JUnit 5 + AssertJ, mirrors src/main package-for-package
└── .github/workflows/
    ├── ci.yml                              # checkstyle + spotbugs + check (JUnit5/JaCoCo) + codecov
    └── release.yml                         # release-please + publishToMavenCentral on release
```

There is no server here and no `tamga-web`-equivalent binary — `tamga-sdk` is the one artifact
consumers depend on.

## Dev Commands

```bash
./gradlew build              # compile + package (sources/javadoc jars come from the publish plugin, NOT withSourcesJar/withJavadocJar — see build.gradle.kts)
./gradlew test                # JUnit 5 only, no coverage gate
./gradlew check               # checkstyleMain/Test + spotbugsMain/Test + test + jacocoTestCoverageVerification (80% gate)
./gradlew checkstyleMain checkstyleTest   # lint only
./gradlew spotbugsMain spotbugsTest       # static analysis only
./gradlew jacocoTestReport                # HTML/XML coverage report without the gate
```

There is no `just`-style task runner in this repo — the Gradle wrapper (`./gradlew`, never a
locally-installed `gradle`) is the whole toolchain. Always use the wrapper:
it pins the exact Gradle version (`gradle/wrapper/gradle-wrapper.properties`) this repo builds
against, and that pin matters — see "Gradle/Checkstyle version coupling" below.

## GOTCHAS — from the Tamga API protocol specification's "Known Server-Side Gaps"

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
  field, don't advertise it as a functioning constraint. (Still stub — deferred with the rest of
  the HTTP-facing surface.)
- **HTTP 429 is live and must be handled once `Transport` exists.** The server returns it, and the
  rest of the SDK fleet already ships the handling: parsed and capped `Retry-After`, jittered
  exponential backoff, auto-retry scoped to `GET` plus the five safe `POST` actions (`validate`,
  `validate-key`, `check-in`, `check-out`, `ping`), with resource creation deliberately excluded.
  This SDK ships none of it yet only because it has no HTTP transport at all — implementing
  `Transport.java` means implementing this too.
- **`Tamga-Environment` request header does nothing server-side.** It's a planned EE feature with
  no request-parsing code path yet. Don't expose a client-facing "environment" option that implies
  it's honored today.
- **Fresh policies default to non-existent enum variants.** `overage_strategy` defaults to the
  literal string `"DENY_ACCESS"` and `heartbeat_resurrection_strategy` to `"NO_RESURRECTION"` —
  neither is a real variant. The server silently treats both as the "no restriction" variant
  (`NO_OVERAGE`/`NO_REVIVE`). Deserializers here must not crash on these strings and must not
  invent fake enum cases implying restrictive behavior the server doesn't actually have. (Still
  stub — the full `Policy` type is deferred; `LicenseScheme` is the only piece of this area
  implemented so far, since `MachineFile`'s scheme dispatch needed it.)
- **Heartbeat windows are hardcoded, not policy-driven.** Machine heartbeat window is a hardcoded
  600s regardless of `policy.heartbeat_duration`; process heartbeat window is a hardcoded 30s with
  no resurrection grace period at all. Any heartbeat-scheduler helper should derive its ping
  interval from these hardcoded constants, not from a policy value the server ignores.
- **Both checkout formats derive their AES key with HKDF-SHA256** (`crypto.Hkdf`), with different,
  non-interchangeable parameters: license files use salt `tamga:license-file-key-v1` / `info`
  `license-file` (`Hkdf.deriveLicenseFileKey`); machine files use salt
  `tamga:machine-file-key-v1` / `info` = the machine fingerprint (`Hkdf.deriveMachineFileKey`).
  The pre-v2 license-file transform (raw license-key bytes zero-padded/truncated to 32) is
  **removed, not deprecated** — its class is gone, so no caller can reach it. See `HkdfTest`'s
  explicit regression that the two derivations never collide.
- **Offline license files must be format v2.** `alg` must be exactly `base64+ed25519+v2` or
  `aes-256-gcm+ed25519+v2`; the signed payload must carry `meta` claims (`iat`/`exp`/`jti`/`kid`);
  `exp` is enforced with a 60s clock-skew tolerance. v1 files are rejected outright with no
  fallback path — a real behavioral break for callers holding v1-issued `.lic` files. See
  `LicenseFile.verifyWithClaims`, `License.parseResourcePayloadWithClaims`, and `LicenseFileClaims`.
- **The license-checkout Ed25519 signature covers the base64 *string bytes* of `enc`, not its
  decoded bytes.** This is the single most common implementation bug across every Tamga SDK. See
  the `CRITICAL:` note in `checkout/LicenseFile.java`'s Javadoc and the call site in `verify`, and
  `LicenseFileTest`'s dedicated regression signing the decoded bytes to confirm it fails.
- **`RSA_2048_JWT_RS256` is rejected for machine files.** `MachineFile.verify` throws
  `TamgaCheckoutException.SchemeNotSupportedException` before attempting any verification for this
  scheme, matching the server's `422 SCHEME_NOT_SUPPORTED` — no JWT verification path exists or
  should be added.
- **Offline-proof field order is load-bearing.** The RSA signature in `proof/OfflineProof.java`
  covers a specific server-produced key order, recursively alphabetical at every nesting level
  (matching `serde_json`'s `BTreeMap`-backed output) — NOT literal source/insertion order. See
  `OfflineProof.java`'s `CRITICAL:` note and `model/CanonicalJson.java`.

## Testing

- **JUnit 5 + AssertJ**, run via `useJUnitPlatform()`. `src/test/java/sh/tamga/sdk/` mirrors
  `src/main/java/sh/tamga/sdk/` package-for-package. Mockito is declared as a dependency but not
  yet needed by any real test — the crypto/checkout/proof suites use real generated keys and
  signatures throughout, not mocks.
- CI gates on **80% instruction coverage** via `jacocoTestCoverageVerification` (wired in
  `build.gradle.kts`, `violationRules { rule { limit { minimum = 0.80 } } }`) — a failing coverage
  gate fails the job the same way a failing test does. Run `./gradlew check` locally before
  pushing.
- Static analysis (Checkstyle: Google Java Style via `config/checkstyle/google_checks.xml`;
  SpotBugs: default effort/threshold) runs **before** the test/coverage steps in CI so lint
  failures short-circuit the more expensive build.
- CI matrix is **Temurin 17/21 × ubuntu/macos/windows-latest**. Pure Java throughout (no native
  build step), but genuinely exercises platform differences that matter here: default charset
  (`build.gradle.kts` sets `options.encoding = "UTF-8"` explicitly for exactly this reason — a
  `.java` file with literal non-ASCII source characters would otherwise risk misreading on
  Windows), temp files, and line endings.

## Critical Dependency Notes

- **BouncyCastle (`org.bouncycastle:bcprov-jdk18on`) is the one crypto dependency, scoped to
  exactly Ed25519.** The JDK's own built-in EdDSA support (`java.security.Signature`/`KeyFactory`
  with algorithm name `"Ed25519"`) only landed in JDK 15, but this module's bytecode target stays
  at Java 11 so consuming applications aren't forced onto a newer JVM. Used via BouncyCastle's
  lightweight API only (`Ed25519Signer`/`Ed25519PublicKeyParameters`) — never registered as a JCA
  `Provider`, keeping the actual dependency surface narrow and auditable. This mirrors
  `tamga-dotnet`'s own precedent for the identical problem (its BCL also lacks Ed25519; it adds
  exactly one minimal dependency, `NSec.Cryptography`, scoped the same way) — confirmed by
  directly reading that repo's `Crypto/` folder, not assumed. AES-256-GCM/ECDSA-P256/RSA/
  HKDF-SHA256 all stay on JDK built-ins.
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
  4-space indent and fail `checkstyleMain`/`checkstyleTest`. Also stricter than a typical Java
  project: Checkstyle's `AbbreviationAsWordInName` rejects 2+ consecutive capital letters in an
  identifier, including the common `...ForA<CapitalizedWord>...`/`...IsNotA<CapitalizedWord>...`
  shape a descriptive test-method name naturally produces (the standalone one-letter word "A"
  directly abuts the next word's capital with nothing lowercase between them) — drop the article
  rather than fight the rule (`verifyReturnsFalseForWrongKey`, not `...ForAWrongKey`). Also flags
  `\u` numeric escapes for anything but genuine control characters, and (a separate but related
  trap) **`\u` unicode-escape processing happens at the Java lexer level, before tokenization, in
  comments and string literals alike** — writing the literal 6 characters backslash-u-0-0-e-9 in
  a Javadoc comment describing an escape sequence is itself a compile error ("illegal unicode
  escape") if the 4 characters after `u` aren't valid hex, and other errors reported later in the
  same file can be phantom cascades from that one real syntax error, not independent bugs.
- **Bytecode target vs. build toolchain are deliberately different versions.** `sourceCompatibility`/
  `targetCompatibility` stay at Java 11 (consumers aren't forced onto a newer JVM, and this is also
  why Ed25519 goes through BouncyCastle above) while the build toolchain is pinned to Temurin 17
  (Checkstyle 13.x's JDK 21+ runtime requirement noted above, and eventual Android/AGP tooling,
  both benefit from a newer build JDK without raising the floor for consumers). Don't "simplify"
  these into the same value.

## Branch & Commit Convention

Branches: `feat/*`, `fix/*`, `chore/*`, `refactor/*`, `docs/*`
Commits: [Conventional Commits](https://www.conventionalcommits.org/) format (`feat: …`, `fix: …`,
etc.) — `release-please` (release-type: `simple`) parses these directly to drive `CHANGELOG.md`
and cut tags; `com.palantir.git-version` then derives the actual build version from that tag at
build time (no hand-bumped version file anywhere in this repo). A commit that doesn't follow the
convention is invisible to the release automation, not just a style nit.
