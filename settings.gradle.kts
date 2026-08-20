// `settings.gradle.kts`
//
// Single-module Gradle build — there is deliberately no `include(...)` here.
//
// This file previously described a `jni/` CMake module invoked as a build step. That module never
// existed after the pivot away from binding to tamga-c: cryptography is implemented natively in
// Java (see CLAUDE.md's "Crypto Architecture"), and there is no native toolchain in this build at
// all. See CLAUDE.md's "Architecture" section for the real repository layout.

rootProject.name = "tamga-sdk"
