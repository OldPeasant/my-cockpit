---
title: Android: unit tests write Robolectric temp files into build/, not the shared /tmp
status: in_progress
owner: dell26/agent-1
deps: []
touches: [android/app/build.gradle.kts]
prio: 2
lease_until: 2026-11-02T12:23:17Z
---
## Goal
Every Robolectric test JVM extracts its native runtime (~205 MB) into `/tmp/robolectric-nativeruntime*` and never
deletes it. On the shared agent machine this filled the user's /tmp quota (2026-11-01): `testDebugUnitTest` then
fails with "Disk quota exceeded" / "UnsatisfiedLinkError: Failed creating temp file" in ~1500 tests, which blocks
`.taskcheck` for every task. Point the test JVMs' temp dir into the build directory so it is per clone and removed
by `./gradlew clean`.

## Acceptance criteria
- [x] `tasks.withType<Test>` in `android/app/build.gradle.kts` sets `java.io.tmpdir` (e.g. a directory under
      `layout.buildDirectory`, created before the run); no new files appear in `/tmp` during `testDebugUnitTest`
  - verified (2026-11-02 12:54+0100 by dell26/agent-1): android/app/build.gradle.kts tasks.withType<Test> sets java.io.tmpdir=temporaryDir/jvm (mkdirs in doFirst). Ran cd android && ./gradlew testDebugUnitTest --tests '*SessionCacheTest' --tests '*CockpitThemeTest' --rerun while polling app/build/tmp/testDebugUnitTest/jvm every 0.5s -> robolectric-nativeruntime* dirs (3, ~157 MB) and robolectric-<test> dirs were created there instead of /tmp (listing /tmp itself is denied to agents by sandbox policy, so 'no new files in /tmp' is shown by where Robolectric's temp files went)
- [x] `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes
  - verified (2026-11-02 12:54+0100 by dell26/agent-1): cd android && ./gradlew clean ktlintCheck testDebugUnitTest lintDebug --console=plain -> BUILD SUCCESSFUL in 4m; 2865 tests, 0 failures, 0 errors, 0 skipped

## Notes
Found while working on and-task-detail (held because of this). A human may need to delete the stale
`/tmp/robolectric-nativeruntime*` directories once; agents may not write outside their clone.
- ASSUMPTION (2026-11-02 12:55+0100 by dell26/agent-1): Criterion 1 'no new files in /tmp' is shown indirectly: agents may not list /tmp (sandbox), but a polled test run showed all Robolectric temp dirs (nativeruntime ~157 MB, per-test dirs) created under android/app/build/tmp/testDebugUnitTest/jvm. A human can confirm by listing /tmp/robolectric-* before/after a run (and should delete the old leftovers once).
