# Code Review

Date: 2026-10-26

Task: and-gtd-load-error - Android: show first-load errors in GTD lists (TaskRepository reports refresh failure)

Scope: final code of the task, 381f80f8943934aa98b631b8eef0d9340b8b6df3..eb5e289d0, limited to `android/app/src/{main,test}/java/ch/sonensei/cockpit/gtd`. This is a re-review after the CR-1 fix (`git diff d97f0f2c8..eb5e289d0`).

Requirements:
- tasks/and-gtd-load-error.md (acceptance criteria and recorded assumption)
- SPEC.md §9.3 (read cache, offline banner)
- AGENTS.md project notes (conventions, touches)
- Previous review of this task (CR-1) and reviews/and-gtd-load-error/fixes.md
- Existing conventions in `gtd/next/TaskListUi.kt` and `gtd/lists/ListUi.kt`

Git range:
- Branch: main
- HEAD: eb5e289d0
- Base/start: 381f80f8943934aa98b631b8eef0d9340b8b6df3
- Commits reviewed: 4 (3deb4964b assume-only, 4e1aa4148 implementation, d97f0f2c8 tests and reports, eb5e289d0 CR-1 fix); no uncommitted changes

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Status of earlier findings

- CR-1 (P3, Today hides a failed first load of one view when the other view has sections): **resolved**.
  `TodayUiState.firstLoadFailed` is now `refreshedAt == null && loadFailed` (`gtd/today/TodayViewModel.kt:51`). `TodayContent` shows the new `LoadFailedBanner` with the `lists_error_failed` text and a retry button above the sections when `firstLoadFailed` and the sections are not empty (`gtd/today/TodayScreen.kt:93`). With no sections, the full-screen `LoadFailed` still shows, because the `when` branch for `sections.isNotEmpty()` comes first (`TodayScreen.kt:96-108`). `loading` is still false whenever `loadFailed` is set, so the banner and "Loading…" cannot show at the same time. Retry still refreshes both views (`TodayViewModel.kt:96-99`). Failed reloads over loaded lists (`refreshedAt != null`) still show no error, as the recorded assumption says. Two new view-model tests (TODAY failed with NEXT scheduled tasks, NEXT failed with TODAY tasks) and one screen test (banner, sections, no loading, retry callback) cover the fix.

## Notes (no findings)

- Acceptance criterion 1: `GtdConnectivity.failure(view)` exposes the last failure per view key. `cachedView` puts it into `Cached.failure`. Next and Today show an error with retry instead of endless loading when nothing was loaded, and Today now also shows it when only one of its two lists never loaded. The keys used by `observe` and `refresh` match and cannot collide. The concurrency handling (failure cleared when a load starts, recorded only if no clear ran meanwhile, emptied on logout under the same lock) has not changed since the previous review.
- Edge case checked and not reported: `loadFailed` combines the failures of both views, while `refreshedAt` is null when either view was never loaded. So in a narrow window (the TODAY view loaded earlier and its reload fails while the NEXT view's first load is still running), the banner shows even though the failed view has cached data. The message is still true, retry is harmless, and the window closes when NEXT finishes loading. It is too rare to matter for this task.
- `LoadFailedBanner` sits next to `LoadFailed` in the shared `gtd/next/TaskListUi.kt` and reuses existing strings and test tags. This matches the task's touches and the recorded assumption (no new resources).
- Tests: the parent reports `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.*' lintDebug` on eb5e289d0 -> BUILD SUCCESSFUL, 450 gtd tests, 0 failures. That is 447 tests from the earlier revision plus the 3 new ones. The reviewer cannot run Gradle here and did not check this independently. All tests are committed.

## Review conclusion

No material issues found.
