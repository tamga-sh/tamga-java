// `settings.gradle.kts`
//
// Single-module Gradle build. JNI glue (`jni/`) is a small, non-Java build
// (CMake, see `jni/CMakeLists.txt`) invoked as a step of the main build, NOT
// a Gradle subproject — there is deliberately no `include(...)` here. See
// CLAUDE.md's "Architecture" section for the full repository layout.

rootProject.name = "tamga-sdk"
