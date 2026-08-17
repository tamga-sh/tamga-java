# Security Policy

## Scope

`tamga-java` reimplements Tamga's offline verification cryptography natively in Java — JDK
built-ins for AES-256-GCM/HKDF-SHA256/ECDSA-P256/RSA, plus BouncyCastle's lightweight API scoped to
exactly Ed25519 (the one primitive the JDK itself doesn't support at this module's Java 11 target).
No JNI, no native library, no C code. See `CLAUDE.md`'s "Crypto Architecture" section for the full
rationale and primitive-by-primitive mapping. The highest-risk code lives in:

- [`src/main/java/sh/tamga/sdk/crypto/`](src/main/java/sh/tamga/sdk/crypto/) — Ed25519, AES-256-GCM, HKDF-SHA256, ECDSA-P256, and RSA (PKCS#1v1.5/PSS) verification primitives.
- [`src/main/java/sh/tamga/sdk/checkout/`](src/main/java/sh/tamga/sdk/checkout/) — `.lic`/`.machine` file parse/verify/decrypt.
- [`src/main/java/sh/tamga/sdk/proof/`](src/main/java/sh/tamga/sdk/proof/) — offline proof verification.
- [`src/main/java/sh/tamga/sdk/model/CanonicalJson.java`](src/main/java/sh/tamga/sdk/model/CanonicalJson.java) — the canonical JSON serializer the offline-proof signature covers.

Out of scope for now: the full `TamgaClient` HTTP-facing surface (entitlements, heartbeat
scheduling, the JSON:API error envelope) is still deferred — see the scope notes in `error/`,
`model/License.java`, `model/Machine.java`, and `model/ValidationCode.java`.

## Supported Versions

This SDK is pre-1.0; the latest published minor version receives security
fixes. Once a 1.x series exists, the two most recent minor versions will
receive security patches.

## Reporting a Vulnerability

**Do not open a public GitHub issue for a suspected security vulnerability.**

Report it privately via GitHub's [private vulnerability reporting](https://github.com/tamga-sh/tamga-java/security/advisories/new)
feature on this repository. Include:

- The affected file(s)/function(s) and, if possible, a minimal reproduction.
- Whether the issue is a verification bypass (a forged `.lic`/`.machine` file
  or offline proof that this SDK would incorrectly accept as valid), an
  information leak, a denial-of-service via malformed/adversarial input, or
  something else.
- The version (git commit or tagged release) you tested against.

You should receive an initial response within 5 business days. Confirmed
vulnerabilities will be fixed in a private branch and disclosed via a GitHub
Security Advisory alongside the patched release; we will credit reporters
who wish to be credited.

## What Counts as a Vulnerability Here

Given this SDK's actual attack surface (an offline file/proof verifier, not
a server), the highest-severity class of bug is **a verifier that accepts
something it should reject** — for example, a signature check computed over
the wrong bytes, a scheme dispatch that picks the wrong algorithm, or an
offline proof that verifies against a differently-serialized (but
semantically equivalent) payload.

## Key Derivation

Both offline file formats derive their AES-256-GCM key with HKDF-SHA256
(RFC 5869), implemented in
[`src/main/java/sh/tamga/sdk/crypto/Hkdf.java`](src/main/java/sh/tamga/sdk/crypto/Hkdf.java):

| File | salt | ikm | info | function |
|---|---|---|---|---|
| `.lic` | `tamga:license-file-key-v1` | the license key | `license-file` | `Hkdf.java::deriveLicenseFileKey` |
| `.machine` | `tamga:machine-file-key-v1` | the license key | the machine fingerprint | `Hkdf.java::deriveMachineFileKey` |

The two are never interchangeable: different salt and different `info`
produce different keys for the same license key.

The pre-v2 license-file transform — the license key's raw UTF-8 bytes
zero-padded or truncated to 32 bytes — has been **removed, not
deprecated**. The class that implemented it no longer exists, so no caller
can reach the weaker derivation. Any documentation, comment or third-party
write-up describing `.lic` key derivation as "not a KDF", "naive" or
"zero-padded" is describing a version of this SDK that is no longer
published.

