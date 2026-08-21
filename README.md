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

<!-- x-release-please-start-version -->
```kotlin
// build.gradle.kts
dependencies {
    implementation("sh.tamga:tamga-sdk:1.4.1")
}
```

```groovy
// build.gradle
dependencies {
    implementation "sh.tamga:tamga-sdk:1.4.1"
}
```

```xml
<!-- pom.xml -->
<dependency>
  <groupId>sh.tamga</groupId>
  <artifactId>tamga-sdk</artifactId>
  <version>1.4.1</version>
</dependency>
```
<!-- x-release-please-end -->

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

Activating a machine. An over-limit license is reported the same way whether the server refuses
the create outright or lets it through and reports the limit at validation; in the second case the
machine is deleted before the exception is thrown, so no seat is left consumed either way:

```java
import sh.tamga.sdk.HeartbeatScheduler;
import sh.tamga.sdk.error.TamgaApiException;
import sh.tamga.sdk.error.TamgaMachineOverLimitException;
import sh.tamga.sdk.model.ActivationResult;
import sh.tamga.sdk.model.CreateMachineOptions;
import sh.tamga.sdk.model.HeartbeatStatus;

try {
  ActivationResult activation = client.activateMachine(
      CreateMachineOptions.of(fingerprint, licenseId).withHostname("build-box"), null);

  HeartbeatScheduler scheduler = HeartbeatScheduler.builder(client, activation.machine().id())
      .onTick((machine, error) -> {
        // DEAD only means the last ping was older than the window. The row and its seat are
        // still there, and this ping just revived the machine -- so keep pinging through it.
        if (machine != null && machine.heartbeatStatus() == HeartbeatStatus.DEAD) {
          logStaleHeartbeat();
        }
        // A 404 from the ping is the only signal the row is really gone. Re-activate off that.
        if (error instanceof TamgaApiException.NotFoundException) {
          reactivate();
        }
      })
      .build();
  scheduler.start();
} catch (TamgaMachineOverLimitException e) {
  // No machine row survives, whichever of the two limit checks fired. The meta says which limit
  // was hit, always in validation-code terms; e.rolledBack() says whether one had to be deleted.
  showSeatLimitMessage(e.validationMeta().code());
} catch (TamgaActivationValidationException e) {
  // The machine was created but could not be validated — a network blip, say.
  // It still exists, so retry validation or clean it up.
  client.deleteMachine(e.machine().id());
}
```

Re-activating on a later launch, with the ping interval sized from the policy rather than the
600-second fallback:

```java
import sh.tamga.sdk.model.ActivationOptions;
import sh.tamga.sdk.model.Policy;

// A fingerprint is stable, so every launch after the first would otherwise get
// 409 FINGERPRINT_TAKEN. This resolves that to the machine already registered.
ActivationResult activation = client.activateMachine(
    CreateMachineOptions.of(fingerprint, licenseId),
    null,
    ActivationOptions.defaults().reuseTakenFingerprint(true));

// getLicensePolicy, not getPolicy: the standalone policy route needs a permission a
// license key does not hold. `policy(...)` sets the interval to a third of the window.
Policy policy = client.getLicensePolicy(licenseId);
HeartbeatScheduler scheduler = HeartbeatScheduler.builder(client, activation.machine().id())
    .policy(policy)
    .build();
scheduler.start();
```

Checking for an update. `204` means two different things server-side and the client cannot tell
them apart, so report the negative case as "nothing available to you", not "you are up to date":

```java
import sh.tamga.sdk.model.UpgradeCheckOptions;
import sh.tamga.sdk.model.UpgradeCheckResult;

UpgradeCheckResult upgrade = client.checkForUpgrade(
    UpgradeCheckOptions.of(productId, "darwin", "dmg", currentVersion, "stable"));

if (upgrade.updateOffered()) {
  offerUpdate(upgrade.release().version());
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

Like `.lic` files, machine files must be format **v2**: `alg` is `<encoding>+<signing-suffix>+v2`
(`base64` or `aes-256-gcm`, crossed with `ed25519`, `ecdsa-p256`, `rsa-sha256` or
`rsa-pss-sha256`), and a file without the `+v2` marker is rejected outright with no fallback. The
signing suffix is only cross-checked against the scheme you pass; it never selects the verifier.

The signed payload carries `meta` claims (`iat`/`exp`/`jti`/`kid`), and `exp` is enforced with a
60-second clock-skew tolerance — the same tolerance and the same
`TamgaCheckoutException.LicenseFileExpiredException` the `.lic` path uses. A checkout made without
a `ttl` produces a file with no `exp`, which genuinely never expires. Use `verifyWithClaims` to
read the claims and to supply a trusted timestamp instead of the local clock, which the user
controls:

```java
Machine.MachineWithClaims result = file.verifyWithClaims(
    LicenseScheme.ED25519_SIGN, publicKey, licenseKey, fingerprint, serverUnixSeconds);

