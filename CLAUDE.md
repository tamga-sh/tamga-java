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

**Current state: complete.** `crypto/` (Ed25519, AES-256-GCM, HKDF-SHA256, ECDSA-P256, RSA
PKCS1/PSS), `checkout/` (`LicenseFile`, `MachineFile`), `proof/` (`OfflineProof` +
`CanonicalJson`), and the HTTP surface (`TamgaClient`'s 31 endpoint methods, `Transport`,
`AuthTransport`'s seven forms, the JSON:API error model, the entitlement cache, both heartbeat
schedulers, and the full `Policy`/`ValidationCode` types) are all implemented and tested — 478
tests, ~97.7% instruction coverage against an 80% gate.

The normative description of the network surface is
`../docs/api-client-contract.md`, derived from `tamga-go`. Behavioural changes to
`TamgaClient`/`Transport` should update that document too, or the fleet drifts apart again.

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
├── gradle/wrapper/                         # pinned Gradle wrapper (9.7.0; build JDK = Temurin 17)
├── config/
│   ├── checkstyle/google_checks.xml        # Google Java Style, wired via the checkstyle plugin
│   └── spotbugs/exclude.xml                # near-empty; see file header before adding excludes
├── src/
│   ├── main/
│   │   └── java/sh/tamga/sdk/
│   │       ├── TamgaClient.java            # entry point; builder requires accountId + auth
│   │       ├── Transport.java              # OkHttp-based transport — hand-rolled
│   │       ├── AuthTransport.java          # the seven auth forms, as static factories
│   │       ├── EntitlementCache.java       # 60s TTL, keyed by license id
│   │       ├── HeartbeatScheduler.java     # 600s default window (Process one is a fixed 30s)
│   │       ├── model/                      # License, Machine, Component, Process, Entitlement,
│   │       │                               #   Policy, Scope, ValidationCode/Meta, Page,
│   │       │                               #   CanonicalJson, TamgaJsonMapper
│   │       ├── crypto/                     # Ed25519, AesGcm, Hkdf, Ecdsa, Rsa
│   │       ├── checkout/                   # LicenseFile, MachineFile — PEM parse/verify/decrypt
│   │       ├── proof/                      # OfflineProof — RSA-2048 PKCS#1v1.5, exact-order payload
│   │       └── error/                      # TamgaCheckoutException, TamgaApiException + 13 typed
│   │                                       #   subclasses, TamgaTransportException
│   └── test/java/sh/tamga/sdk/             # JUnit 5 + AssertJ, mirrors src/main package-for-package
└── .github/workflows/
    ├── ci.yml                              # checkstyle + spotbugs + check (JUnit5/JaCoCo) + codecov
    └── release.yml                         # release-please, then publishToMavenCentral gated on
                                            #   its release_created output (NOT on a
                                            #   `release: published` trigger — see the workflow
                                            #   header for why that never fired)
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

