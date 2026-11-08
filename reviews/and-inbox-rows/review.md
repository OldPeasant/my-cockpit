# Code Review

Date: 2026-11-08

Task: and-inbox-rows - Android: inbox and New view rows complete and open tasks

Scope: `git diff ffe51cdac..b958d862f -- android` (task commit b958d862f only; later test-writer commits and the uncommitted working-tree change to `InboxViewModelTest.kt` are out of scope)

Requirements:
- `tasks/and-inbox-rows.md` acceptance criteria and recorded assumptions
- SPEC.md §5.3 (New view: opening a row opens the item in its own view)
- docs/PARITY.md (row "Inbox rows: no complete, no open; New view task opens read-only sheet")
- docs/ARCHITECTURE.md, AGENTS.md project notes
- Shell integration: `ui/CockpitShell.kt`, `gtd/task/TaskNavigation.kt`, `gtd/next/TaskCompletion.kt`, `gtd/next/TaskListUi.kt`

Git range:
- Branch: main
- HEAD: b958d862f856457944c2ded93c0320cc6e8df548
- Base/start: ffe51cdaca3bcbe1aee32e4ca2ed409dfa0262a4
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 1 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Completing or undoing from the Inbox never reloads the Inbox cache, so the list can keep a stale version that the next action is refused for**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxViewModel.kt:194` (with `android/app/src/main/java/ch/sonensei/cockpit/gtd/next/TaskCompletion.kt:65-69`)
    - Category: Correctness
    - Problem: The Inbox reuses `TaskCompleter`/`TaskCompletionRepository`. `TaskCompletionRepository.outcome` reloads only `TaskView.NEXT` and `TaskView.TODAY` after a complete or undo (the comment there says a refused request is fixed by reloading the cache). `TaskView.INBOX` is not reloaded. Other Inbox writes do reload it: `InboxRepository.kt:104` after processing and `TaskDetailRepository.kt:320` after edits. Scenario: complete a row (the server version goes from v to v+1), tap Undo (`Reopened`, server at v+2). `TaskCompleter` drops the `completed` entry, so the cached copy at version v shows again. Ticking it again sends `If-Match: "v"` and gets 412, shown as the CONFLICT snackbar. That failure again reloads only Next/Today, so every retry fails the same way until a TASK change event arrives or the Inbox flow is subscribed again. A CONFLICT on the first completion (the task changed elsewhere) also leaves the outdated Inbox copy in place. While the change stream is connected, the server's TASK event reloads the Inbox and hides the problem. The problem shows when the stream is down but the REST API is reachable, which is the case the explicit refresh in `outcome` exists for. The task's assumption calls this "same as Next/Today", but Next and Today are reloaded explicitly. No test covers complete, undo, complete again.
    - Why it matters: A normal flow (undo a tick, then tick again) can keep failing with "changed elsewhere" for a task nobody else changed. The Inbox then disagrees with the other lists, which do reload after the same operation.
    - Suggested fix: Reload `TaskView.INBOX` after a completion or undo that reached the server. Either add `tasks.refresh(TaskView.INBOX)` in `TaskCompletionRepository.outcome`, which is outside this task's `touches`, so coordinate or record it, or call `tasks.refresh(TaskView.INBOX)` from `InboxViewModel` once a completer result arrives. Add a view-model test: complete, undo, complete again, and the second completion uses the reloaded version.

## P3 Low

None.

## Notes (not findings)

- Criterion 1: `InboxRow` has a checkbox wired to the shared `TaskCompleter`. The checkbox is disabled while offline and `complete` checks for offline too. `CompletionSnackbarEffect` gives the same undo snackbar as Next. The snackbar and list padding keep clear of the shell FAB (`LocalFabClearance`; the Inbox is not in `withOwnFab`). A tap on the row opens the detail through `opensTask`/`LocalTaskOpener` and passes the list's copy as the snapshot. Hiding uses `CompletionState.hides`, which applies to both the list and the processing queue.
- Criterion 2: the New view's `TaskRow` calls `LocalTaskOpener`. `NewOpen.Task`, `NewTaskView`, `NewUiState.inbox`/`task()` and their tests are removed, and a saved `task:` state from the old version is ignored (tested in `NewStoresTest`). `NewRouteTest` runs inside `CockpitShellFrame` and checks the detail route, Back, the filter staying selected, the scroll position kept, and a single view-model instance.
- Criterion 3: no new strings were added. The reused `tasks_complete`/`tasks_completed`/`tasks_undo`/`task_detail_open` exist in `values/` and `values-de/`. The now-unused `new_task_created_at`/`new_task_hint` were removed from both locales.
- Criterion 4 evidence: `python tasks.py verify and-inbox-rows` showed all four criteria still unverified when this review ran. The parent worker must record the Gradle and `scripts/check.sh` runs. This review did not run them.

## Review conclusion

Material issues found; fix before completing the task.
