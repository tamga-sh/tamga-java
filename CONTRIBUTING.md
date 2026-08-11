# Contributing to tamga-java

> **Skeleton only.** Full build instructions (JNI native-library prerequisites, `TAMGA_C_LIB_PATH`
> setup, how to run the pure-Java test suite vs. the native-artifact-gated integration tests) land
> in `docs/plans/tamga-java.plan.md` Section M, once `tamga-c` publishes its v0.1 release and
> Section B's native binding layer is real. This file exists now so the section header structure
> is in place and PRs against this repo have a contribution doc to point to.

## Status

This repository is currently an infrastructure scaffold (`docs/plans/tamga-java.plan.md`
Section A). There is no business logic, HTTP transport, or JNI/crypto implementation yet — see
this repo's [`CLAUDE.md`](CLAUDE.md) for the full picture and the plan file for the task-by-task
build order.

## Prerequisites (partial — expand once Section B unblocks)

- **JDK**: Temurin 17 (build toolchain) — see `build.gradle.kts`'s `java.toolchain` block.
- **Gradle**: use the committed wrapper (`./gradlew`), never a locally-installed Gradle — the
  wrapper pins the exact version this repo builds against (`gradle/wrapper/gradle-wrapper.properties`).

The rest — CMake, a C toolchain, a built `tamga-c` cdylib, `TAMGA_C_LIB_PATH` — is deferred until
Section B is unblocked.

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
before `./gradlew publishToMavenCentral` can run on `release: published`:

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
**mandatory** (not optional) on any change touching `src/main/java/sh/tamga/sdk/internal/jni/`,
`src/main/java/sh/tamga/sdk/checkout/`, `src/main/java/sh/tamga/sdk/proof/`, or `jni/` — see
`docs/plans/tamga-java.plan.md` §4 Quality Gates.
