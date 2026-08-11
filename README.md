# Tamga

Official Java SDK for Tamga. Integrate license activation, offline verification, and machine
management into your Java and Android applications, built on the
[tamga-c](https://github.com/tamga-sh/tamga-c) core via JNI.

> **Status: pre-release scaffold.** This repository currently contains project structure and stub
> classes only — no business logic, HTTP transport, or cryptographic verification is implemented
> yet, and it is blocked on `tamga-c`'s ABI freeze (see `CLAUDE.md`). The code snippet below shows
> the intended API shape and is illustrative, not yet functional.

## Install

**Maven Central** — package `sh.tamga:tamga-sdk`:

```kotlin
// build.gradle.kts
dependencies {
    implementation("sh.tamga:tamga-sdk:<version>")
}
```

```groovy
// build.gradle
dependencies {
    implementation "sh.tamga:tamga-sdk:<version>"
}
```

## Quickstart

> Illustrative — matches the stub API shape scaffolded in this repository, not yet a working
> implementation.

```java
import sh.tamga.sdk.TamgaClient;
import sh.tamga.sdk.model.ValidationCode;

TamgaClient client = TamgaClient.builder()
    .accountId("your-account-id")
    .baseUrl("https://api.tamga.sh")
    .build();

var result = client.licenses().validateByKey("your-license-key");

switch (result.code()) {
    case VALID -> System.out.println("License is valid");
    case EXPIRED, SUSPENDED -> System.out.println("License is not usable: " + result.code());
    default -> System.out.println("Validation returned: " + result.code());
}
```

## Documentation

- [Tamga SDK protocol reference](https://github.com/tamga-sh/tamga-api/blob/main/docs/sdk.md) —
  the authoritative spec every field name, endpoint, and enum value in this SDK is taken from,
  including a "Known Server-Side Gaps" section describing which documented features are not
  actually reachable against the server yet.
- [`CLAUDE.md`](CLAUDE.md) — architecture, dev commands, and gotchas for contributors.

## Roadmap

- **v0.1** ships desktop-JVM natives only: `linux-x86_64`, `macos-x86_64`, `macos-aarch64`,
  `windows-x86_64` (see `src/main/resources/native/`).
- **Android AAR packaging + NDK cross-compile** (`arm64-v8a`, `armeabi-v7a`, `x86_64`) is explicit
  backlog, not part of v0.1.

## License

MIT — see [LICENSE](LICENSE).
