---
title: Server and Android tests keep temp files in build/, not /tmp
status: in_progress
owner: dell26/agent-1
deps: [test-tmp-dir-leaks,backup-round-trip-tmp-leak]
touches: [server/build.gradle.kts,android/app/build.gradle.kts,docs/ARCHITECTURE.md]
prio: 1
lease_until: 2026-11-02T07:16:42Z
releases: 1
extended: 
---
## Goal
On the owner's laptop `/tmp` (tmpfs, 12.6 GB quota) filled up on 2026-11-02 ("disk quota exceeded"; 13 GB used):
- 58 `robolectric-nativeruntime<random>` folders (~200 MB each, 11 GB): every Android unit-test JVM unpacks
  Robolectric's native runtime into `java.io.tmpdir` and never deletes it;
- 7 `cockpit-round-trip-data<random>` folders (61 MB each) from `BackupRoundTripTest`: its `@AfterAll` cleanup
  (task backup-round-trip-tmp-leak) does not run when the test JVM is killed (timeouts, stopped agents, daemons).
Each agent check / `done` adds 1-2 GB, so the machine runs out again within a day or two, which then breaks builds,
`gh` downloads and other agents' tests. Fix it in the build, so it holds for every test, not per test class.

## Acceptance criteria
- [ ] Every `Test` task of `server/` and `android/app` runs its JVMs with `java.io.tmpdir` set to a folder under
      the module's `build/` (e.g. `build/tmp/test-jvm/<task name>`), created before and emptied at the start of
      the task (so leftovers of killed runs are removed by the next run of the same task); `./gradlew clean`
      removes it. Existing tests that call `Files.createTempDirectory`/`@TempDir` keep working unchanged
- [ ] Evidence: count `/tmp/robolectric-nativeruntime*` and `/tmp/cockpit-round-trip-data*` before and after
      `cd android && ./gradlew testDebugUnitTest` and `cd server && ./gradlew test` -> no new entries in `/tmp`;
      a second run of the same task does not grow `build/tmp/test-jvm` (it was emptied first)
- [ ] A killed run leaves nothing in `/tmp`: start `./gradlew testDebugUnitTest`, kill the test JVM mid-run
      (`pkill -f "Gradle Test Executor"`), check `/tmp` gained no Robolectric folder; the next run empties the
      leftovers under `build/`
- [ ] The integration test task (`server` `integrationTest`, Testcontainers) gets the same setting; Testcontainers
      still works (`./gradlew integrationTest --tests '*BackupRoundTrip*'` or another short one passes)
- [ ] `sh scripts/check.sh` passes, and its duration is not noticeably longer than before (record both)
- [ ] `docs/ARCHITECTURE.md` (testing section) says in one line that test temp files live under `build/tmp/test-jvm`

## Notes
Requested by the owner (2026-11-02) after `/tmp` filled up. Only the build files (one `tasks.withType<Test>` block
each, keep both sides on a merge conflict) and the docs line; no test code changes unless a test hard-codes `/tmp`.
Web (Vitest) and Playwright temp files are out of scope unless the evidence shows they also accumulate in `/tmp`.
- release by felix/agent-1: Implementation done and committed (ac4c3f51): server/build.gradle.kts and android/app/build.gradle.kts tasks.withType<Test> set -Djava.io.tmpdir=build/tmp/test-jvm/<task> via jvmArgumentProviders and empty it in doFirst (covers integrationTest too); ARCHITECTURE.md line added. Left: all 6 criteria unverified (run /tmp counts before/after testDebugUnitTest + server test, kill-mid-run test, integrationTest --tests '*BackupRoundTrip*', time check.sh vs. before), then test-writer + code-reviewer + quality.json, then done. Note: my check.sh run took >10 min (expected <2) and server PageFetcherSizeLimitTests failed once with 1 TIMEOUT of 50 (looks flaky/unrelated, but confirm it is not caused by the new tmpdir); measure check duration on main for comparison.
