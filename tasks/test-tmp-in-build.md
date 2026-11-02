---
title: Server and Android tests keep temp files in build/, not /tmp
status: todo
owner: 
deps: [test-tmp-dir-leaks,backup-round-trip-tmp-leak]
touches: [server/build.gradle.kts,android/app/build.gradle.kts,docs/ARCHITECTURE.md]
prio: 1
lease_until: 
releases: 3
extended: 
hold: given back 3 times; last: Code done (20be1761a), helper reports committed (a314022bb: tests.md + review.md, 0 findings, fixer not required). Criteria #1-#4 and #6 verified. Left: #5 run 'time sh scrip
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
- [x] Every `Test` task of `server/` and `android/app` runs its JVMs with `java.io.tmpdir` set to a folder under
      the module's `build/` (e.g. `build/tmp/test-jvm/<task name>`), created before and emptied at the start of
      the task (so leftovers of killed runs are removed by the next run of the same task); `./gradlew clean`
      removes it. Existing tests that call `Files.createTempDirectory`/`@TempDir` keep working unchanged
  - verified (2026-11-02 10:32+0100 by dell26/agent-1): server/build.gradle.kts:115 and android/app/build.gradle.kts:90 tasks.withType<Test>().configureEach add -Djava.io.tmpdir=build/tmp/test-jvm/<task name> via jvmArgumentProviders, doFirst deleteRecursively+mkdirs; runs from #2-#4 (server test, android testDebugUnitTest x2, server integrationTest) all BUILD SUCCESSFUL with dirs build/tmp/test-jvm/{test,testDebugUnitTest,integrationTest} created and emptied per run (killed-run leftovers removed by next run); dir is under build/ so clean removes it; existing createTempDirectory/@TempDir tests (incl. BackupRoundTripTest) pass unchanged
- [x] Evidence: count `/tmp/robolectric-nativeruntime*` and `/tmp/cockpit-round-trip-data*` before and after
      `cd android && ./gradlew testDebugUnitTest` and `cd server && ./gradlew test` -> no new entries in `/tmp`;
      a second run of the same task does not grow `build/tmp/test-jvm` (it was emptied first)
  - verified (2026-11-02 10:06+0100 by dell26/agent-1): Counted /tmp/robolectric-nativeruntime* + /tmp/cockpit-round-trip-data* (.tmp/count.sh, list diff) before/after 'cd android && ./gradlew testDebugUnitTest' (2 runs, 2nd with --rerun) and 'cd server && ./gradlew test --rerun' -> all BUILD SUCCESSFUL; /tmp lists identical before/after (10 robolectric, 0 round-trip, all pre-existing from other clones); during the android run android/app/build/tmp/test-jvm/testDebugUnitTest held up to 680M (robolectric-*, mockk_boot*.jar), after run1 8K, after run2 8K (no growth); server build/tmp/test-jvm/test empty after run
- [x] A killed run leaves nothing in `/tmp`: start `./gradlew testDebugUnitTest`, kill the test JVM mid-run
      (`pkill -f "Gradle Test Executor"`), check `/tmp` gained no Robolectric folder; the next run empties the
      leftovers under `build/`
  - verified (2026-11-02 10:06+0100 by dell26/agent-1): Started 'cd android && ./gradlew testDebugUnitTest --rerun', killed the test JVM with pkill -9 -f 'agent-1/android/app/build/tmp/test-jvm' once its tmpdir reached 205M (pattern scoped to this clone's executor, not 'Gradle Test Executor', so other agents' test runs on the shared machine survive) -> BUILD FAILED as expected; 205M left in android/app/build/tmp/test-jvm/testDebugUnitTest incl. robolectric-nativeruntime806669198986125669; /tmp list identical (no new robolectric folder). Next 'testDebugUnitTest --rerun' -> BUILD SUCCESSFUL, dir back to 4K, leftovers gone
