# Code Review

Date: 2026-10-26

Task: and-gtd-load-error - Android: show first-load errors in GTD lists (TaskRepository reports refresh failure)

Scope: production code under `android/app/src/main/java/ch/sonensei/cockpit/gtd` in 381f80f8943934aa98b631b8eef0d9340b8b6df3..HEAD (commit 4e1aa4148), plus the uncommitted test changes under `android/app/src/test/java/ch/sonensei/cockpit/gtd` written by test-writer.

Requirements:
- tasks/and-gtd-load-error.md (acceptance criteria and recorded assumption)
- SPEC.md §9.3 (read cache, offline banner)
- AGENTS.md project notes (conventions, touches)
- Existing conventions in `gtd/lists/ListUi.kt` (live-list error/retry UI)

Git range:
- Branch: main
- HEAD: 4e1aa41481f9aa7fb3223e6b2a6ecf0f1e12c832
- Base/start: 381f80f8943934aa98b631b8eef0d9340b8b6df3
- Commits reviewed: 2 (3deb4964b assume-only, 4e1aa4148 implementation); uncommitted test changes in 10 test files also reviewed

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Today hides a failed first load of the Today view when the Next view has tasks scheduled for today**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/today/TodayViewModel.kt:48`, `android/app/src/main/java/ch/sonensei/cockpit/gtd/today/TodayScreen.kt:93`
    - Category: Correctness
    - Problem: `firstLoadFailed` needs `sections.isEmpty()`. If the TODAY view (overdue + due) was never loaded and its first load fails, but the NEXT view loaded and has a task scheduled for today, `sections` is not empty. The screen shows only the SCHEDULED section with no error or hint that overdue and due tasks are missing, and there is no retry. This is still a never-loaded state (TODAY `refreshedAt == null`), not the "failed refresh over cached data" case the recorded assumption excludes.
    - Why it matters: the user may think nothing is overdue or due when that list never loaded. It is rare (first launch, 4xx/unreadable answer on one endpoint only) and it is not the endless-loading defect the criterion targets, so it is low priority.
    - Suggested fix: when `loadFailed` is set and one of the two lists was never loaded, show a small inline error with retry above the sections (or fall back to the full error). Alternatively, record an assumption that partial Today results are acceptable.

## Notes (no findings)

- Acceptance criterion 1: `GtdConnectivity.failure(view)` exposes the last failure per view key. `cachedView` puts it into `Cached.failure`. Next and Today show `LoadFailed` with a retry instead of `Loading…` when nothing was loaded. Retry calls `TaskRepository.refresh` for the right view(s). Keys match between `observe` (`view.name`, `CacheMetaEntity.PROJECTS/CONTEXTS`) and `refresh`, and they cannot collide ("INBOX"/"NEXT"/"TODAY"/"PROJECTS"/"CONTEXTS").
- Concurrency: the failure is cleared inside the per-view lock when a load starts, so a retry shows Loading. A failure is recorded only when no clear ran meanwhile (checked under `writeLock`), and `clearing` empties the map under the same lock, so a load that outlives a logout does not leak its error into the next session. Offline results (IOException, 5xx) clear the failure, so the error and the offline banner do not compete for the same cause. Repeated retry taps queue behind the view lock and cause no inconsistent state.
- `LoadFailed` in `gtd/next/TaskListUi.kt` is close to the private `LoadFailed` in `gtd/lists/ListUi.kt`. That file is outside this task's touches, so keeping them separate is reasonable.
- Tests (uncommitted, from test-writer) cover these paths: failure reporting for 4xx, an unreadable body and a failed cache write; isolation per view; offline vs failure; clearing on a successful load; hiding while a load runs; logout races, at both connectivity and repository level; view-model state for first load vs reload; retry wiring in the view models and through `NextRoute`/`TodayRoute`; screen rendering. The parent reports the gtd suite passing (447 tests, 0 failures). The reviewer could not run Gradle here, so this was not checked independently. These tests are not committed yet, and the parent must commit them before `done`.

## Review conclusion

No material issues found.
