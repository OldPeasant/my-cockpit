# Code Review

Date: 2026-11-01

Task: and-logbook - Android: Logbook (completed tasks, reopen)

Scope: `git diff b5f069cdf..HEAD -- android` (implementation commit 456b21f81, tests commit cd9c8f334); committed changes only, working tree clean

Requirements:
- `tasks/and-logbook.md` acceptance criteria and its three recorded assumptions (detail dialog with Reopen; pages of 50 and reload of as many pages as shown; Logbook in More right after Someday)
- `AGENTS.md` project notes and conventions; `docs/ARCHITECTURE.md` conventions
- Existing GTD list screens for comparison: `LiveTaskListViewModel`, `WaitingViewModel`, `SomedayViewModel` (`ListViewModels.kt`), `LiveTaskListContent` and helpers (`ListUi.kt`), `TaskCompletionRepository` (`gtd/next/TaskCompletion.kt`), `GtdRefreshTriggers` (`gtd/data/GtdRefresh.kt`)
- Server contract: `TaskController.listTasks` (`completed=true`, cursor, limit 1-200), `POST /tasks/{id}/uncomplete` with If-Match via `TaskCompletionRepository.undo`

Git range:
- Branch: main
- HEAD: cd9c8f3344ab16387760723a56eec4b25dfa71de
- Base/start: b5f069cdf0dd7b8a3808f7b25951e89fcb91f3e1
- Commits reviewed: 2 (456b21f81, cd9c8f334; the three `assume:` commits only touch `tasks/`)

Summary: 0 P0, 0 P1, 1 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Logbook keeps reloading all shown pages on every task change while it is not on screen**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/lists/LogbookViewModel.kt:109`
    - Category: Architecture
    - Problem: The reload pipeline is started in `init` with `viewModelScope.launch { merge(repository.changes(), reloads).collectLatest { reload() } }`, so it runs for the whole lifetime of the view model, independent of whether the UI collects `uiState`. The view model is scoped to the Logbook's `NavBackStackEntry` (`CockpitShell.kt:141-153`), which stays alive while the user has opened search or another destination on top of it (`navController.navigate(...)`) and, through `navigateToTopLevel`'s `popUpTo(start) { saveState = true }`, also after switching to a bottom-bar tab (saved back-stack entries keep their ViewModelStore). During that time every TASK change event and every change-stream reconnect (`GtdRefreshTriggers.on`) triggers `reload()`, which issues up to `pagesLoaded` sequential `GET /tasks?completed=true` requests (50 tasks each) for a screen nobody sees. The existing live lists (`LiveTaskListViewModel`, `ListViewModels.kt:84-103`) drive their triggers inside the `uiState` upstream with `SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS)`, so they stop 5 s after the screen leaves; the Logbook's `uiState` uses `WhileSubscribed` too but only for projects/contexts, not for the reloads.
    - Why it matters: Unnecessary network traffic and battery use on a phone after the Logbook was visited once, growing with how far the user scrolled (e.g. 10 pages -> 10 requests per completed task anywhere in the app, including tasks completed on other devices). It also diverges from the established "load while shown" pattern of the GTD list screens and the task's own assumption #2 ("not cached ... reloads ... on task change events" while shown).
    - Suggested fix: Tie the change collection to subscribers, e.g. move `merge(repository.changes(), reloads)` into the `uiState` upstream (a `channelFlow`/`onStart` side that runs `collectLatest { reload() }` and is combined with `state`) so it is cancelled by `WhileSubscribed(STOP_TIMEOUT_MS)` and resumes (with the immediate emission of `changes()`) when the screen is shown again. Add a ViewModel test that no page is requested after the last `uiState` subscriber is gone past the stop timeout and that a reload happens on resubscription.

## P3 Low

None.

## Review conclusion

Material issues found; fix before completing the task.

Notes on items checked and not reported:
- All five acceptance criteria are implemented: Logbook entry in More after Someday (`CockpitDestination.kt`, `CockpitShell.kt`), newest-first list grouped by local completion day with project/context text, paging on scroll with a retryable footer, detail dialog on tap plus a Reopen row/dialog action through `TaskCompletionRepository.undo` (If-Match with the listed version; 404/409/412 mapped to CONFLICT and reloads), loading/empty/error/stale states reusing `Centered`, `LoadFailed`, `StaleBanner` and `LiveListTags`, and 15 new strings present in both `values` and `values-de`. Project titles come from `ProjectRepository`, which caches projects of every status, so done tasks in completed projects still show their project.
- The test-writer's observation (a "load more" page that answers before an already running reload is dropped again because the reload keeps the page count it computed at its start) is not reported: the reload result is consistent, `canLoadMore` stays true and the footer re-triggers loading when it becomes visible again (pinned by `a reload answering after a next page leaves a consistent list that can load more again`). The cost is one repeated page request in a rare race; no data is lost or duplicated (ids are de-duplicated before they reach the `LazyColumn` keys).
- A "load more" started during a running reload (reload with `loaded=true` does not set `loading`) is appended after the reload with its own cursor; with the server's keyset cursor and id de-duplication this stays consistent (covered by `a next page answering after a running reload is appended once`).
- Acceptance-criterion evidence: `python tasks.py verify and-logbook` shows all criteria still unrecorded at review time; recording them is the parent worker's step and not a finding against the code.
