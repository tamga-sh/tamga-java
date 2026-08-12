// `build.gradle.kts`
//
// Single-module build for `sh.tamga:tamga-sdk`. Crypto is implemented
// natively in `crypto/` (JDK built-ins for everything except Ed25519 --
// BouncyCastle's lightweight API, scoped to exactly that one primitive,
// since the JDK's own built-in EdDSA support only landed in JDK 15 and this
// module's bytecode target stays at 11). See CLAUDE.md for the full
// architecture rationale, why bytecode target and build-toolchain versions
// differ, and why the version comes from a git tag instead of a hand-bumped
// property.

plugins {
    `java-library`
    checkstyle
    jacoco
    id("com.github.spotbugs") version "6.5.10"
    id("com.palantir.git-version") version "0.11.0"
    id("com.vanniktech.maven.publish") version "0.37.0"
    signing
}

group = "sh.tamga"

// No hand-bumped version property anywhere in this repo. `com.palantir.git-version`
// derives it from the nearest git tag (`git tag v0.2.0` + a build IS the entire
// "bump the version" operation) — see docs/plans/tamga-java.plan.md Section N and
// this repo's CLAUDE.md "Release & Versioning" section. `gitVersion()` returns
// `"unspecified"` in a shallow/tag-less checkout (e.g. this scaffold, pre-v0.1),
// which is fine for local `./gradlew build` but must never be what gets published.
val gitVersion: groovy.lang.Closure<String> by extra
version = gitVersion()

java {
    // Bytecode target stays at Java 11 so consuming applications aren't forced
    // onto a newer JVM than the SDK itself needs (this is also why Ed25519 goes
    // through BouncyCastle below instead of the JDK's own EdDSA support, which
    // only landed in JDK 15). The BUILD toolchain is pinned higher (Temurin 17)
    // on purpose — Checkstyle 13.x's Ant task requires a JDK 21+ *runtime* to
    // execute regardless of bytecode level it lints (verified locally), and
    // eventual Android/AGP tooling benefits from a newer build JDK too, without
    // raising the floor for consumers. Do not let these two drift into the same
    // value by "simplifying" — they are deliberately different.
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11

    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
        vendor.set(JvmVendorSpec.ADOPTIUM) // Temurin
    }

    // Deliberately NOT calling withSourcesJar()/withJavadocJar() here: this repo applies
    // com.vanniktech.maven.publish's MAIN plugin (not the `.base` variant), which internally
    // calls configureBasedOnAppliedPlugins() and already wires up its own sources/javadoc jar
    // tasks (including a task it names `plainJavadocJar`) once it detects the java-library
    // plugin is applied. Calling withSourcesJar()/withJavadocJar() here too used to create a
    // second, competing task-creation path for the same artifacts -- Gradle's task-graph
    // validation correctly flagged this as an undeclared dependency between
    // generateMetadataFileForMavenPublication and plainJavadocJar (their execution order
    // wasn't guaranteed), which surfaced as a real publishToMavenCentral failure. Confirmed
    // against the plugin's own docs: the "equivalent manual Gradle setup" it documents for
    // JavaLibrary publications IS exactly withSourcesJar()/withJavadocJar() -- i.e. it's an
    // either/or with the plugin's auto-configuration, not additive.
}

// Explicit, not left to the platform default: the CI matrix includes
// windows-latest, whose default source-file charset is NOT UTF-8 (unlike
// macOS/Linux) -- without this, a .java file containing literal non-ASCII
// characters (e.g. CanonicalJsonTest's café/日本語 literals) would compile
// correctly on macOS/Linux but potentially misread on Windows.
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
tasks.withType<Javadoc> {
    options.encoding = "UTF-8"
}

repositories {
    mavenCentral()
}