- [x] The integration test task (`server` `integrationTest`, Testcontainers) gets the same setting; Testcontainers
      still works (`./gradlew integrationTest --tests '*BackupRoundTrip*'` or another short one passes)
  - verified (2026-11-02 10:06+0100 by dell26/agent-1): integrationTest is a Test task so withType<Test>().configureEach applies; '--tests *BackupRoundTrip*' finds nothing (BackupRoundTripTest is in the unit test task, covered by #2). Ran 'cd server && ./gradlew integrationTest --tests *HeaderSyncDovecotTests --tests *ExternalToolingSmokeTests --rerun' -> BUILD SUCCESSFUL, 4 tests 0 failures 0 skipped (Dovecot via Testcontainers GenericContainer, podman); build/tmp/test-jvm/integrationTest created; /tmp list unchanged
- [ ] `sh scripts/check.sh` passes, and its duration is not noticeably longer than before (record both)
- [x] `docs/ARCHITECTURE.md` (testing section) says in one line that test temp files live under `build/tmp/test-jvm`
  - verified (2026-11-02 08:13+0100 by dell26/agent-1): grep -n test-jvm docs/ARCHITECTURE.md -> line 150-151 in Testing section: test JVM java.io.tmpdir is build/tmp/test-jvm/{task name}, emptied per run, removed by clean

## Notes
Requested by the owner (2026-11-02) after `/tmp` filled up. Only the build files (one `tasks.withType<Test>` block
each, keep both sides on a merge conflict) and the docs line; no test code changes unless a test hard-codes `/tmp`.
Web (Vitest) and Playwright temp files are out of scope unless the evidence shows they also accumulate in `/tmp`.
- release by felix/agent-1: Implementation done and committed (ac4c3f51): server/build.gradle.kts and android/app/build.gradle.kts tasks.withType<Test> set -Djava.io.tmpdir=build/tmp/test-jvm/<task> via jvmArgumentProviders and empty it in doFirst (covers integrationTest too); ARCHITECTURE.md line added. Left: all 6 criteria unverified (run /tmp counts before/after testDebugUnitTest + server test, kill-mid-run test, integrationTest --tests '*BackupRoundTrip*', time check.sh vs. before), then test-writer + code-reviewer + quality.json, then done. Note: my check.sh run took >10 min (expected <2) and server PageFetcherSizeLimitTests failed once with 1 TIMEOUT of 50 (looks flaky/unrelated, but confirm it is not caused by the new tmpdir); measure check duration on main for comparison.
- extended by dell26/agent-1: Implementation committed (ca5345f16: withType<Test> sets java.io.tmpdir=build/tmp/test-jvm/<task>, doFirst empties it, server+android) and helper reports committed (80b660945, 0 findings, fixer not required). #6 verified. Left: run & record evidence for #1-#5 (server test + android testDebugUnitTest with /tmp counts before/after, kill test JVM mid-run then rerun, server integrationTest with Testcontainers, check.sh pass + duration vs before), write reviews/test-tmp-in-build/quality.json (reviewed_revision = HEAD of code), run done.
- release by dell26/agent-1: Code done and committed (959aded34: server+android withType<Test> set java.io.tmpdir=build/tmp/test-jvm/<task>, doFirst empties it; ARCHITECTURE.md line). Helper reports committed (ae595fc77: tests.md + review.md, 0 findings, fixer not required). #6 verified. Left (evidence only, no code): #2 count /tmp/robolectric-nativeruntime* and /tmp/cockpit-round-trip-data* before/after 'cd android && ./gradlew testDebugUnitTest' and 'cd server && ./gradlew test', rerun shows build/tmp/test-jvm not growing; #3 pkill -f 'Gradle Test Executor' mid testDebugUnitTest, /tmp unchanged, next run empties build leftovers; #4 'cd server && ./gradlew integrationTest --tests *BackupRoundTrip*' passes; #1 can cite #2-#4 runs; #5 time sh scripts/check.sh (compare with a main-without-change timing or earlier ~known duration). Then write reviews/test-tmp-in-build/quality.json (reviewed_revision = 959aded34 or rebased equivalent, result exactly 'pass'), commit, done (run with long background timeout).
- ASSUMPTION (2026-11-02 10:06+0100 by dell26/agent-1): Kill test (#3) used pkill -9 -f 'agent-1/android/app/build/tmp/test-jvm' instead of 'Gradle Test Executor': the machine is shared with other agents and the generic pattern would kill their test runs; the matched process is this clone's Gradle Test Executor
- release by dell26/agent-1: Code done (20be1761a), helper reports committed (a314022bb: tests.md + review.md, 0 findings, fixer not required). Criteria #1-#4 and #6 verified. Left: #5 run 'time sh scripts/check.sh' on this branch and on main (or cite a known main duration), record both with verify 5; write reviews/test-tmp-in-build/quality.json (reviewed_revision = full hash of 20be1761a or its rebased equivalent, result exactly 'pass', fixer not required), commit, then 'python tasks.py done test-tmp-in-build' with a long background timeout (check.sh has taken >10 min on this machine).