- **The upgrade-check endpoint works now, and is implemented** as `checkForUpgrade`. The previous directive here — "`GET
  /releases/actions/upgrade` crashes at runtime and there is no download route" — was verified
  false against the server and is deleted; it was forbidding work that is buildable. The route is
  live, public (`OptionalAuth`), and enforces the product's distribution strategy. Real
  constraints to encode when it is implemented: an up-to-date caller gets **204 with an empty
  body**, so an "optional release" return type that cannot distinguish 204 from a decode failure
  is wrong; omitting `constraint` defaults to patch-only (`~x.y.z`); omitting `channel` matches
  **every** channel including alpha and dev, so require it at the API level; the handler uses a
  bare query extractor, so a malformed query answers **plain-text 400**, not JSON:API. The
  artifact-download route exists too, but every credential this SDK issues is currently refused by
  it (`artifact.download` is in no role's default permission set) — that one is genuinely blocked
  upstream.
- **Auth IS enforced server-side, and license-key auth is off by default.** The previous claim
  here ("no auth is enforced today") was false. Every endpoint this SDK calls is authenticated,
  and `Authorization: License <key>` additionally requires the license's policy to set
  `authentication_strategy` to `LICENSE` or `MIXED`. The column **defaults to `TOKEN`**, and
  `NONE` behaves the same way, so an otherwise perfect key answers `401 LICENSE_NOT_ALLOWED` on
  every call until an operator changes the policy. Treat that code as a configuration
  precondition, never as a retryable auth failure or a "wrong key" prompt. Two sibling front-door
  rejections apply to the same credential: `401 LICENSE_SUSPENDED` for a suspended license, and
  `401 LICENSE_EXPIRED` for an expired one whose policy uses `REVOKE_ACCESS` (under the other
  three expiration strategies an expired license still authenticates and validate reports
  `EXPIRED`). All three, plus the four create-time limit codes and `TOO_MANY_PROCESSES`, are
  mapped to their own `TamgaApiException` subclasses.
- **16 of 24 `ValidationCode` values are reachable, and the scope story has changed twice over.**
  Model all 24 with lenient/unknown-value decoding (`@JsonEnumDefaultValue` on `UNKNOWN`), but
  don't build UI/UX around the 8 that are declared and never emitted (`BANNED`, `TOO_MANY_USERS`,
  `HEARTBEAT_DEAD`, `HEARTBEAT_NOT_STARTED`, `COMPONENTS_SCOPE_MISMATCH`,
  `CHECKSUM_SCOPE_MISMATCH`, `VERSION_SCOPE_MISMATCH`, and `NOT_FOUND`, which surfaces as an
  HTTP 404 instead of this code). The `Scope` fields split three ways now:
  - `product`/`policy`/`user`/`environment` — enforced, as always.
  - `entitlements`/`fingerprint` — **now genuinely enforced**, so `ENTITLEMENTS_MISSING` and
    `FINGERPRINT_SCOPE_MISMATCH` are real verdicts. `entitlements` takes entitlement **codes**
    (not the UUIDs the attach/detach bodies use), compared case-insensitively and de-duplicated,
    satisfied by policy-inherited entitlements as well as direct ones; an empty list asserts
    nothing. `fingerprint` matches any machine of the license regardless of heartbeat status.
  - `version`/`checksum` — **not ignored: fatal.** The server answers `422 SCOPE_NOT_SUPPORTED`
    the moment either is present, before any validation runs, so the caller gets no verdict at
    all. `Scope.toRequestMap()` therefore drops both and the two `with*` methods are
    `@Deprecated`; that degrades a caller who sets one to a working validate instead of a hard
    failure. Do not add a typed `SCOPE_NOT_SUPPORTED` error — the generic API-error path covers
    it, and the fleet decision was to keep this patch-safe everywhere.
- **HTTP 429 is live and must be handled once `Transport` exists.** The server returns it, and the
  rest of the SDK fleet already ships the handling: parsed and capped `Retry-After`, jittered
  exponential backoff, auto-retry scoped to `GET` plus the seven safe `POST` actions (`validate`,
  `validate-key`, `check-in`, `check-out`, `ping`, `ping-heartbeat`, `reset-heartbeat`), with
  resource creation deliberately excluded. `ping-heartbeat`/`reset-heartbeat` were previously
  excluded on the reasoning that suffix matching must not confuse them with `/actions/ping` —
  true, which is why they are listed in their own right rather than folded in. Both are bare
  `SET last_heartbeat_at = NOW()` writes with no seat-burning risk, and the rate limiter buckets
  per **route pattern**, so an entire fleet shares one budget on that path: a dropped 429 there
  leaves the machine reading `DEAD` until some later ping lands (a staleness report, not a cull —
  see the `DEAD` bullet below). `RateLimitTest` now pins the opposite of what it used to.
  Implemented in `Transport`: see `isRetryable`, `parseRetryAfterSeconds` and `retryDelayMillis`,
  and `RateLimitTest` for the regressions that pin the policy — in particular that `POST
  /machines` makes exactly one call.
- **Machine creation enforces the policy's limits — through the overage strategy.** The old
  directive ("no policy limit is checked at creation; limits surface only through validation") was
  false. `POST /machines` rejects with `422 MACHINE_LIMIT_EXCEEDED` / `CORE_LIMIT_EXCEEDED` /
  `MEMORY_LIMIT_EXCEEDED` / `DISK_LIMIT_EXCEEDED` — but that check runs through
  `policy.overage_strategy`, so under `ALLOW_ACCESS` or `ALLOW_1_25X_OVERAGE` the create still
  succeeds and the limit only appears at validate. **Both paths are live**, which is why
  `activateMachine` keeps the create→validate→rollback sequence *and* catches the create-time 422.
  It normalizes the create-time code onto the validation vocabulary
  (`ValidationCode.fromMachineLimitErrorCode`) and throws the same
  `TamgaMachineOverLimitException` either way, so product code never has to handle two sets of
  names for one condition; `rolledBack()` says which path ran. Uniqueness is checked *before* the
  limits, so a re-activation is `409 FINGERPRINT_TAKEN` and must never be translated into an
  over-limit verdict — telling a customer to buy seats for a machine they already licensed is the
  exact failure that ordering exists to prevent.
- **Machine `memory` and `disk` are MEGABYTES, not bytes.** Both the resource model and the
  create-options builder said bytes. A caller reporting 16 GiB as `17179869184` inflates the
  license's `machines_memory_count` by a factor of 1,048,576 and the *next* activation on that
  license fails with `MEMORY_LIMIT_EXCEEDED` — a bug that only ever shows up on someone else's
  machine.
- **The entitlements listing does not paginate.** `GET /licenses/{id}/entitlements` is a union of
  directly attached and policy-inherited rows, which one keyset cursor cannot describe, so the
  server reads `page[after]` into a field it never uses (and would reject a non-UUID cursor
  outright). `listEntitlements` therefore never sends the parameter and always reports
  `nextCursor == null`; a license with more than 100 effective entitlements **cannot be
  enumerated completely** through this route, so a `false` from `hasEntitlement` is only
  authoritative below that ceiling. Two related items on the same route: entitlement resources
  carry an `inherited` flag (exposed as `Entitlement.inherited()`, `null` on responses that do not
  send it) — an inherited entitlement cannot be detached and **`getEntitlement` answers 404 for
  it**, so list-then-get-each is not a valid pattern here. `/machines/{id}/components` is
  different: keyset paging genuinely works there, do not "fix" it to match.
- **Never let the server pick the page size.** Its default is 25, these listings carry no
  `meta.page` and no `links`, and the only end-of-list signal is a page shorter than a limit the
  client already knows — so an omitted `limit` truncated silently at 25 with no cursor to
  continue from. `TamgaClient` sends the server maximum (100) explicitly when the caller does not
  choose one.
- **Quick-validate DOES touch the license, unless the request carries `Origin`.** The javadoc used
  to say the exact opposite. `GET /licenses/{id}/actions/validate` writes `last_validated_at`, and
  the server skips that write entirely when an `Origin` header is present — with a
  byte-identical response either way, so a caller cannot tell. That column decides whether a
  machine-less license reports `INACTIVE` and is the baseline for the check-in-overdue worker, so
  behind a proxy that adds `Origin` neither can ever move. This SDK never sets `Origin` itself;
  the only genuinely side-effect-free path is `POST validate` with `meta.skip_touch: true`.
- **`resetHeartbeat` and `generateOfflineProof` are always 403 for a license-key credential.**
  Both are gated on **role**, not permission: admin/developer/product/environment tokens only.
  `LicenseToken` fails even though it holds `machine.proofs.generate`. This matters most for
  `resetHeartbeat`, which is the only server-side way to unstick a wedged heartbeat job —
  presenting it to an embedded client as a recovery tool promises a recovery that cannot happen.
- **Two more legal strategy values exist:** `expiration_strategy` also accepts `REVOKE_ACCESS`
  (the only one of the four that blocks *authentication* rather than just the validation verdict),
  and `authentication_strategy` also accepts `NONE` (which behaves like `TOKEN` at the auth gate —
  it does not mean "no auth required"). Both are on `Policy.ExpirationStrategy` /
  `Policy.AuthenticationStrategy`.
- **The default request timeout must not equal the server's.** The server runs its own 30 s
  `TimeoutLayer`; matching it exactly makes the two race, and the local timeout usually wins,
  throwing away the server's `504` and with it the `X-Request-Id` that is the only handle support
  has on a slow request. `TamgaClient.DEFAULT_TIMEOUT` is 45 s for that reason.
- **`Tamga-Environment` request header does nothing server-side.** It's a planned EE feature with
  no request-parsing code path yet. Don't expose a client-facing "environment" option that implies
  it's honored today.
- **Fresh policies default to non-existent enum variants.** `overage_strategy` defaults to the
  literal string `"DENY_ACCESS"` and `heartbeat_resurrection_strategy` to `"NO_RESURRECTION"` —
  neither is a real variant. The server silently treats both as the "no restriction" variant
  (`NO_OVERAGE`/`NO_REVIVE`). Deserializers here must not crash on these strings and must not
  invent fake enum cases implying restrictive behavior the server doesn't actually have.
  Implemented: `Policy` keeps all three strategy fields as raw strings and exposes
  `effectiveOverageStrategy()`/`effectiveResurrectionStrategy()`/`effectiveCullStrategy()`
  normalizers. Read the raw field and you get a false negative; `"DENY_ACCESS"` in particular
  reads as maximally restrictive and means the opposite.
- **The machine heartbeat window IS policy-driven; 600s is only the fallback.**
  `Policy::effective_heartbeat_duration_secs` returns `policy.heartbeat_duration` when the policy
  sets it and 600 only when it is null, and the cull job's claim query agrees
  (`COALESCE(p.heartbeat_duration, 600)`). An earlier note here claimed the window was a hardcoded
  600s regardless of the policy — that was false and is reversed. The **process** window is
  genuinely hardcoded, at a fixed `INTERVAL '30 seconds'` with no resurrection grace period.
- **The default ping interval still assumes the 600s fallback, but no longer has to.**
  `HeartbeatScheduler.DEFAULT_INTERVAL` is a third of `WINDOW` (600s), so on a policy with a
  shorter `heartbeat_duration` the default ping rate is too slow and the machine goes `DEAD`
  between pings. Callers on such a policy must set the interval themselves. This SDK exposes no
  `getPolicy`/`getMachine`, so the window is not directly readable — and `Machine.nextHeartbeatAt`
  only helps on some routes. The server computes that field from the heartbeat window joined onto
  the machine row: `check-out` and `generate-offline-proof` resolve the machine through a
  policy-joined read and carry the true value (so `nextHeartbeatAt - lastHeartbeatAt` recovers the
  window there), while activate/create, `ping-heartbeat` and `reset-heartbeat` are bare writes with
  no join and always report the 600s fallback, as does `PATCH /machines/{id}`. Filed upstream as
  `tamga-api-internal#7`. The policy read now exists: `getLicensePolicy(licenseId)` returns the
  `Policy`, `Policy.effectiveHeartbeatWindow()` applies the same `heartbeat_duration`-else-600 rule
  the server does, and `HeartbeatScheduler.Builder.policy(...)` sizes the interval at a third of it.
  Use `getLicensePolicy`, **not** `getPolicy`: `policy.read` is absent from the `LicenseToken`
  permission set (`authz/mod.rs:236-261`), so the standalone policy route answers `403` under
  license-key auth while the nested one, gated on `license.read`, works. `DEFAULT_INTERVAL` is
  unchanged, so a caller who sets neither still gets the old behaviour.
