---
title: Android: unit tests write Robolectric temp files into build/, not the shared /tmp
status: in_progress
owner: dell26/agent-1
deps: []
touches: [android/app/build.gradle.kts]
prio: 2
lease_until: 2026-11-02T10:56:42Z
---
## Goal
Every Robolectric test JVM extracts its native runtime (~205 MB) into `/tmp/robolectric-nativeruntime*` and never
deletes it. On the shared agent machine this filled the user's /tmp quota (2026-11-01): `testDebugUnitTest` then
fails with "Disk quota exceeded" / "UnsatisfiedLinkError: Failed creating temp file" in ~1500 tests, which blocks
`.taskcheck` for every task. Point the test JVMs' temp dir into the build directory so it is per clone and removed
by `./gradlew clean`.

## Acceptance criteria
- [ ] `tasks.withType<Test>` in `android/app/build.gradle.kts` sets `java.io.tmpdir` (e.g. a directory under
      `layout.buildDirectory`, created before the run); no new files appear in `/tmp` during `testDebugUnitTest`
- [ ] `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes

## Notes
Found while working on and-task-detail (held because of this). A human may need to delete the stale
`/tmp/robolectric-nativeruntime*` directories once; agents may not write outside their clone.
