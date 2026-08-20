# sh.tamga:tamga-sdk

[![Maven Central](https://img.shields.io/maven-central/v/sh.tamga/tamga-sdk)](https://central.sonatype.com/artifact/sh.tamga/tamga-sdk)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Official Java SDK for Tamga. Integrate license activation, offline verification, and machine
management into your Java applications.

Two independent surfaces, either of which can be used without the other:

- **`TamgaClient`** talks to the API — validation, activation, checkout, heartbeats, components,
  processes and entitlements. Twenty endpoints, seven auth transports, and automatic handling of
  HTTP 429.
- **`checkout/` and `proof/`** verify `.lic` and `.machine` files and offline proofs with **no
  network access at all**, once your account's public key is embedded in the application.

## Install

Requires Java 11 or newer. The published bytecode target is Java 11; the build toolchain is
Temurin 17.

```kotlin
// build.gradle.kts
dependencies {
    implementation("sh.tamga:tamga-sdk:1.3.0")
}
```

```groovy
// build.gradle
dependencies {
    implementation "sh.tamga:tamga-sdk:1.3.0"
}
```

```xml
<!-- pom.xml -->
<dependency>
  <groupId>sh.tamga</groupId>
  <artifactId>tamga-sdk</artifactId>
  <version>1.3.0</version>
</dependency>
```

## Quickstart

### Validating against the API

```java
import sh.tamga.sdk.AuthTransport;
import sh.tamga.sdk.TamgaClient;
import sh.tamga.sdk.model.ValidationCode;
import sh.tamga.sdk.model.ValidationResult;

TamgaClient client = TamgaClient.builder("YOUR-ACCOUNT-ID")
    .auth(AuthTransport.licenseKey(licenseKey))
    .build();

ValidationResult result = client.validateByKey(licenseKey);
if (!result.valid()) {
  // Branch on code, never on detail: detail is human text that may be reworded.
  if (result.meta().code() == ValidationCode.EXPIRED) {
    promptForRenewal();
  }
}
```

Activating a machine, with the seat freed automatically if the license is over its limit:

```java
import sh.tamga.sdk.HeartbeatScheduler;
import sh.tamga.sdk.error.TamgaMachineOverLimitException;
import sh.tamga.sdk.model.ActivationResult;
import sh.tamga.sdk.model.CreateMachineOptions;
import sh.tamga.sdk.model.HeartbeatStatus;

try {
  ActivationResult activation = client.activateMachine(
      CreateMachineOptions.of(fingerprint, licenseId).withHostname("build-box"), null);

  HeartbeatScheduler scheduler = HeartbeatScheduler.builder(client, activation.machine().id())
      .onTick((machine, error) -> {
        // DEAD means the row was culled server-side: re-activate rather than keep pinging.
        if (machine != null && machine.heartbeatStatus() == HeartbeatStatus.DEAD) {
          reactivate();
        }
      })
      .build();
  scheduler.start();
} catch (TamgaMachineOverLimitException e) {
  // The machine has already been deleted; the meta says which limit was hit.
  showSeatLimitMessage(e.validationMeta().code());
}
```

**Note:** this SDK generates no machine fingerprint for you, and embeds no account public key.
Producing a stable, device-specific fingerprint and deciding your grace-period and enforcement
policy remain application concerns — see [Known gaps](#known-gaps).

### Verifying an offline file

Verify and decrypt an offline `.lic` file that was checked out earlier. `verifyAndDecrypt` fails
closed: a bad signature, a wrong license key, a malformed envelope and an expired file each throw a
distinct `TamgaCheckoutException` subtype rather than returning a partially-trusted result.

```java
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import sh.tamga.sdk.checkout.LicenseFile;
import sh.tamga.sdk.error.TamgaCheckoutException;
import sh.tamga.sdk.model.License;

public final class Quickstart {

  public static void main(String[] args) throws Exception {
    // Your account's Ed25519 verify key: raw 32 bytes, distributed base64-encoded.
    byte[] publicKey = Base64.getDecoder().decode(System.getenv("TAMGA_PUBLIC_KEY"));
    String licenseKey = System.getenv("TAMGA_LICENSE_KEY");

    String pem = new String(Files.readAllBytes(Paths.get("license.lic")), StandardCharsets.UTF_8);

    try {
      License license = LicenseFile.parse(pem).verifyAndDecrypt(publicKey, licenseKey);
      System.out.println("license " + license.id() + " suspended=" + license.suspended());
      System.out.println("metadata " + license.metadata());
    } catch (TamgaCheckoutException.LicenseFileExpiredException e) {
      System.out.println("expired at unix " + e.expiresAt());
    } catch (TamgaCheckoutException.SignatureVerificationException e) {
      System.out.println("forged or corrupted file");
    } catch (TamgaCheckoutException e) {
      System.out.println("rejected: " + e.getMessage());
    }
  }
}
```

## Offline verification

### License files (`.lic`)

`LicenseFile.parse` splits the PEM envelope and decodes the inner `{enc, sig, alg}` certificate
without trusting anything in it. Verification is a separate, explicit step.

```java
import sh.tamga.sdk.checkout.LicenseFile;
import sh.tamga.sdk.model.License;

LicenseFile file = LicenseFile.parse(pem);

// Signature only: returns false instead of throwing, for callers that want a plain boolean.
boolean authentic = file.verify(publicKey);

// Signature + decrypt + expiry, all fail-closed.
License license = file.verifyAndDecrypt(publicKey, licenseKey);
```

`licenseKey` is only used to derive the decryption key for an encrypted file
(`alg` = `aes-256-gcm+ed25519+v2`); it is ignored for a plain file (`alg` = `base64+ed25519+v2`),
but is still required so both cases have the same call shape.

To read the signed claims, or to check expiry against a timestamp you trust more than the local
clock, use `verifyWithClaims`. Passing a server-supplied Unix timestamp is the recommended defence
against a user winding their system clock back to revive an expired file.

```java
import sh.tamga.sdk.checkout.LicenseFile;
import sh.tamga.sdk.model.License;
import sh.tamga.sdk.model.LicenseFileClaims;

License.LicenseWithClaims result =
    LicenseFile.parse(pem).verifyWithClaims(publicKey, licenseKey, serverUnixSeconds);

License license = result.license();
LicenseFileClaims claims = result.claims();

claims.issuedAt();   // iat
claims.expiresAt();  // exp, null when the file never expires
claims.id();         // jti, unique per checkout — usable for replay detection
claims.keyId();      // kid, so a file survives a signing-key rotation
```

### Machine files (`.machine`)

Machine files dispatch the signature check on the license's own signing scheme, supplied by you —
never on the file's self-declared `alg`. Decryption needs both the license key and the target
machine's fingerprint.

```java
import sh.tamga.sdk.checkout.MachineFile;
import sh.tamga.sdk.model.LicenseScheme;
import sh.tamga.sdk.model.Machine;

MachineFile file = MachineFile.parse(pem);

Machine machine = file.verifyAndDecrypt(
    LicenseScheme.ED25519_SIGN, publicKey, licenseKey, fingerprint);

machine.fingerprint();
machine.heartbeatStatus();
```

Supported schemes: `ED25519_SIGN` (and `NONE`, which defaults to Ed25519), `RSA_2048_PKCS1_SIGN`,
`RSA_2048_PKCS1_PSS_SIGN`, `ECDSA_P256_SIGN`. `RSA_2048_JWT_RS256` throws
`TamgaCheckoutException.SchemeNotSupportedException` — it is rejected server-side for machine files
and is deliberately not implemented here.

Ed25519 public keys are raw 32 bytes. RSA and ECDSA public keys are X.509 `SubjectPublicKeyInfo`
DER.

`MachineFile.validateTtl(int)` mirrors the server's `ttl` bounds (`> 0` and `<= 31536000`, i.e. 365
days) so a checkout request can fail fast client-side.

### Offline proofs

A lighter-weight "this machine is still valid" check for air-gapped environments. Proofs are always
RSA-2048 PKCS#1 v1.5 over SHA-256, regardless of the license's scheme.

```java
import java.util.LinkedHashMap;
import java.util.Map;
import sh.tamga.sdk.proof.OfflineProof;

Map<String, Object> dataset = new LinkedHashMap<>();
dataset.put("seats", 5);

boolean valid = OfflineProof.parse(proofString)
    .verify(rsaPublicKeyDer, accountId, machineId, fingerprint, dataset);
```

`proofString` is the `meta.proof` value, shaped `v1x0.<base64 signature>`. Insertion order of
`dataset` does not matter: the signed payload is rebuilt as canonical JSON, recursively
key-sorted by UTF-8 byte order at every nesting level.

## Security notes

Every claim below points at the code that implements it.

**Key derivation is HKDF-SHA256 for both file formats**
(`src/main/java/sh/tamga/sdk/crypto/Hkdf.java`). License files use salt
`tamga:license-file-key-v1` with `info` `license-file`
(`Hkdf.java::deriveLicenseFileKey`); machine files use salt `tamga:machine-file-key-v1` with
`info` = the machine fingerprint (`Hkdf.java::deriveMachineFileKey`). The two derivations never
collide. The pre-v2 license-file transform — raw license-key bytes zero-padded or truncated to 32 —
is **removed, not deprecated**; the class that implemented it no longer exists.

**Offline license files must be format v2, and v1 files are rejected outright with no fallback.**
This is a real behavioral break: a `.lic` file issued before v2 must be re-issued. `alg` is pinned
to exactly `base64+ed25519+v2` or `aes-256-gcm+ed25519+v2` (`LicenseFile.java::verify`); the signed
payload must carry `meta` claims `iat`/`exp`/`jti`/`kid`
(`License.java::parseResourcePayloadWithClaims`, `LicenseFileClaims.java`); and `exp` is enforced
with a 60-second clock-skew tolerance, not merely reported
(`LicenseFile.java::verifyWithClaims`). v1 put the requested expiry only in the envelope around the
certificate, never inside the signed bytes, which made every trial file permanent for anyone who
kept the raw certificate string.

**The license-file signature covers the base64 string of `enc`, not its decoded bytes**
(`LicenseFile.java::verify`). This is the single most common implementation bug in a Tamga client;
`LicenseFileTest` has a dedicated regression that signs the decoded bytes and asserts the file is
rejected.

**The ECDSA verifier pins P-256 explicitly** (`Ecdsa.java::verify`).
`KeyFactory.getInstance("EC").generatePublic(...)` does not validate that a parsed
`SubjectPublicKeyInfo`'s declared curve is the one you expect, so the parsed key's
`ECParameterSpec` is compared against P-256's canonical curve, generator, order and cofactor
field by field before any verification math runs.

**The RSA verifier enforces a 2048-bit modulus** (`Rsa.java::verifyPkcs1`, `Rsa.java::verifyPss`).
The `RSA_2048_*` scheme names are exact, so this is an equality check, not a minimum.

**Canonical JSON sorts keys by UTF-8 bytes, never by `String.compareTo()`**
(`CanonicalJson.java`). Java's natural string ordering is UTF-16 code-unit ordering, which diverges
from UTF-8 byte ordering for astral-plane characters — enough to break offline-proof verification
for a payload containing one.

**AES-GCM decryption fails closed** (`AesGcm.java::open`): a tag mismatch throws rather than
returning unauthenticated plaintext, and is surfaced as a distinct
`TamgaCheckoutException.DecryptionException` so callers can tell "wrong license key" from "possibly
forged file" (`EncryptedPayloadDecryptor.java::decrypt`).

Reporting a vulnerability: see [SECURITY.md](SECURITY.md). Do not open a public issue.

## Known gaps

This SDK is a protocol client, not a licensing-enforcement framework. These are deliberate
boundaries, not oversights.

**Left to your application**

- **Machine fingerprints.** No SDK in the fleet generates one. Producing a stable, device-specific,
  reasonably tamper-resistant fingerprint — and keeping it stable across reinstalls — is yours.
- **Embedding the account public key**, plus its rotation and key-id handling. Offline verification
  takes the key as a parameter; getting it into the binary is out of scope.
- **Persistence.** Nothing is written to disk. Storing `.lic`/`.machine` files, deciding when to
  refresh them, and securing the license key (keychain, DPAPI, file permissions) are yours. The
  only cache is the 60-second in-memory entitlement cache, which does not survive a restart.
- **Grace periods and offline policy.** How many days to run without a network, and how many
  validation failures to tolerate, are product decisions the SDK does not make.
- **Clock trust.** A user who moves the clock backwards can revive an expired file. Offline
  verification accepts an explicit `now`, so you can pass a server-supplied timestamp — but
  choosing to do so is up to you.
- **Enforcement.** A `ValidationCode` says what happened, not what your application should do
  about it.

**Server-side limitations this SDK inherits**

- **`.machine` files carry no signed expiry.** Only `.lic` files do. A machine file is bounded in
  practice by the `ttl` requested at checkout and by its fingerprint binding.
- **10 of the 24 `ValidationCode` values are unreachable.** All 24 are modelled for
  forward-compatibility, and `ValidationCode.reachable()` reports which. Do not build behaviour on
  an unreachable one.
- **Only four `Scope` fields are enforced** — product, policy, user, environment. The other four
  (`fingerprint`, `version`, `checksum`, `entitlements`) are sent, parsed, and then ignored.
- **The heartbeat window is a hardcoded 600s**, not driven by `policy.heartbeat_duration` despite
  that field existing. `HeartbeatScheduler` derives its interval from the real 600s.
- **`hasEntitlement` reads a single page** of 100 entitlements, the server maximum. Paginate
  `listEntitlements` yourself if a license may carry more.
- **No auto-update or release-check API, and no RFC 9421 response-signature verification.** Neither
  has a working server counterpart.

**Packaging**

- **No Android artifact.** The SDK is plain Java with no native code, and OkHttp is used precisely
  so it runs on Android — but no AAR is published and Android is not part of the CI matrix.
- **No asynchronous API.** Every endpoint method blocks. Wrap calls in your own executor if you
  need them off the calling thread.

## Documentation

- [tamga.sh](https://tamga.sh) — product documentation and account setup.
- [Javadoc](https://javadoc.io/doc/sh.tamga/tamga-sdk) — published API reference for each release.
- [`SECURITY.md`](SECURITY.md) — offline-format security contract and vulnerability reporting.
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — build, test and release workflow.
- [`CLAUDE.md`](CLAUDE.md) — architecture notes and the gotchas worth reading before changing
  anything under `crypto/`, `checkout/` or `proof/`.

## License

MIT — see [LICENSE](LICENSE).