- **A heartbeat scheduler must never stop on a status — any status.** The only row-is-gone signal
  is a **404 from the ping itself**, which is where re-activation belongs. A stale machine is
  always one successful ping away from `ALIVE`: the ping write is a bare
  `SET last_heartbeat_at = NOW()` with no resurrection check, so `heartbeat_resurrection_strategy`
  bounds the cull job and not the ping endpoint. Stopping, returning or short-circuiting the loop
  on a status is what strands the machine (tamga-python shipped exactly that bug).
  `HeartbeatScheduler` never gates a tick on the previous outcome, and
  `HeartbeatSchedulerTest.noHeartbeatStatusEndsTheLoopNotEvenDead` pins that.
- **A ping response can never report `DEAD` (M42).** Two directives have now been wrong here in
  opposite directions, so state the mechanism, not a scenario. The original ("DEAD means the row
  was culled, so re-activate instead of pinging") was false and was reversed. Its replacement kept
  the right rule but justified it with "a `DEAD` reading from a ping", which is also unreachable:
  `ping-heartbeat` writes `last_heartbeat_at = NOW()` and then derives `heartbeat_status` from that
  same timestamp (`heartbeat_status_within`, `machines/model.rs:124-146`), so the measured age is
  ~0 and the answer is always `ALIVE` or `RESURRECTED`. `reset-heartbeat` nulls the column
  (`NOT_STARTED`), `create` never sets it (`NOT_STARTED`), and `validate_license.rs` never
  constructs `ValidationCode::HeartbeatDead` at all. Any `case DEAD` branch in a tick callback is
  therefore dead code — reframe or drop the branch, but **do not** delete the enum constant or the
  model field, which are part of the wire model.
- **`DEAD` is real, and in this SDK it is reachable — through the checkout family.** It means only
  that the last ping is older than the window: `Machine::heartbeat_status*` derives it from
  `last_heartbeat_at` and never reads `policy.require_heartbeat`, which defaults to `FALSE` and is
  what the cull job requires (its claim query has `AND p.require_heartbeat`), so **on a default
  policy nothing is ever culled** and a machine sits in `DEAD` forever with its row and its seat
  still present. `check-out` and `generate-offline-proof` both resolve the machine via the
  policy-joined `queries::find_by_id`, so their serialized `heartbeat_status` is computed against
  the real window and can be `DEAD`: reachable here as `MachineFile.verifyAndDecrypt` (from
  `checkOutMachine`) and `OfflineProofResult.machine()`. Note the fleet-wide M42 note lists only
  ping/reset/create/validate and concludes `DEAD` is unobservable — that holds for those four
  routes and for SDKs without checkout, but **not** for tamga-java. `getMachine` and `listMachines`
  now show it directly, which is what makes a `case DEAD` branch in caller code reachable rather
  than dead.
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
- **Offline machine files must be format v2 as well, and they DO carry signed claims.** Earlier
  revisions of this file and of the SDK stated the opposite; both were wrong, and the consequence
  was that a checked-out machine verified forever. `alg` is `<encoding>+<signing-suffix>+v2`
  (`base64`/`aes-256-gcm` × `ed25519`/`ecdsa-p256`/`rsa-sha256`/`rsa-pss-sha256`), parsed
  structurally by `checkout/MachineFileAlg` — split the encoding at the FIRST `+` and the version
  at the LAST, because both halves contain hyphens. A substring `contains()` test is not a parse:
  it accepts `base64+ed25519+v3` and `xbase64+ed25519+v2junk` too, which is how this SDK "passed"
  the v2 gate without ever checking for it. The signed payload carries the same
  `meta` claims a `.lic` file does and `MachineFile.verifyWithClaims` enforces `exp` with the SAME
  constant, `LicenseFile.CLOCK_SKEW_TOLERANCE_SECONDS` — never define a second one — raising the
  same `LicenseFileExpiredException`. A TTL-less checkout legitimately has no `exp`.
- **An encrypted `.machine` file's `enc` is `"<nonce_b64>.<ciphertext_b64>"` — two independently
  base64-encoded halves.** An encrypted `.lic` file's is a single `base64(nonce‖ciphertext‖tag)`
  blob. Same PEM envelope, same `{enc, sig, alg}` shape, same `aes-256-gcm+…+v2` prefix, genuinely
  different framing: the server builds them through different functions. The doc comment on
  `machine_file.rs`'s own encoder still describes the single-blob form and is stale, which is what
  led all eight SDKs in this fleet to implement it that way. Branch on the encoding prefix from
  `alg`, never on whether a dot happens to be present, and never decode any of it before the
  signature over the whole `enc` STRING has verified.
- **`Base64Codec`'s use of the STRICT decoder is load-bearing — do not "helpfully" relax it.**
  Reading the dot-separated `enc` as one blob is wrong everywhere, but whether it *fails* depends
  entirely on the decoder. A 12-byte nonce always encodes to exactly 16 unpadded base64 characters,
  so `nonce_b64 + cipher_b64` is always a multiple of 4 and a decoder that ignores out-of-alphabet
  characters silently drops the `.` and reconstructs `nonce‖ciphertext‖tag` byte-for-byte — the old
  12-byte slice then lands correctly by accident. Measured on all four encrypted fixtures: 16 + 896
  = 912 chars, and a lenient decode reproduces the exact plaintext every time. That is why the
  sibling CPython and Node SDKs appeared to work. Java is only where the bug was *visible*, because
  `java.util.Base64.getDecoder()` rejects the `.`. Switching any of this to `getMimeDecoder()` would
  re-hide it and quietly soften the nonce/ciphertext guards; `MachineFileTest` pins the strict
  behaviour for exactly that reason.
- **Public-key encodings differ per algorithm and per server endpoint — and ECDSA is never SPKI.**
  `accounts.ecdsa_public_key` holds `ecdsa_pair.public_key().as_ref()`, a raw 65-byte SEC1
  uncompressed point, so an SPKI-only ECDSA verifier cannot consume a real account key at all —
  that one was a genuine defect here. RSA is subtler and is NOT a defect: `accounts.public_key`
  holds `as_der()`, i.e. real SPKI (294 bytes), which this SDK already parsed, while
  `license_signing.rs`'s `extract_public_key` returns PKCS#1 `RSAPublicKey` (270 bytes) — the form
  the machine-file fixtures carry, and the form the server's own `verify` helper expects. Both are
  legitimate distribution paths, so `Ecdsa`/`Rsa` accept both encodings per algorithm rather than
  guessing which endpoint a caller used. The P-256 curve pin, the on-curve check and the
  exact-2048-bit modulus check apply on every path.
  Upstream note: the server test `rsa_public_key_is_spki_der` asserts only `len > 256`, which both
  270-byte PKCS#1 and 294-byte SPKI satisfy — so it passes while asserting the opposite of what its
  name claims, and cannot catch this discrepancy.
- **`RSA_2048_JWT_RS256` is rejected for machine files.** `MachineFile.verify` throws
  `TamgaCheckoutException.SchemeNotSupportedException` before attempting any verification — and
  before `alg` is even parsed — matching the server's `422 SCHEME_NOT_SUPPORTED`. No JWT
  verification path exists or should be added. The server emits the identical `rsa-sha256` suffix
  for this scheme and for `RSA_2048_PKCS1_SIGN`, so `alg` cannot identify the scheme even in
  principle; the caller's `LicenseScheme` is authoritative and `alg` is only ever a cross-check.
- **Machine-file tests must use server-produced fixtures.** `src/test/resources/machine-file-fixtures/`
  holds 12 files (4 schemes × plain/encrypted/expired) emitted by the server's own
  `encode_machine_file`, driven by `manifest.json` through `support/MachineFixtures` and
  `MachineFileFixtureTest`'s `@ParameterizedTest` — iterate the manifest, never hardcode names.
  Do not regenerate them from this repo: a self-encoded fixture reproduces whatever this repo
  believes the format to be, which is exactly how the three bugs above survived. `CheckoutFixture`
  remains fine for wiring/edge cases but is not evidence about the wire format. Note the valid
  fixtures carry a real one-hour `ttl`, so never let the wall clock reach `verifyWithClaims` for
  one — pass a timestamp derived from the file's own `exp`.
- **Offline-proof field order is load-bearing.** The RSA signature in `proof/OfflineProof.java`
  covers a specific server-produced key order, recursively alphabetical at every nesting level
  (matching `serde_json`'s `BTreeMap`-backed output) — NOT literal source/insertion order. See
  `OfflineProof.java`'s `CRITICAL:` note and `model/CanonicalJson.java`.
- **`check_in_interval`'s wire values are adverbs, not nouns.** The server's accepted set is
  `["daily", "weekly", "monthly", "yearly"]` (`policies/enums.rs:27`), pinned by the identical list
  in the `policies` table's `CHECK` constraint and by the server's own
  `enum_lists_match_the_database` test. This SDK mapped `Policy.CheckInInterval` to
  `day`/`week`/`month`/`year`, so **every real policy decoded to `null`** — invisible until the
  policy read endpoints landed and something finally saw the field on the wire. The fleet contract
  document says lowercase nouns too and is wrong on the same point. `fromWireValue` accepts both
  spellings; `wireValue()` returns the adverb.
- **`listMachines` pages by OFFSET, and it is the only listing here that does.** It takes
  `page[number]`/`page[size]` (aliases `page`/`limit`), default size 25, clamped to 100, and
  answers a real `meta.page`. `listComponents` and `listMachineProcesses` are keyset (`limit` +
  `page[after]`, no metadata, cursor synthesized from a full page), and `listEntitlements` is
  neither. Hence `OffsetPage` alongside `Page` — do not merge them. **The `meta.page` keys mix
  casings**: `number`, `size` and `total` are bare, `total_pages` is renamed to `totalPages`
  (`shared/list_query.rs`), so a decoder assuming one convention reads three fields and silently
  misses the fourth.
- **There is no fingerprint filter on the machine collection.** The accepted filters are
  `filter[license]`, `filter[owner]`, `filter[group]`, `filter[platform]` and the free-text
  `filter[q]` — and `filter[q]` is a case-insensitive `ILIKE %term%` across `m.name`,
  `m.hostname` **and** `m.fingerprint`. So it narrows, it does not identify:
  `findMachineByFingerprint` sends it and then compares `fingerprint` exactly on the rows that
  return. A machine that merely *contains* the fingerprint in its hostname is not the machine.
- **`getPolicy` is always 403 for a license key; `getLicensePolicy` is not.** `policy.read` is
  absent from the `LicenseToken` permission set (`authz/mod.rs:236-261`), and
  `GET /policies/{id}` requires it. `GET /licenses/{id}/policy` is gated on `license.read`
  instead, which the license token does hold. Both are exposed, and the Javadoc on each points at
  the other; anything running under a license key wants the nested one.
- **`PATCH /machines/{id}` is the counterexample to the write-vs-read rule.** The durable form of
  the `DEAD` rule is "a response built off a write it just performed cannot say `DEAD`" — this
  route is a write that can, because its `UPDATE ... RETURNING` sets none of the heartbeat columns,
  so the status is judged against a clock it did not reset. The same `UPDATE` does not join
  `policies`, so its `next_heartbeat_at` is the 600s fallback. A machine from this route is
  therefore unusable for sizing a heartbeat interval, and `case DEAD` against it is reachable.
- **Machine and license reads are not confined to the caller's own license.** No machine route
  applies `require_license_scope`, and neither does `GET /licenses/{id}`, which additionally
  returns `attributes.key` in plain text. A credential with `license.read`/`machine.read` can
  therefore read every license and machine in the account. Reported upstream; no client can close
  it. **Do not describe `getLicense`/`getMachine` as scoped** — the Javadoc on both says so
  explicitly for that reason.
- **The release resource is camelCase, alone in this API.** `ReleaseAttributes` carries
  `#[serde(rename_all = "camelCase")]`, which no other serializer does, so the owning product
  arrives as `productId`, not `product_id`. Reading the house style yields `null` and nothing else
  complains. The two timestamps are renamed individually on top of that and stay
  `created`/`updated`. `EndpointModelsTest` pins both halves.
- **`GET /v1/health` is not under the account prefix and is not JSON:API.** It answers a flat
  `{status, version, uptime_secs}`, so it must not go through the envelope decoder. Reaching it at
  all needed `Transport` to be able to build a URL without `/v1/accounts/{accountId}` — that
  unconditional prefix, not the server, is why no SDK in this fleet could call it. Diagnostic
  value: it is exempt from both the auth gate and the host-header check, so if every other call is
  answering `403` "The Host header does not match any configured host" and this one succeeds, the
  fault is `TAMGA_ALLOWED_HOSTS`, not the credential. The converse does not hold.
- **Nothing reaps process rows, so `deleteProcess` is not optional.** The server's reaper for
  expired processes is dead code, so a row created by `createProcess` outlives the process it
  describes and keeps counting against `TOO_MANY_PROCESSES`. An application that registers a
  process per run and never deletes one accumulates rows until activation fails on a limit no
  running process is using. `ProcessHeartbeatScheduler.dispose()` pairs stop-and-delete;
  `close()` deliberately does not delete, because it runs implicitly at the end of a
  try-with-resources block and a scoped block must not silently destroy server state.
- **`Component` and `Process` responses ARE JSON:API-enveloped** — `{"data":{"type","id",
  "attributes":{…}}}` — even though their *request* bodies are flat. tamga-dotnet deserialized
  those bodies straight into its model and returned empty ids and fingerprints on every call, with
  its own fixtures encoding the wrong shape so CI stayed green. This SDK decodes them through
  `Component.fromResourceNode`/`Process.fromResourceNode`, which read `id` off the resource and
  the rest off `attributes`, and its fixtures carry the enveloped shape and assert real values —
  so a regression to flat decoding fails the tests rather than passing quietly. Verified, not
  assumed; do not "simplify" either decode path.

## Testing

- **JUnit 5 + AssertJ**, run via `useJUnitPlatform()`. `src/test/java/sh/tamga/sdk/` mirrors
  `src/main/java/sh/tamga/sdk/` package-for-package. Mockito is declared as a dependency but is
  still not used by any test, and that is deliberate: the crypto/checkout/proof suites use real
  generated keys and signatures, and the client/transport suites run against a real loopback
  `MockWebServer` rather than a mocked round-tripper — mirroring how `tamga-go` tests against
  `net/http/httptest`. Header construction, URL escaping and retry behaviour are only meaningful
  when something actually parses the bytes.
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
  deliberately). The Gradle wrapper itself is pinned to **9.7.0**, one line above the minimum
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
