# sh.tamga:tamga-sdk

[![Maven Central](https://img.shields.io/maven-central/v/sh.tamga/tamga-sdk)](https://central.sonatype.com/artifact/sh.tamga/tamga-sdk)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Official Java SDK for Tamga. Integrate license activation, offline verification, and machine
management into your Java applications.

**What ships today: offline verification only.** Everything under `crypto/`, `checkout/` and
`proof/` is implemented and tested. The HTTP client surface — `TamgaClient` and `Transport` — is
still an empty scaffold, so this SDK cannot yet talk to the API. See [Known gaps](#known-gaps).

## Install

Requires Java 11 or newer. The published bytecode target is Java 11; the build toolchain is
Temurin 17.

```kotlin
// build.gradle.kts
dependencies {
    implementation("sh.tamga:tamga-sdk:1.2.0")
}
```

```groovy
// build.gradle
dependencies {
    implementation "sh.tamga:tamga-sdk:1.2.0"
}
```

```xml
<!-- pom.xml -->
<dependency>
  <groupId>sh.tamga</groupId>
  <artifactId>tamga-sdk</artifactId>
  <version>1.2.0</version>
</dependency>
```

## Quickstart

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

- **No HTTP client.** `TamgaClient` and `Transport` are empty scaffolds — neither has any method.
  License activation, validation, check-in, checkout, heartbeat and entitlement calls are not
  implemented in this SDK yet. Offline artifacts must be obtained out of band for now.
- **No auth transports.** Because there is no HTTP layer, this SDK sends no credentials and
  implements no `Authorization` header construction.
- **No 429 handling.** The server does return HTTP 429, and the SDK fleet handles it with a parsed
  and capped `Retry-After`, jittered exponential backoff, and auto-retry scoped to `GET` plus the
  five safe `POST` actions (`validate`, `validate-key`, `check-in`, `check-out`, `ping`), with
  resource creation excluded. This SDK ships none of that, purely because it has no transport to
  put it in.
- **`ValidationCode`, `Policy` and `TamgaError` are empty types.** They exist so the package layout
  is stable; they carry no values or fields yet. `LicenseScheme` and `HeartbeatStatus` are real.
- **`License` and `Machine` model only the fields carried inside an offline file**, not the full
  API resource shape.
- **No Android artifact.** The SDK is plain Java with no native code, so it runs on Android, but no
  AAR is published and Android is not part of the CI matrix.

## Documentation

- [tamga.sh](https://tamga.sh) — product documentation and account setup.
- [Javadoc](https://javadoc.io/doc/sh.tamga/tamga-sdk) — published API reference for each release.
- [`SECURITY.md`](SECURITY.md) — offline-format security contract and vulnerability reporting.
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — build, test and release workflow.
- [`CLAUDE.md`](CLAUDE.md) — architecture notes and the gotchas worth reading before changing
  anything under `crypto/`, `checkout/` or `proof/`.

## License

MIT — see [LICENSE](LICENSE).
