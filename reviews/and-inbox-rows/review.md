# Code Review

Date: 2026-11-08

Task: and-inbox-rows - Android: inbox and New view rows complete and open tasks

Scope: `git diff db8cd5ded..3a3ca26ec -- android` (commits 7771bba40 implementation, 1f0cacae8 test-writer tests, 3a3ca26ec fix for the earlier CR-1). Uncommitted working-tree changes from the concurrent test-writer are out of scope.

Requirements:
- `tasks/and-inbox-rows.md` acceptance criteria and recorded assumptions
- SPEC.md §5.3 (New view: opening a row opens the item in its own view, task detail or mail read view)
- docs/PARITY.md line 41 (row "Inbox rows: no complete, no open; New view task opens read-only sheet")
- docs/ARCHITECTURE.md, AGENTS.md project notes
- Earlier review (CR-1) and `reviews/and-inbox-rows/fixes.md`
- Shared code used by the change: `gtd/next/TaskCompletion.kt` (TaskCompleter, TaskCompletionRepository, CompletionState)

Git range:
- Branch: main
- HEAD: 3a3ca26ece6e4cf8ea36d25568c9e1bea0d6078d
- Base/start: db8cd5ded04a2d1cb51257378035dff4eeeed45c
- Commits reviewed: 3

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | The Inbox reload decides "offline" from the shared snackbar, not from the result of this task's request**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxViewModel.kt:284-287`
    - Category: Correctness
    - Problem: `refreshInboxAfter(taskId)` waits until `taskId` leaves `pending`, then skips the reload when `answered.snackbar == Failed(OFFLINE)`. The snackbar is shared by every completion and undo in the `TaskCompleter`, so it can come from a different task. `completer.state` is a conflating `StateFlow`. Suppose task B is refused (CONFLICT) and task A fails as OFFLINE (the network drops mid-request) before B's collector runs. B's collector then sees A's OFFLINE snackbar and skips the reload. B then has the stale-version problem the earlier CR-1 fixed. The same thing happens when an undo is pending and another completion fails offline at that moment. The reverse case, an extra reload after an offline failure, does no harm.
    - Why it matters: This only happens with two overlapping requests and a network drop, so the impact is small. The result is the 412-on-retry behaviour that the fix was meant to remove, and the check depends on UI state instead of the request's own result.
    - Suggested fix: Base the decision on the request's own outcome. One way is to always call `tasks.refresh(TaskView.INBOX)` once the task leaves `pending`. A refresh while offline only marks the cache offline, which matches how other Inbox writes reload. The other way is to have `TaskCompleter` expose the per-task result. If the code stays as it is, a short comment saying the check is a best-effort shortcut is enough.

## Notes (not findings)

- Earlier CR-1 (Inbox never reloaded after a complete or undo, so a later action could get 412): **resolved**. `InboxViewModel.complete`/`undo` now reload `TaskView.INBOX` after the server answers, including CONFLICT/FAILED refusals, and skip it after OFFLINE. `complete` returns early for a task that is already hidden, so a double tap does not start a second reload. `TaskCompletion.kt` (outside `touches`) is not changed. Tests: `InboxViewModelTest` "complete, undo and complete again sends the version of the reloaded inbox" checks that the second completion uses the reloaded version 3 and that the Inbox reloads 3 times. Other tests cover a refused completion versus an offline one, and that the reload waits for the server's answer. `InboxRouteTest` only added the `refresh(INBOX)` stub the strict mock needs; no assertion was weakened.
- Criterion 1: `InboxRow` has a checkbox wired to the shared `TaskCompleter`. The checkbox is disabled offline and the view model also guards against offline. It is labelled `tasks_complete`. The undo snackbar comes from `CompletionSnackbarEffect`, the same one Next uses, and the snackbar and list padding keep clear of the shell FAB. A tap on a row calls `opensTask(task)` and passes the list's copy as the snapshot. Hiding uses `CompletionState.hides` for both the list and the processing queue. `InboxScreenTest` and `InboxRouteTest` cover all of this.
- Criterion 2: the New view's `TaskRow` calls `LocalTaskOpener`. `NewOpen.Task`, `NewTaskView`, `NewTags.TASK_VIEW`, `NewUiState.inbox`/`task()` and their tests are removed. A saved `task:` state from the old version is ignored (`NewStoresTest`). `NewRouteTest` runs inside `CockpitShellFrame` and checks the detail route, the snapshot, Back, the filter staying selected, the scroll position kept, and that only one view model is created. This matches SPEC.md §5.3.
- Criterion 3: no new strings were added. The reused `tasks_complete`/`tasks_completed`/`tasks_undo`/`task_detail_open` exist in `values/` and `values-de/`. The now-unused `new_task_created_at`/`new_task_hint` were removed from both locales. No other file still references them.
- Criterion 4 evidence: `python tasks.py verify and-inbox-rows` still showed all four criteria unverified when this review ran. The parent worker must record the Gradle and `scripts/check.sh` runs. This review did not run them.
- docs/PARITY.md line 41 still lists the gap. Whether to update it is the parent worker's decision, because `docs/` is outside this task's `touches`.

## Review conclusion

No material issues found.
