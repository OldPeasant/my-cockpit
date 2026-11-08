# Code Review

Date: 2026-11-08

Task: and-inbox-rows - Android: inbox and New view rows complete and open tasks

Scope: `git diff 2ec89bec4..0215c218d -- android` (commits 81933fe51 implementation, 434d7f2ac test-writer tests, 2c5c2b473 fix for the first review's CR-1, 0215c218d extra undo reload tests and reports). This is a re-review of the final revision. The production code under `android/app/src/main` is the same as at 2c5c2b473, which the previous review covered under earlier hashes. Since then only `InboxViewModelTest.kt` has changed. There were no uncommitted changes.

Requirements:
- `tasks/and-inbox-rows.md` acceptance criteria and recorded assumptions
- SPEC.md §5.3 (New view: opening a row opens the item in its own view, task detail or mail read view)
- docs/PARITY.md (the Inbox rows / New view task gap)
- docs/ARCHITECTURE.md, AGENTS.md project notes, docs/QUALITY.md
- Previous review reports and `reviews/and-inbox-rows/fixes.md`
- Shared code the change relies on: `gtd/next/TaskCompletion.kt` (TaskCompleter, TaskCompletionRepository, CompletionState)

Git range:
- Branch: main
- HEAD: 0215c218d
- Base/start: 2ec89bec4825c32dd1901814c6645b74719cdee9
- Commits reviewed: 4

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | The Inbox reload decides "offline" from the shared snackbar, not from the result of this task's request** (status: open from the previous review, still present at HEAD; the parent worker accepts it as low impact with no code change)
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxViewModel.kt:284-289`
    - Category: Correctness
    - Problem: `refreshInboxAfter(taskId)` waits until `taskId` is no longer in `pending`. It then skips the reload when `answered.snackbar == Failed(OFFLINE)`. Every completion and undo in the `TaskCompleter` writes to the same snackbar, so that value can belong to a different task. `completer.state` is a conflating `StateFlow`. Example: task B is refused (CONFLICT), then task A fails as OFFLINE before B's collector runs. B's collector sees A's OFFLINE snackbar and skips the Inbox reload, so B keeps its stale version.
    - Why it matters: It needs two overlapping requests plus a network drop, so it is unlikely. When it happens, a later action on B gets a 412 until the next sync. That is the problem the first review's CR-1 fixed.
    - Suggested fix: Base the decision on this request's own result. One option is to always refresh once the task leaves `pending`; a refresh while offline only marks the cache offline. Another is to have `TaskCompleter` expose a per-task result. If the code stays as it is, add a short comment saying the check is a best-effort shortcut.

## Notes (not findings)

- New in 0215c218d: two tests in `InboxViewModelTest`:
    - "a refused undo reloads the inbox, an undo that failed offline does not"
    - "an undo is reloaded only once the server answered it"
  
  They fill the undo side of the reload behaviour, which was only tested for completion before. Both assert observable behaviour: how many times `tasks.refresh(INBOX)` is called, the snackbar, and the visible task list. They do not weaken any existing assertion. This review ran `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.inbox.InboxViewModelTest'`: 43 tests, 0 failures, 0 errors, 0 skipped.
- The first review's CR-1 (Inbox not reloaded after a complete or undo, so a later action got 412) remains **resolved** at HEAD.
- Criterion 1:
    - `InboxRow` has a checkbox wired to the shared `TaskCompleter`. The checkbox is disabled offline, and the view model also refuses to complete offline.
    - The undo snackbar comes from Next's `CompletionSnackbarEffect`. The snackbar and the list padding stay clear of the FAB.
    - Tapping a row uses `opensTask(task)`.
    - Covered by `InboxScreenTest`, `InboxRouteTest` and `InboxViewModelTest`.
- Criterion 2:
    - New view task rows open the task detail through `LocalTaskOpener`.
    - `NewOpen.Task`, `NewTaskView`, `NewTags.TASK_VIEW` and `NewUiState.inbox`/`task()` are removed.
    - A saved `task:` state from the old version is ignored.
    - `NewRouteTest` covers inside the shell frame: the detail route opens, Back returns to New, and the filter and scroll position are kept.
- Criterion 3: no new strings were added. The reused strings exist in both `values/` and `values-de/`. The unused `new_task_created_at`/`new_task_hint` were removed from both, and nothing else references them.
- Criterion 4 evidence: `python tasks.py verify and-inbox-rows` still lists all four criteria as unverified. The parent worker must record the full `./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` runs. This review ran only the focused test class above.
- docs/PARITY.md still lists this gap. Updating it is outside this task's `touches`, so it is the parent worker's decision.

## Review conclusion

No material issues found.
