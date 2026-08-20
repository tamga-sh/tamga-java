# Contributing to tamga-java

## Status

Crypto verification (`crypto/`), offline checkout file parsing (`checkout/`), and offline proof
verification (`proof/`) are implemented and tested. The `TamgaClient` HTTP-facing surface
(validate/checkout/heartbeat/etc. endpoints, the full `Policy`/`ValidationCode` types) is not yet
implemented — see this repo's [`CLAUDE.md`](CLAUDE.md) for the full current-state picture.

## Prerequisites

- **JDK**: Temurin 17 (build toolchain) — see `build.gradle.kts`'s `java.toolchain` block. The
  published bytecode target is Java 11 (see `CLAUDE.md`'s "Bytecode target vs. build toolchain"
  note); you don't need a separate JDK 11 install to build or test this repo.
- **Gradle**: use the committed wrapper (`./gradlew`), never a locally-installed Gradle — the
  wrapper pins the exact version this repo builds against (`gradle/wrapper/gradle-wrapper.properties`).

No native toolchain (CMake, a C compiler) is needed — this repo has no native build step.

## Build & Test

```bash
./gradlew build   # compile + package
./gradlew check   # checkstyle + spotbugs + test + jacocoTestCoverageVerification (80% gate)
./gradlew test    # JUnit 5 only, no coverage gate
```

## Commit & Branch Convention

See this repo's [`CLAUDE.md`](CLAUDE.md) "Branch & Commit Convention" section — Conventional
Commits, parsed directly by `release-please` (release-type: `simple`) to drive `CHANGELOG.md` and
version tags.

## Release Secrets (repo maintainers only)

`.github/workflows/release.yml`'s `publish` job requires these repo secrets to be configured
before `./gradlew publishToMavenCentral` can run when release-please cuts a release:

| Secret | Purpose |
|---|---|
| `ORG_GRADLE_PROJECT_SIGNING_IN_MEMORY_KEY` | In-memory GPG private key (armored) for artifact signing — no on-disk keyring in CI |
| `ORG_GRADLE_PROJECT_SIGNING_IN_MEMORY_KEY_PASSWORD` | Passphrase for the above key |
| `MAVEN_CENTRAL_USERNAME` | Central Publisher Portal (central.sonatype.com) token username — **not** a legacy OSSRH JIRA username |
| `MAVEN_CENTRAL_PASSWORD` | Central Publisher Portal token password |

See `build.gradle.kts`'s `signing {}` and `mavenPublishing {}` blocks for how these map to Gradle
project properties, and `release.yml`'s header comment for why publishing targets the Central
Publisher Portal rather than legacy OSSRH.

## Code Review

`java-reviewer` and `ecc:java-coding-standards` apply to every change. `security-reviewer` is
**mandatory** (not optional) on any change touching `src/main/java/sh/tamga/sdk/crypto/`,
`src/main/java/sh/tamga/sdk/checkout/`, or `src/main/java/sh/tamga/sdk/proof/` — see
`.github/CODEOWNERS`.
