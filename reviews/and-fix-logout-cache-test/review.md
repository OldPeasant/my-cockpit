# Code Review

Date: 2026-11-09

Task: and-fix-logout-cache-test - Android: AppStartRoutingTest 'logging out from settings clears the cached files' fails on main

Scope: git diff 5acb7590bb60248c6fb19287d4b2557ce1575dca..b61cd964f (test-only change in `android/app/src/test/java/ch/sonensei/cockpit/auth/AppStartRoutingTest.kt`)

Requirements:
- tasks/and-fix-logout-cache-test.md (acceptance criteria and notes)
- AGENTS.md (Evidence, quality gate)
- Production code read for context: `android/app/src/main/java/ch/sonensei/cockpit/auth/AuthModule.kt`, `auth/AuthRepository.kt` (`clearCaches`), `files/DeviceFiles.kt`

Git range:
- Branch: main
- HEAD: b61cd964f (reviewed commit; local HEAD db4259359 is the same change after rebase)
- Base/start: 5acb7590bb60248c6fb19287d4b2557ce1575dca
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 0 P2, 1 P3

Verification of criterion 1 (logout clears the files cache actually used):
- The files feature writes its caches under `context.cacheDir`: `PdfPreviews.target` uses `cacheDir/files-preview` and `OpenedFiles.target` uses `cacheDir/<DIR>/<id>/` (`files/DeviceFiles.kt:97`, `:123`). Neither uses `filesDir` or `externalCacheDir`.
- `AuthModule.cacheDirectory` (`auth/AuthModule.kt:38-42`) deletes every entry of `context.cacheDir` recursively, so both subdirectories are removed.
- `AuthRepository.clearCaches` (`auth/AuthRepository.kt:258-272`) clears the caches one after another and catches any failing cache. Only a cancellation of the caller stops the loop. So an earlier failing cache cannot skip the cache-directory cleanup, but a slow one delays it. That supports the timing explanation in the task notes, and raising the wait only in the two cache assertions is a proportionate test-only fix.
- No production change was needed. The diff touches only the test, which is inside the task's `touches`.

Evidence status: `python tasks.py verify and-fix-logout-cache-test` still shows both criteria unrecorded. The parent worker must record them (the test run, `./gradlew ktlintCheck testDebugUnitTest lintDebug`, `sh scripts/check.sh`) before `done`. This is a process note, not a code finding.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Test cache file does not sit where the files feature writes**
    - File: `android/app/src/test/java/ch/sonensei/cockpit/auth/AppStartRoutingTest.kt:185-191`
    - Category: Tests
    - Problem: `cachedFile()` writes `cacheDir/thumbnail-fictitious.png` at the top level of the cache directory. The files feature writes into subdirectories (`files-preview/`, the opened-files folder). The current recursive delete covers both locations. But the test would still pass if the cleanup were narrowed to top-level files only, or if the files feature moved its cache outside `cacheDir`.
    - Why it matters: criterion 1 is about the cache "actually used by the files feature". A regression there would go unnoticed.
    - Suggested fix (optional): create the fixture through `PdfPreviews.target(context, id)` (or at least inside a subdirectory of `cacheDir`) so the test pins the real location.

## Review conclusion

No material issues found.