dependencies {
    // --- Crypto (crypto/) ---
    // JDK built-ins cover Ed25519's siblings (AES-256-GCM via javax.crypto,
    // ECDSA-P256/RSA via java.security, HKDF-SHA256 hand-rolled over
    // javax.crypto.Mac) -- BouncyCastle is scoped to exactly the one real gap:
    // the JDK's own EdDSA support requires JDK 15+, but this module's bytecode
    // target is 11. Used via its lightweight API (Ed25519Signer +
    // Ed25519PublicKeyParameters) only -- never registered as a JCA Provider,
    // keeping the dependency's actual surface area narrow and auditable.
    // "jdk18on" means "JDK 1.8 and onward" (BouncyCastle's own naming
    // convention for their current general-purpose artifact line) -- covers
    // this module's Java 11 target.
    api("org.bouncycastle:bcprov-jdk18on:1.80")

    // --- Transport ---
    // Hand-rolled HTTP transport on OkHttp.
    api("com.squareup.okhttp3:okhttp:5.4.0")

    // --- JSON ---
    // `FAIL_ON_UNKNOWN_PROPERTIES = false` config (forward-compat with server
    // additions) lives in TamgaJsonMapper, shared by the checkout/proof
    // offline-decode path and (eventually) TamgaClient's response mapping.
    api("com.fasterxml.jackson.core:jackson-databind:2.22.1")
    // Optional<T> (de)serialization support for model fields that are
    // genuinely absent-vs-null on the wire (see ecc:java-coding-standards on
    // Optional usage — fields, not method params).
    api("com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.22.1")
    // Instant/OffsetDateTime (de)serialization -- jackson-databind alone does
    // not understand java.time types.
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.22.1")

    // --- Test (test scope only) ---
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.23.0")
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true) // consumed by codecov/codecov-action@v4 in CI
        html.required.set(true)
    }
}

jacoco {
    // Matches the CI-enforced gate documented in docs/plans/tamga-java.plan.md
    // §4/§5 and this repo's CLAUDE.md "Testing" section.
    toolVersion = "0.8.12"
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    violationRules {
        rule {
            limit {
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

checkstyle {
    // Pinned below Checkstyle's latest (13.x) on purpose: 13.x's Ant task is compiled for a
    // newer class-file version than our Temurin 17 build toolchain can run (verified locally --
    // 13.10.0 fails with "compiled by a more recent version of the Java Runtime, class file
    // version 65.0" under JDK 17, i.e. it requires JDK 21+ to RUN, independent of what bytecode
    // level it lints). 12.x is the newest line confirmed to run under JDK 17.
    toolVersion = "12.3.1"
    configFile = file("config/checkstyle/google_checks.xml")
    maxWarnings = 0
}

spotbugs {
    // Default effort/threshold (MAX/MEDIUM) — tighten only if false-positive
    // noise on the crypto-adjacent packages proves it's warranted.
    excludeFilter.set(file("config/spotbugs/exclude.xml"))
}

tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
    reports.create("html") {
        required.set(true)
    }
    reports.create("xml") {
        required.set(true)
    }
}

// --- Publishing (§N) ---
// `publishToMavenCentral()` (no argument) targets the Central Publisher Portal
// (central.sonatype.com) by default in vanniktech/gradle-maven-publish-plugin
// 0.31+ — the plugin dropped the legacy-OSSRH-vs-Portal `SonatypeHost` choice
// once the legacy OSSRH hosts were fully sunset. Do not reintroduce a
// `SonatypeHost` argument; it no longer exists on this plugin version. See
// CLAUDE.md "Release & Versioning". Actual `release: published` → publish
// wiring lives in .github/workflows/release.yml.
mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates(group.toString(), "tamga-sdk", version.toString())

    pom {
        name.set("tamga-sdk")
        description.set(
            "Official Java SDK for Tamga. Integrate license activation, offline " +
                "verification, and machine management into your Java and Android " +
                "applications, with cryptographic verification implemented natively " +
                "in Java (JDK built-ins + BouncyCastle for Ed25519)."
        )
        url.set("https://github.com/tamga-sh/tamga-java")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                id.set("tamga")
                name.set("Tamga")
                url.set("https://tamga.sh")
            }
        }
        scm {
            url.set("https://github.com/tamga-sh/tamga-java")
            connection.set("scm:git:git://github.com/tamga-sh/tamga-java.git")
            developerConnection.set("scm:git:ssh://git@github.com/tamga-sh/tamga-java.git")
        }
    }
}

// In-memory GPG signing (no on-disk keyring in CI): reads
// ORG_GRADLE_PROJECT_signingInMemoryKey / ...KeyPassword project properties,
// which release.yml maps from repo secrets of the same suffix.
signing {
    val signingInMemoryKey: String? by project
    val signingInMemoryKeyPassword: String? by project
    if (signingInMemoryKey != null) {
        useInMemoryPgpKeys(signingInMemoryKey, signingInMemoryKeyPassword)
    }
}