result.claims().expiresAt();  // null when the checkout had no ttl
result.machine().fingerprint();
```

Supported schemes: `ED25519_SIGN` (and `NONE`, which defaults to Ed25519), `RSA_2048_PKCS1_SIGN`,
`RSA_2048_PKCS1_PSS_SIGN`, `ECDSA_P256_SIGN`. `RSA_2048_JWT_RS256` throws
`TamgaCheckoutException.SchemeNotSupportedException` — it is rejected server-side for machine files
and is deliberately not implemented here.

Public keys are accepted in whichever encoding the server hands you. Ed25519 is raw 32 bytes.
ECDSA-P256 is a raw 65-byte uncompressed point (what the account record stores) or X.509
`SubjectPublicKeyInfo` DER. RSA is PKCS#1 `RSAPublicKey` DER or SPKI — the server emits both for
the same key from different endpoints.

`MachineFile.validateTtl(int)` mirrors the server's `ttl` bounds (`> 0` and `<= 31536000`, i.e. 365
days) so a checkout request can fail fast client-side.

### Surviving a signing-key rotation

When an account rotates its Ed25519 signing key, a file signed **before** the rotation is still
authentic — but against the current key alone it fails with exactly the error a forged file
produces. Verifying against a *key set* keeps the two apart.

```java
import sh.tamga.sdk.checkout.LicenseFile;
import sh.tamga.sdk.checkout.SigningKeySet;
import sh.tamga.sdk.checkout.VerifiedLicenseFile;
import sh.tamga.sdk.error.TamgaCheckoutException;

// Pin the account's published keys — no network needed, and the path an embedded client has.
SigningKeySet keys = SigningKeySet.ofPublicKeys(currentKeyBase64, previousKeyBase64);

