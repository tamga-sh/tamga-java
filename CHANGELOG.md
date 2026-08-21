# Changelog

## [1.4.1](https://github.com/tamga-sh/tamga-java/compare/v1.4.0...v1.4.1) (2026-08-21)


### Bug Fixes

* keep the dependency snippets' version current at release ([8ab7624](https://github.com/tamga-sh/tamga-java/commit/8ab7624eff5c0cf6060da58d88ff032f0601113a))
* keep the dependency snippets' version current at release ([f109724](https://github.com/tamga-sh/tamga-java/commit/f109724311b8dd90ddef5ea845467844ec858c10))

## [1.4.0](https://github.com/tamga-sh/tamga-java/compare/v1.3.1...v1.4.0) (2026-08-21)


### Features

* model the four x-ratelimit headers the server really sets ([3026bdd](https://github.com/tamga-sh/tamga-java/commit/3026bddf6b9592a61b5992c6d0ea14f052846cf6))
* pin the rate-limit surface, absence included ([eaa2662](https://github.com/tamga-sh/tamga-java/commit/eaa2662706edcf27f4fc20db6b49cf7d5e5bc250))
* record what the rate-limit headers say and where they surface ([eac10ee](https://github.com/tamga-sh/tamga-java/commit/eac10eeaa4cf4206de85a4274637149bc9095e2c))
* surface the x-ratelimit headers on response metadata (M19) ([4912954](https://github.com/tamga-sh/tamga-java/commit/49129540ec9c463a9ec990e0b2351b32abf8a0ca))

## [1.3.1](https://github.com/tamga-sh/tamga-java/compare/v1.3.0...v1.3.1) (2026-08-21)


### Bug Fixes

* add the endpoint surface the SDK could not reach ([d22004e](https://github.com/tamga-sh/tamga-java/commit/d22004ead7078b56f315a3462882bc7ae3731612))
* add the endpoint surface the SDK could not reach ([3ad3c94](https://github.com/tamga-sh/tamga-java/commit/3ad3c94f7374cc2d8e6db714c44d18792f113d56))
* align the client with the current tamga-api server contract ([c2926e2](https://github.com/tamga-sh/tamga-java/commit/c2926e28cbf81512aef7d66ee384f6540452a80e))
* align the SDK with the current tamga-api server contract ([6b9cf39](https://github.com/tamga-sh/tamga-java/commit/6b9cf3984560f8aade6a7e63e5c23893b82d0609))
* close the security review's LOWs and the codecov patch gap ([99d3175](https://github.com/tamga-sh/tamga-java/commit/99d317506c881e5c8b568592bc6bd74ac75d00b1))
* correct the DEAD heartbeat guidance and pin the ping loop ([a9dbe49](https://github.com/tamga-sh/tamga-java/commit/a9dbe492be57f935c945a7578709efc0e1a795fd))
* correct the heartbeat window claim without overpromising the scheduler ([d61619b](https://github.com/tamga-sh/tamga-java/commit/d61619bf8db6529f5edda18c3dc9b0d951cd0b1a))
* cover the new endpoint surface with tests ([3d92349](https://github.com/tamga-sh/tamga-java/commit/3d923496b6c6e4d9f522ab1e5db6c01fee50b542))
* document the new endpoint surface and the findings behind it ([a160207](https://github.com/tamga-sh/tamga-java/commit/a160207283e7fbbb7bc78cce35b3b0a156d44cad))
* name the machine page-size constant after whose default it is ([8afe244](https://github.com/tamga-sh/tamga-java/commit/8afe244e620836a8dd6d1bd4e7dc6bbe1a8b6133))
* pin the interval floor against the server's truncating liveness rule ([7ebb95e](https://github.com/tamga-sh/tamga-java/commit/7ebb95ed8e22e68664c4979e591bab3fce30abbe))
* pin the strict base64 decoder and correct two overstated claims ([03d8857](https://github.com/tamga-sh/tamga-java/commit/03d8857b08f4cd3f0dcda19cf1b795d0c5358c1a))
* raise every heartbeat interval clamp to a one-second floor ([1280bfb](https://github.com/tamga-sh/tamga-java/commit/1280bfbf0f57ceca735001e2d30627a929dfdcc3))
* read machine files the way the server actually writes them ([7c631d3](https://github.com/tamga-sh/tamga-java/commit/7c631d3e48c33908558f64ade586762b5a372d4e))
* read machine files the way the server actually writes them ([afa5285](https://github.com/tamga-sh/tamga-java/commit/afa5285a2221550be4eb538afbfa11d4bac58a33))
* record the interval floor and what it costs per window ([94da1f6](https://github.com/tamga-sh/tamga-java/commit/94da1f66fd55d1386ad71346defd391bb9c33781))
* record why licence-scoped fingerprint recovery is sufficient ([328ce1e](https://github.com/tamga-sh/tamga-java/commit/328ce1e784b9497d42cd222d6079cd5abf865453))
* reframe the DEAD guidance on the mechanism, and scope the window per route ([518acbf](https://github.com/tamga-sh/tamga-java/commit/518acbf7d85d481ad3894b2d07975f4e0205744c))
* rename the heartbeat loop tests off the retired DEAD-from-a-ping premise ([01432d2](https://github.com/tamga-sh/tamga-java/commit/01432d2d19386d4d7767bc06bb0abb5c334ff523))
* snapshot the tick list under its monitor before asserting on it ([22b0a3f](https://github.com/tamga-sh/tamga-java/commit/22b0a3fdb0ee0a007035ddc58a6cc6a559587a38))

## [1.3.0](https://github.com/tamga-sh/tamga-java/compare/v1.2.2...v1.3.0) (2026-08-20)


### Features

* **client:** implement the HTTP API client ([#32](https://github.com/tamga-sh/tamga-java/issues/32)) ([1378f40](https://github.com/tamga-sh/tamga-java/commit/1378f40cadf95bb69a4907ef96c336a43cb1c47c))

## [1.2.2](https://github.com/tamga-sh/tamga-java/compare/v1.2.1...v1.2.2) (2026-08-18)


### Bug Fixes

* **ci:** open release PRs with a GitHub App token so required checks run ([#26](https://github.com/tamga-sh/tamga-java/issues/26)) ([b57b322](https://github.com/tamga-sh/tamga-java/commit/b57b322cdd307971f733c9a4039c31674092484a))

## [1.2.1](https://github.com/tamga-sh/tamga-java/compare/v1.2.0...v1.2.1) (2026-08-18)


### Bug Fixes

* correct SDK documentation and align package metadata ([14e94d5](https://github.com/tamga-sh/tamga-java/commit/14e94d578cc6b159ded2436308a8c8aa1d04b1d7))

## [1.2.0](https://github.com/tamga-sh/tamga-java/compare/v1.1.1...v1.2.0) (2026-08-13)


### Bug Fixes

* strip leading v from the published Maven Central version string ([76f4169](https://github.com/tamga-sh/tamga-java/commit/76f41690d9807a0c15151924f77f639a8c9c2ccc))


### Miscellaneous Chores

* set explicit release version ([165a6b2](https://github.com/tamga-sh/tamga-java/commit/165a6b23dee26e0401bb21fabe367069c683eee9))

## [1.1.1](https://github.com/tamga-sh/tamga-java/compare/v1.1.0...v1.1.1) (2026-08-13)


### Bug Fixes

* broken NaiveKey javadoc references blocking Maven Central publish ([4cf6923](https://github.com/tamga-sh/tamga-java/commit/4cf69236e31b3302da895c6d1ad96b9d61e63eeb))

## [1.1.0](https://github.com/tamga-sh/tamga-java/compare/v1.0.1...v1.1.0) (2026-08-13)


### Features

* license-file HKDF + offline format v2 ([9014cf6](https://github.com/tamga-sh/tamga-java/commit/9014cf62a0490a54e66bac1ef7757253aeedbf53))


### Bug Fixes

* auto-release Maven Central deployments instead of leaving them pending ([bfcb93f](https://github.com/tamga-sh/tamga-java/commit/bfcb93f0820e99dad95c96727488f09dc200bf83))

## [1.0.1](https://github.com/tamga-sh/tamga-java/compare/v1.0.0...v1.0.1) (2026-08-12)


### Bug Fixes

* chain Maven Central publish off release-please's own outputs ([4fda5c9](https://github.com/tamga-sh/tamga-java/commit/4fda5c9d5a3800be0759f6f4ee455f3a52206e96))
* remove redundant withSourcesJar/withJavadocJar causing publish failure ([4569ca3](https://github.com/tamga-sh/tamga-java/commit/4569ca3b1afa017490f0115a57af64aa8b7fdb63))

## 1.0.0 (2026-08-12)


### Features

* pivot from tamga-c JNI binding to native Java crypto reimplementation ([9fe1ff4](https://github.com/tamga-sh/tamga-java/commit/9fe1ff41706e4f010a826b9990970c61161ece4a))
* scaffold project structure ([29ccb1d](https://github.com/tamga-sh/tamga-java/commit/29ccb1d145b957eea083ce24141cc4c4bf71e981))


### Bug Fixes

* explicitly use bash for the doc-only detection step on Windows ([4207f19](https://github.com/tamga-sh/tamga-java/commit/4207f192672ac285b731d44a15b40bfeb82460a0))
* replace dorny/paths-filter with a direct git diff for doc-only detection ([8d26e75](https://github.com/tamga-sh/tamga-java/commit/8d26e755c38f6e58194c6d03db4609eb906b7754))
* replace dorny/paths-filter with a direct git diff for doc-only detection ([9418f88](https://github.com/tamga-sh/tamga-java/commit/9418f88d1fa8fb7434549a716841932dbcefed3f))
* skip Checkstyle/SpotBugs/coverage gate in CI for doc-only PRs ([be83106](https://github.com/tamga-sh/tamga-java/commit/be8310670e3f1c30aad0eeda4d7e15f5cab72b0e))
* skip Checkstyle/SpotBugs/coverage gate in CI for doc-only PRs ([368b2fc](https://github.com/tamga-sh/tamga-java/commit/368b2fc06e5a2e0aa43909a189a3f0cb9b6d498f))