## Offline License File Format v2 — Compatibility Warning

**v1 `.lic` files are rejected outright. There is no fallback path.** A
caller holding a `.lic` file issued before format v2 must re-issue it; this
is a real behavioral break, not a soft deprecation.

What v2 adds, and where it is enforced
([`src/main/java/sh/tamga/sdk/checkout/LicenseFile.java`](src/main/java/sh/tamga/sdk/checkout/LicenseFile.java)):

- `alg` must be exactly `base64+ed25519+v2` or `aes-256-gcm+ed25519+v2`.
  Anything else — including the v1 spellings without the `+v2` suffix —
  throws `UnsupportedAlgorithmException`
  (`LicenseFile.java::verify`).
- The signed payload carries a `meta` claims object: `iat`, `exp`, `jti`,
  `kid`
  ([`src/main/java/sh/tamga/sdk/model/LicenseFileClaims.java`](src/main/java/sh/tamga/sdk/model/LicenseFileClaims.java)).
  A payload with no `meta` is rejected as a pre-v2 file — the second line of
  defence behind the `alg` gate
  (`License.java::parseResourcePayloadWithClaims`).
- `exp` is enforced, not advisory, with a 60-second clock-skew tolerance
  (`LicenseFile.java::verifyWithClaims`, constant
  `CLOCK_SKEW_TOLERANCE_SECONDS`). The tolerance is deliberately small: the
  client's clock is under the attacker's control, so a generous allowance
  is a free extension on every expired file.

This is what v1 got wrong. In v1 the requested `ttl`/`expiry` lived only in
the JSON:API envelope around the certificate, never inside the signed bytes,
so a 24-hour trial file was cryptographically valid forever — keeping or
redistributing the raw certificate string bypassed any envelope-based check.

An application that does not trust the local clock can pass a
server-supplied timestamp to `LicenseFile.verifyWithClaims(byte[], String,
long)` instead. Expiry is enforced either way; it is not opt-in.

## Known, Deliberate Non-Vulnerabilities

The following are intentional design decisions, not bugs, and reports about
them will be closed without action (though corrections/clarifications are
welcome):

- The certificate's `alg` field is read but not itself covered by the
  signature (only `enc` is signed) — used solely to choose
  AES-GCM-decrypt vs. plain-decode, never to select the signature verifier
  (that's always Ed25519 for license files, and always the caller-supplied
  scheme, never the file's own `alg`, for machine files — see
  `MachineFile.java::verify`). Flipping `alg` on an otherwise-validly-signed
  file fails closed in both directions (ciphertext misread as plaintext JSON
  fails to parse; plaintext misread as `nonce||ciphertext||tag` fails the
  AES-GCM tag check) — this is an accepted wire-format tradeoff shared with
  the other Tamga SDKs, not an oversight. For license files the blast radius
  is smaller still, since `alg` is additionally pinned to two exact literals
  before anything else happens (`LicenseFile.java::verify`).
- `MachineFile` treats "`alg` does not contain `aes-256-gcm`" as "plain",
  rather than matching a fixed literal set
  (`MachineFile.java::verifyAndDecrypt`). `alg` is unauthenticated either
  way, and both branches fail closed, so this is security-neutral; it exists
  because a plain non-Ed25519 machine file's `alg` is just its signature
  suffix (`rsa-sha256`, `ecdsa-sha256`) with no encoding prefix at all.

## Rate Limiting (HTTP 429)

The server does return HTTP 429, and the Tamga SDK fleet handles it:
`Retry-After` is parsed and capped, backoff is jittered exponential, and
auto-retry is scoped to `GET` plus five safe `POST` actions (`validate`,
`validate-key`, `check-in`, `check-out`, `ping`) — resource creation is
deliberately excluded.

**This SDK does not implement any of that yet, because it does not ship an
HTTP transport at all** (`src/main/java/sh/tamga/sdk/Transport.java` is a
stub). That is a gap in this SDK, not a statement about the server. Do not
read it as licence to assume 429 never arrives.
