# Code Review

Date: 2026-11-09

Task: and-fix-logout-cache-test - Android: AppStartRoutingTest 'logging out from settings clears the cached files' fails on main

Scope: git diff 5acb7590bb60248c6fb19287d4b2557ce1575dca..0fcb536855b71adb06b1945eedd5e59898093345 -- android (test-only: `AppStartRoutingTest.kt` cache wait raised, new test in `SessionCacheTest.kt`)

Requirements:
- tasks/and-fix-logout-cache-test.md (acceptance criteria and notes)
- AGENTS.md (Evidence, quality gate, conventions: fictitious test data, lint)
- Production code read for context: `android/app/src/main/java/ch/sonensei/cockpit/auth/AuthModule.kt`, `android/app/src/main/java/ch/sonensei/cockpit/files/DeviceFiles.kt`

Git range:
- Branch: main
- HEAD: 0fcb536855b71adb06b1945eedd5e59898093345
- Base/start: 5acb7590bb60248c6fb19287d4b2557ce1575dca
- Commits reviewed: 2 code commits touching `android/` (db4259359, 0fcb53685); the other commits in the range only touch task state

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Verification of criterion 1 (logout clears the files cache actually used by the files feature):
- The files feature writes its caches under `context.cacheDir`: `PreviewFiles.target` uses `cacheDir/files-preview/<id>-<n>.pdf` (`files/DeviceFiles.kt:93-103`) and `OpenedFiles.target` uses `cacheDir/files-open/<id>/<name>` (`files/DeviceFiles.kt:118-126`, `:217`).
- `AuthModule.cacheDirectory` (`auth/AuthModule.kt`) deletes every entry of `context.cacheDir` recursively on IO, so both folders go away.
- The new test `the files feature's previewed and opened files go with the logout` (`SessionCacheTest.kt:36-49`) creates its fixtures through the real `PreviewFiles.target` and `OpenedFiles.target`, then asserts that the preview folder and the opened file are gone and that `cacheDir` is empty. So the test now fails if the files feature moves its cache out of `cacheDir` or if the cleanup stops being recursive. This resolves CR-1 (P3) of the earlier review.
- Test data is fictitious (fixed UUIDv7-shaped id, "fictitious" content, `report-fictitious.pdf`).

Criterion 2 (AppStartRoutingTest timing):
- Only the two cache-disappearance waits use the new `CACHE_CLEAR_WAIT_MS = 30_000L` (`AppStartRoutingTest.kt:167`, `:181`); screen waits keep `WAIT_MS`. `waitUntil` returns as soon as the condition holds, so the larger limit costs nothing on a fast run. The KDoc explains the reason (sequential `SessionCache` loop before the cache directory). This matches the timing analysis in the task notes and is a proportionate test-only fix.
- Gradle was not run in this review, as the parent agent asked. The parent worker must record the run of `AppStartRoutingTest`, `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` with `tasks.py verify`.

No production code changed. All changed files are inside the task's `touches`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.