try {
  VerifiedLicenseFile verified =
      LicenseFile.parse(pem).verifyWithClaims(keys, licenseKey, serverUnixSeconds);

  verified.license();
  verified.claims();
  verified.key().isRetired();  // authentic, but issued before the last rotation
} catch (TamgaCheckoutException.UnknownSigningKeyException e) {
  // NOT a forgery: the file names a key this set does not hold. Refresh the key set.
  e.keyId();
  e.availableKeyIds();
} catch (TamgaCheckoutException.SignatureVerificationException e) {
  // The named key IS in the set and the signature still fails. Refuse the file.
}
```

`MachineFile` has the same pair of entry points, minus the scheme argument —
`verifyAndDecrypt(keys, licenseKey, fingerprint)` and
`verifyWithClaims(keys, licenseKey, fingerprint, nowUnixSeconds)`.

Three conditions are distinguishable, all subclasses of `TamgaCheckoutException`:

| Condition | Meaning | What to do |
|---|---|---|
| `UnknownSigningKeyException` | The file names a key the set does not hold. | Refresh the key set or ship an update — the file may well be genuine. |
| `SigningKeyNotPublishedException` | The file's `kid` is `keyId("")`, so the issuing account never published a public key. A subclass of the above. | Refetching cannot help; the account's key column has to be populated server-side. |
| `NoUsableSigningKeyException` | The set holds no usable Ed25519 key at all. | Check what was pinned or fetched. An empty *published* set is normal for an account that has never rotated. |

Three things are worth knowing before building on this:

- **The keys do not have to come over the wire, and usually cannot.**
  `TamgaClient.listSigningKeys()` / `signingKeySet()` read
  `GET /v1/accounts/{accountId}/signing-keys`, which requires the `account.read` permission — a
  license-key credential does not hold it and gets `403`. Pin the public keys instead. An offline
  verifier that only works while it has a network is not offline.
- **Key sets are Ed25519-only.** Only Ed25519 keys are ever published or rotated, so
  `MachineFile`'s key-set entry points refuse an RSA- or ECDSA-signed file rather than guessing.
  Verify those with the license's own scheme and a single public key.
- **Signatures are checked before the `kid` claim is read.** Every key in the set is tried against
  the signature first; the claim — which lives inside the signed payload — is only read once they
  have all failed, and only to choose which error to report. It selects from keys you already
  trust and can never introduce one.

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
- **Deciding what to do with a machine whose activation could not be validated.** If
  `activateMachine` creates the machine and the validation call then fails, the machine is handed
  back on `TamgaActivationValidationException` rather than deleted — a network blip is not a verdict
  about the license. Retry the validation, or delete it.
- **Clock trust.** A user who moves the clock backwards can revive an expired file. Offline
  verification accepts an explicit `now`, so you can pass a server-supplied timestamp — but
  choosing to do so is up to you.
- **Enforcement.** A `ValidationCode` says what happened, not what your application should do
  about it.

**Server-side preconditions**

- **License-key authentication is off unless the policy enables it.** `AuthTransport.licenseKey`
  requires the license's policy to set `authentication_strategy` to `LICENSE` or `MIXED`. It
  defaults to `TOKEN`, and `NONE` is refused the same way, so a correct key answers
  `401 LICENSE_NOT_ALLOWED` (`TamgaApiException.LicenseNotAllowedException`) on every call until
  an operator changes the policy. It is a configuration precondition — not a retryable failure,
  and not a reason to re-prompt for the key. A suspended license is refused with
  `401 LICENSE_SUSPENDED`, and an expired one whose policy uses `REVOKE_ACCESS` with
  `401 LICENSE_EXPIRED`; under the other three expiration strategies an expired license still
  authenticates and validation reports `EXPIRED`.

**Server-side limitations this SDK inherits**

- **`.machine` file expiry is enforced entirely client-side.** The file does carry a signed `exp`
  (this SDK used to state it did not, and never read it — a machine file consequently verified
  forever), but the server never re-checks an already-issued offline file, so the `ttl` you
  requested at checkout is only as binding as the client that reads it.
- **8 of the 24 `ValidationCode` values are unreachable.** All 24 are modelled for
  forward-compatibility, and `ValidationCode.reachable()` reports which. Do not build behaviour on
  an unreachable one. `ENTITLEMENTS_MISSING` and `FINGERPRINT_SCOPE_MISMATCH` moved into the
  reachable set once the server started enforcing those two scope fields.
- **Six `Scope` fields are enforced** — product, policy, user, environment, and now also
  `fingerprint` and `entitlements`, which used to be parsed and ignored. `entitlements` takes
  entitlement *codes*, compared case-insensitively, and is satisfied by policy-inherited
  entitlements too.
- **`Scope.withVersion` / `withChecksum` are deprecated and no longer sent.** The server rejects
  the entire validate call with `422 SCOPE_NOT_SUPPORTED` when either field is present, so a
  caller who sets one would get no verdict at all. Dropping them degrades that to a validate which
  simply does not apply the constraint.
- **The heartbeat window is policy-driven; 600s is only the fallback.** The server's effective
  window is `policy.heartbeat_duration` when the policy sets it, and 600s only when it is null.
  **`HeartbeatScheduler`'s default interval is still computed against the 600s fallback**, so on a
  policy with a shorter window the default ping rate is too slow and machines will read `DEAD`
  between pings. Read the policy and size the interval from it —
  `.policy(client.getLicensePolicy(licenseId))` on the builder does both. Use `getLicensePolicy`,
  not `getPolicy`: the standalone policy route needs a permission a license key does not hold and
  answers `403`, while the nested one is authorised as a license read.
  `Machine.nextHeartbeatAt()` is not a substitute — it reveals the true window only on
  `GET /machines/{id}`, the machine list, `check-out` and `generate-offline-proof`, while
  `create`/activate, `ping-heartbeat`, `reset-heartbeat` and `PATCH /machines/{id}` report the
  600s fallback, and nothing on the wire says which kind you are holding.

  **The ping interval is floored at one second** — `HeartbeatScheduler.MINIMUM_INTERVAL`, applied
  by `Builder.interval(...)`, `intervalForWindow(...)` and the process scheduler alike. A `500ms`
  `Duration` becomes `1s`; `45s` is untouched. The parameter is a `Duration` while the policy
  field is `heartbeat_duration` in **seconds**, so a hand-rolled unit conversion lands in the
  clamped range by ordinary mistake. It is a floor rather than a check on what
  `ScheduledExecutorService.scheduleAtFixedRate` refuses, because that method rejects a period of
  `0` and honours a period of `1` *exactly*, at ~1000 pings a second — a rule guarding only what
  the runtime refuses would clamp `0` and wave `1` through, which describes where a number came
  from rather than what it does. Null, zero and negative still mean "unspecified" and keep the
  `DEFAULT_INTERVAL` fallback.

  ⚠️ **The server judges liveness on truncated whole seconds**, which is easy to restate
  pessimistically. `heartbeat_status_within` compares
  `(now - last_heartbeat_at).num_seconds() <= window_secs`, and chrono's `num_seconds()`
  truncates, so a machine reads `DEAD` only once its age reaches `window_secs + 1` seconds — every
  window carries one free second. A 1s window is therefore served comfortably by a 1s ping (2s of
  slack, not zero), which is what makes a flat floor safe on short windows. What the floor *does*
  cost is the `/3` divisor's promise of two tolerable consecutive losses: `heartbeat_duration` 3
  is the first window where floor and divisor agree, 2 keeps one spare ping, 1 keeps none, and
  steady state holds all three. The only window it cannot hold is `0`, whose entire grace *is*
  that free second; chasing it would need a ~333ms ping, tying this SDK's request rate to a
  truncation artifact rather than a protocol guarantee, so it deliberately does not. A negative
  window is unserveable at any rate. The whole interaction is pinned window by window in
  `SchedulerWindowTest`.
- **A heartbeat scheduler must never stop on a status — any status.** The only terminal signal
  from a ping is a **404** (`TamgaApiException.NotFoundException`), which means the row is gone;
  hang re-activation off that. A stale machine is always one successful ping away from `ALIVE`,
  because the ping write is a bare `SET last_heartbeat_at = NOW()` with no resurrection check, so
  stopping is what would actually lose it.
- **A ping response can never report `DEAD`.** `ping-heartbeat` writes `last_heartbeat_at = NOW()`
  and then derives `heartbeat_status` from that same timestamp, so it always answers `ALIVE` or
  `RESURRECTED`. An earlier version of the bullet above framed the keep-pinging rule around "a
  `DEAD` reading from a ping" — the rule is right, but that observation cannot happen on that
  route. `reset-heartbeat` and `create` likewise only ever yield `NOT_STARTED`, and `validate`
  never returns `HEARTBEAT_DEAD` at all.
- **`DEAD` is still a real server state**, just not one a ping shows, and it does not mean the
  machine was culled. It means only that the last ping is older than the window: the server
  computes it from `last_heartbeat_at` alone and never consults `policy.require_heartbeat`, which
  defaults to `false` and is exactly what the cull job requires before it removes anything — so on
  a default policy nothing is ever culled and a machine sits in `DEAD` indefinitely with its row
  and its seat still in place. In this SDK it surfaces only through the checkout-family reads,
  which resolve the machine through a policy-joined query: `MachineFile.verifyAndDecrypt` (from
  `checkOutMachine`) and `generateOfflineProof`. `getMachine` and `listMachines` now show it
  directly, as does `updateMachine` — a write, but one that resets no heartbeat column — so a
  `case DEAD` branch against those is reachable rather than dead code.
- **The entitlements listing does not paginate at all.** It is a union of directly attached and
  policy-inherited rows, which one keyset cursor cannot describe, so the server accepts
  `page[after]` and never reads it. `listEntitlements` does not send it and always reports a null
  next cursor; `limit` still works, capped at 100. A license with more than 100 effective
  entitlements cannot be enumerated completely, which also bounds `hasEntitlement`: a `true` is
  always authoritative, a `false` only below that ceiling. Keyset paging *does* work on
  `listComponents`.
- **An inherited entitlement is not fetchable by id.** `Entitlement.inherited()` flags the ones a
  license holds through its policy; `getEntitlement` resolves direct attachments only and answers
  404 for the rest, so list-then-get-each is not a valid pattern here.
- **`resetHeartbeat` and `generateOfflineProof` always fail for a license key.** Both are gated on
  role rather than permission and need an admin, developer, product or environment token. Since
  `resetHeartbeat` is the only server-side way to unstick a wedged heartbeat job, that recovery
  belongs to an operator, not to the embedded client.
- **Process rows are never reaped, so delete them yourself.** The server's reaper for expired
  process registrations is not wired up: a row created by `createProcess` outlives the process it
  describes and keeps counting against the license's process limit until something deletes it. Use
  `deleteProcess`, or `ProcessHeartbeatScheduler.dispose()`, which stops pinging and deletes in one
  call. `close()` deliberately only stops the timer, so a try-with-resources block never destroys
  server state on its own.
- **`listMachines` pages by offset; every other listing here does not.** It takes a page number and
  size and returns `OffsetPage`, whose `hasNextPage()` is the server's own answer. `listComponents`
  and `listMachineProcesses` are keyset and return `Page`, where the cursor is synthesized from a
  full page. They are not interchangeable.
- **There is no fingerprint filter on the machine collection.** The free-text filter is a substring
  match across name, hostname *and* fingerprint, so it narrows but never identifies.
  `findMachineByFingerprint` sends it and then compares the fingerprint exactly on the rows that
  come back; a machine merely containing the fingerprint in its hostname is not a match.
- **Re-activation is opt-in, and scoped to the license.** A stable fingerprint means an application
  that activates on every launch gets `409 FINGERPRINT_TAKEN` on every launch after the first.
  `activateMachine(options, scope, ActivationOptions.defaults().reuseTakenFingerprint(true))`
  resolves that to the existing machine instead. A fingerprint held under a *different* license —
  which the wider uniqueness strategies permit — still raises the conflict: that case is precisely
  the seat-sharing the server refuses, and a machine resource carries no license id, so returning
  one would leave you pinging a machine your license does not own. A reused machine is never rolled
  back on an over-limit verdict, because its seat predates the call.
- **`getLicense`, `getMachine` and `updateMachine` are not scoped to your own license.** The server
  authorises them on a permission alone and applies no license-scope check, so a credential that
  can call them reaches every license and machine in the account — and `getLicense` returns each
  license's `key` in plain text. That is a server-side gap reported upstream; no client can close
  it. Do not build a "read your own license" feature on it and describe it as isolated.
- **`getPolicy` always fails for a license key.** It needs the `policy.read` permission, which a
  license token does not carry, so it answers `403`. `getLicensePolicy(licenseId)` reaches the same
  resource through a route authorised as a license read and works.
- **`updateMachine` cannot clear a field.** The server merges with `COALESCE`, so an omitted field
  and an explicit null both mean "leave unchanged". It is also the one write whose response can
  still report `DEAD`, and whose `nextHeartbeatAt()` is the 600s fallback rather than the policy
  window.
- **`quickValidate` writes `last_validated_at`** — unless the request carries an `Origin` header,
  in which case the server skips the write and returns a byte-identical response, so the caller
  cannot tell. This SDK never sets `Origin`, but a proxy that adds one silently disables the
  write. `validateById` with `withSkipTouch(true)` is the only reliably side-effect-free check.
- **Machine `memory` and `disk` are megabytes, not bytes.** Reporting bytes inflates the license's
  running total by a factor of about a million and makes the next activation on that license fail
  with `MEMORY_LIMIT_EXCEEDED`.
- **The upgrade check cannot tell you that you are up to date.** `checkForUpgrade` wraps
  `GET /releases/actions/upgrade`, which answers `204` **both** when no newer release exists and
  when one exists that this license is not entitled to — deliberately, so a refusal cannot leak
  the existence of a build the caller may not have. `UpgradeCheckResult.updateOffered() == false`
  therefore means *no update is available to you*, never *you are on the latest version*, and
  there is no client-side way to separate them. A suspended license is the exception: it comes
  back as `403` rather than being folded into the `204`. No download URL is returned — the
  artifact route exists but no credential this SDK issues may use it. RFC 9421
  response-signature verification is still not implemented.

**Transport hardening**

- **Redirects are not followed** by the client this SDK builds for you. The API never
  legitimately redirects, and following one is unsafe: OkHttp strips the `Authorization` header on
  a cross-origin redirect but does *not* strip a `Cookie` header, which is how
  `AuthTransport.sessionCookie` sends its credential. Supplying your own `OkHttpClient` opts out
  of this, and then the redirect policy is yours.
- **Response bodies are capped at 32 MiB.** A timeout bounds how long a response may take, not how
  large it may be.
- **The `x-ratelimit-*` headers are surfaced, but only on the error path.** The server's rate-limit
  middleware writes all four -- `limit`, `remaining`, `reset` and `window` -- onto every response it
  handles, and they arrive as `ResponseMetadata.rateLimit()`, a `RateLimitInfo`. Like the rest of
  that bag they reach you through `TamgaApiException.responseMetadata()`, so a successful call
  discards them: the useful reading is the 429 that survives the retry budget. Absence is normal
  and is `RateLimitInfo.ABSENT` (`-1`), never `0` -- rate limiting is disabled outright when the
  server cannot build its Redis pool, and then no header is written at all. `reset` is an absolute
  Unix timestamp, not a delay.
- **Exception messages embed server-supplied text.** `TamgaApiException.getMessage()` includes the
  server's `detail`. Treat it as untrusted when logging.

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
