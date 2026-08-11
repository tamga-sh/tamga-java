# `native/windows-x86_64/`

Empty placeholder. The built `tamga_jni.dll` (linked against `tamga-c`'s `tamga.dll`, see
`jni/CMakeLists.txt`) lands here as part of the release build — see
`docs/plans/tamga-java.plan.md` Section B and `sh.tamga.sdk.internal.jni.NativeLibraryLoader`'s
Javadoc for how this directory is resolved at runtime from `os.name`/`os.arch`.

Not committed until `tamga-c` v0.1 publishes GitHub Release artifacts for this target — see this
repository's `CLAUDE.md` for the current blocker.
