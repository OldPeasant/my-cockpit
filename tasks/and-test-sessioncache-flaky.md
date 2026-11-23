---
title: Android: fix flaky SessionCacheTest (Room lock file in cacheDir)
status: todo
owner:
deps: []
touches: [android/app/src/test/java/ch/sonensei/cockpit/auth/SessionCacheTest.kt]
prio: 2
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
- [ ] Both `the cache directory is emptied, including subfolders, but kept itself` and `the files feature's previewed
      and opened files go with the logout` check that the files and folders the test created are gone (and that
      `cacheDir` itself still exists), not that `cacheDir` is exactly empty. They still fail if the clear does nothing
- [ ] The race is reproduced and shown fixed: a test variant (or a temporary local run) that creates a
      `capture_queue.db.lck`-like file in `cacheDir` after the clear passes with the new checks
- [ ] Production code is unchanged, unless the investigation shows a real problem; then record it with `tasks.py assume`
- [ ] `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.SessionCacheTest'`
      passes 20 times in a row (e.g. a loop with `--rerun-tasks`), and `ktlintCheck testDebugUnitTest lintDebug` passes
