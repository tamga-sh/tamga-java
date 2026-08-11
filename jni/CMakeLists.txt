# jni/CMakeLists.txt
#
# STUB -- scaffolding only. Declares the `tamga_jni` shared-library target and locates JNI
# headers, but does NOT yet link against a real tamga-c cdylib (no path exists until tamga-c
# publishes its v0.1 GitHub Release artifacts -- see this repository's CLAUDE.md and
# docs/plans/tamga-java.plan.md Section B).
#
# Intended contents once tamga-c v0.1 ships:
#   - Locate the platform-appropriate tamga-c cdylib (libtamga.so / libtamga.dylib / tamga.dll)
#     via an env var (e.g. TAMGA_C_LIB_PATH) or find_library(), and link tamga_jni against it.
#   - Output the built shared library to the matching
#     src/main/resources/native/{linux-x86_64,macos-x86_64,macos-aarch64,windows-x86_64}/
#     directory as part of the Gradle build (or a documented manual copy step for v0.1).

cmake_minimum_required(VERSION 3.20)
project(tamga_jni C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

find_package(JNI REQUIRED)

add_library(tamga_jni SHARED tamga_jni.c)

target_include_directories(tamga_jni PRIVATE ${JNI_INCLUDE_DIRS})
target_link_libraries(tamga_jni PRIVATE ${JNI_LIBRARIES})

# PLACEHOLDER: cannot link against tamga-c until its v0.1 cdylib + tamga.h are published with a
# frozen exported-symbol table. Do not hand-transcribe a partial/unstable tamga.h here -- that is
# exactly how this binding silently drifts from tamga-c's actual runtime ABI.
#
# find_library(TAMGA_C_LIB
#     NAMES tamga
#     PATHS $ENV{TAMGA_C_LIB_PATH}
# )
# target_link_libraries(tamga_jni PRIVATE ${TAMGA_C_LIB})
