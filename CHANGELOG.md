# Changelog

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
