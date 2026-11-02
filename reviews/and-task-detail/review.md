# Code Review

Date: 2026-11-02

Task: and-task-detail - Android: task detail screen with editing

Scope: `git diff 305558bcc c171fc231` (re-review of the final code; `tasks/` and `reviews/` ignored). Commits: a0b11de74, 8f85753ba, e6e8d0a57 (merged as 035c6684c), c5e12669f (title-length error as plurals), d6abae750 (test-writer tests), c171fc231 (fixer's fixes for CR-1, CR-3, CR-4, CR-5).

Requirements:
- tasks/and-task-detail.md acceptance criteria and its assumptions (confirmed and unconfirmed)
- SPEC.md (§4.2 ETag/If-Match, Problem Details), docs/ARCHITECTURE.md (Android: MVVM, one package per feature), AGENTS.md project notes
- api/openapi.yaml (`/api/v1/tasks/{id}` GET/PATCH/DELETE, `/move`, `/complete`, `/uncomplete`, `/tasks/{taskId}/checklist[/{itemId}]`, `/api/v1/links`, `/api/v1/trash/{type}/{id}/restore`, `TaskPatchRequest`, `MoveTaskRequest`, `ProblemDetail`/`FieldError`)
- Parent agent instructions; reviews/and-task-detail/fixes.md for the claimed fixes

Git range:
- Branch: main
- HEAD reviewed: c171fc231a797a39cce60a73059ddc1ba7f028ad
- Base/start: 305558bcc69b44c40f9b91a010cc944ae849c286
- Commits reviewed: 6 (plus the merge commit 035c6684c)

Summary: 0 P0, 0 P1, 0 P2, 0 P3 open (the 6 earlier findings, CR-1 to CR-6, are resolved; no new findings)

Validation reported by the parent for c171fc231: `android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` -> BUILD SUCCESSFUL, all unit tests green (gtd.task: 108 tests, 0 failures), lint 0 errors. The reviewer did not run Gradle.

What still holds from the first review: the endpoints, methods and bodies match api/openapi.yaml. `If-Match` uses the strong `"{version}"` ETag. 409/412 reload the task without overwriting it. Problem Details `errors[].field` maps to the form fields. Row tap targets in all lists, the Logbook, the Dashboard and search lead to the `task/{id}` route, and Back is navigateUp. Edit controls are disabled offline. EN and DE resources have the same keys, now including the `task_detail_error_too_long` plurals with `one` and `other` in both languages. The new code does no logging and contains no secrets.

## Status of earlier findings

- [x] **CR-1 | An offline reload replaced the fresher server copy with the older list snapshot** (P2) - Resolved.
    - Verified in `TaskDetailViewModel.kt:268`. The Offline branch now asks `source.cached(id)` only when no detail is loaded. It keeps `it.detail` (and its `cachedAt`) otherwise, and `loadFailed` is only set when there is neither. Covered by `TaskDetailViewModelEdgeTest`: "going offline after the task was loaded keeps the loaded task read-only" and "a reload without an answer after a change keeps the loaded task, not the older list copy".
- [x] **CR-2 | TaskDetailRepository had no tests** (P2) - Resolved.
    - New `TaskDetailRepositoryTest` (MockWebServer) checks:
        - `If-Match: "3"` on PATCH, complete, uncomplete, move, delete, and checklist PATCH and DELETE (the item's own version);
        - the hand-built PATCH body;
        - 412 and 409 become Conflict; 404 and 410 become NotFound; 5xx becomes Offline; 403 becomes Failed; an unreadable body becomes Failed(null);
        - Problem Details with `errors` and `detail` (400 and 422), and a 400 without a body;
        - link direction, self-links dropped, and the `type`/`id` query of the links request;
        - restore without `If-Match`;
        - `cached()` order: snapshot first, then the Room view with its load time;
        - `changed()` emits `TaskEdits` and reloads the cached views.
- [x] **CR-3 | The due time was formatted with the default locale** (P3) - Resolved.
    - `TaskDetailViewModel.kt:107` now uses `"%02d:%02d:00".format(Locale.ROOT, ...)`.
- [x] **CR-4 | A Logbook task opened offline showed as an open task** (P3) - Resolved.
    - `TaskOpener` got a three-argument `open(id, snapshot, completedAt)`, with a two-argument default for the other lists. `LogbookScreen.kt:223` passes `done.completedAt` (non-null `Instant` in `LoggedTask`). The shell stores it in `TaskSnapshots.put(task, completedAt)`. `cached()` returns `TaskSnapshots.cached(id)`, and the view model builds `TaskDetail(copy.task, completedAt = copy.completedAt)`. Covered by "offline a task opened from the Logbook shows as completed" and the repository snapshot test.
- [x] **CR-5 | A failed links request could mark all GTD screens offline** (P3) - Resolved.
    - `TaskDetailRepository.links()` calls `writeApi.listTaskLinks` directly in `runCatching`, outside `connectivity.refreshing`, and returns an empty list on any failure. Covered by "links that fail on the server side leave the shared offline state alone" (503 on links, `connectivity.offline` stays false) and "the task still shows when its links cannot be loaded".
- [x] **CR-6 | Opening tasks from the Dashboard and the Logbook was not tested** (P3) - Resolved.
    - New `DashboardTaskOpenTest`: Today and Waiting-overdue task rows call `LocalTaskOpener` and not the card's `onOpen`; without an opener the card opens as before. `TaskDetailContentTest` covers a Logbook row opening the detail instead of the Logbook dialog. Today, Someday and project rows share `TaskRow`/`LiveTaskRow`, which are covered through the Next and Waiting tests.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

The fix commit c171fc231 introduced no new issue. Checked:
- the `fun interface` overload for `open` is source-compatible with the Dashboard (`open(task.id, null)`) and with list callers;
- `TaskSnapshots.get` still returns the `GtdTask` that the existing tests use;
- `cachedAt` is no longer overwritten when a loaded detail is kept.

`runCatching` in `links()` would also swallow a `CancellationException`. That has no effect here, because the surrounding `coroutineScope` is cancelled anyway and the result is discarded, so it is not reported.

## Review conclusion

No material issues found.
