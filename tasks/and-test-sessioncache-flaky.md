---
title: Android: fix flaky SessionCacheTest (Room lock file in cacheDir)
status: in_progress
owner: dell26/agent-1
deps: []
touches: [android/app/src/test/java/ch/sonensei/cockpit/auth/SessionCacheTest.kt]
prio: 2
lease_until: 2026-11-23T19:08:34Z
---
## Goal
`SessionCacheTest` (`android/app/src/test/java/ch/sonensei/cockpit/auth/SessionCacheTest.kt`) is flaky. It failed in CI
run 45235688070 (commit 0f783cd3c, an unrelated change) with
`expected:<[]> but was:<[capture_queue.db.lck]>`.

Cause: `CockpitApp.onCreate()` starts `CaptureWidgetUpdater` in the background. It observes the capture queue Room
database, and opening that database makes the androidx SQLite helper create a `capture_queue.db.lck` lock file in
`context.cacheDir`. When that happens after the logout cache clear ran, the test's check "cacheDir is exactly empty"
fails. A lock file in the cache after logout is harmless, so the test is wrong, not the production code.

## Acceptance criteria
- [x] Both `the cache directory is emptied, including subfolders, but kept itself` and `the files feature's previewed
      and opened files go with the logout` check that the files and folders the test created are gone (and that
      `cacheDir` itself still exists), not that `cacheDir` is exactly empty. They still fail if the clear does nothing
  - verified (2026-11-23 19:43+0100 by dell26/agent-1): Both tests now use assertCleared (cacheDir is a directory and every entry the test created is gone); new test 'the checks still fail when the clear does nothing' (no-op SessionCache) asserts assertCleared throws. android/gradlew -p android testDebugUnitTest --tests SessionCacheTest -> 5 tests, 0 failures
- [x] The race is reproduced and shown fixed: a test variant (or a temporary local run) that creates a
      `capture_queue.db.lck`-like file in `cacheDir` after the clear passes with the new checks
  - verified (2026-11-23 19:40+0100 by dell26/agent-1): Added test 'a lock file the app creates in the cache right after the clear does not fail the checks' (writes capture_queue.db.lck into cacheDir after clear). Temporarily added the old exact-empty assertion to it: gradlew testDebugUnitTest --tests SessionCacheTest -> FAILED with expected:<[]> but was:<[capture_queue.db.lck]> (the CI message); with the new checks -> 5 tests, 0 failures
- [x] Production code is unchanged, unless the investigation shows a real problem; then record it with `tasks.py assume`
  - verified (2026-11-23 19:40+0100 by dell26/agent-1): git diff 6e527678a..HEAD --stat -> only android/app/src/test/.../auth/SessionCacheTest.kt changed; no production problem found (a lock file in cacheDir after logout is harmless)
- [x] `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.SessionCacheTest'`
      passes 20 times in a row (e.g. a loop with `--rerun-tasks`), and `ktlintCheck testDebugUnitTest lintDebug` passes
  - verified (2026-11-23 19:47+0100 by dell26/agent-1): .tmp/loop20.sh: 20x 'cd android && ./gradlew ktlintCheck testDebugUnitTest --tests ch.sonensei.cockpit.auth.SessionCacheTest --rerun' (test task forced to rerun each time) -> passed 20/20 in a row; 'android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug' -> exit 0
