# Code Review

Date: 2026-11-09

Task: web-task-reminders - Reminders on tasks (web)

Scope: final re-review of `git diff f1b60527..59fa20743 -- web` (implementation ef7f2bedf, tests 358e07dd6, fixes 69f24631e, test additions 428d572f0, round-2 fixes 2274f57a7, test-only additions 59fa20743), including a check of the round-2 fixes for CR-1 / CR-2 of the previous review and the test-writer's TW-1, as described in `reviews/web-task-reminders/fixes.md`

Requirements:
- tasks/web-task-reminders.md acceptance criteria and recorded assumptions (presets mirror Android, notification firing not manually verified)
- SPEC.md section 4.6 (Reminders and notifications), section 4.2 API conventions
- AGENTS.md project notes (feature folder, locale, lint conventions)
- Existing web conventions: features/gtd/task-detail/task-checklist.ts, TaskDetail.load request sequencing, core/notifications

Git range:
- Branch: main
- HEAD: 59fa20743253af2f39171bcb3631ef6f0f14f74f (final reviewed revision)
- Base/start: f1b60527da71148ded53116921e7f7423e613727
- Commits reviewed: 6 code/report commits (ef7f2bedf, 358e07dd6, 69f24631e, 428d572f0, 2274f57a7, 59fa20743), plus board-only commits that touch only `tasks/`
- Uncommitted changes: none
- Note: while this re-review ran, the local branch was rebased. The commits now have new hashes (59fa20743 is now c549e36b8). `git diff --stat 59fa20743 HEAD -- web/src/app/features/gtd/task-detail` is empty, so the reviewed code is identical.

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Changes since the previous review (2274f57a7..59fa20743)

The only change under `web/` is two new specs in `web/src/app/features/gtd/task-detail/task-reminders.spec.ts`. No production code changed. The rest of the diff is the test-writer's and reviewer's reports under `reviews/`.

- "keeps the controls locked when an earlier visit answers before the current load": opens task t1, goes to t2 and back to t1 with a new gate, then resolves the first visit's load first. The test checks that the chips stay disabled and that neither rows nor "No reminders." appear. After the current load resolves, the row shows and the chips are enabled. The mock reads `loadGate[taskId]` when the call is made (`await loadGate[taskId]`, spec line 156), so the first visit really waits on the first gate. This exercises the `seq === this.loadSeq` guard on the success path.
- "ignores a retry of the previous task that fails after switching task": a failed load, then a Retry with a slow gate (`loads` is `['t1', 't1']`), then a switch to t2, which loads one row. The slow t1 retry then fails, because `failLoad` is set again before the gate resolves. The mock reads `failLoad` after the gate (spec line 157), so the stale request really fails. The test checks that no error banner or Retry button appears and that t2's row (22 Nov) stays. This exercises the guard on the failure path across a task switch.

Both tests assert visible behavior (disabled state, rows, banner, Retry button) and do not depend on internal fields. They do not weaken any existing assertion. The reviewer did not run them because the parent worker was running the project check at the same time. The parent's run of `sh scripts/check.sh` or the test-writer's report is the execution evidence. These specs do not change any finding below.

## Status of earlier findings

- CR-1 / TW-1 (a superseded load for the same task could settle the state and overwrite the list): fixed. `load()` now takes `const seq = ++this.loadSeq` and applies a success or a failure only when `seq` is still the latest (`task-reminders.ts:236-250`). So neither an earlier visit of the same task nor a load replaced by a retry can unlock the controls or replace the list. This matches the sequencing that `TaskDetail.load` already uses. Covered by "ignores a late answer from an earlier visit of the same task" and "ignores a late load failure from an earlier visit of the same task". In 59fa20743 it is also covered by the two specs described above.
- CR-2 (Retry button looked enabled while a change was running): fixed. The button now has `[disabled]="locked()"` (`task-reminders.html:43-51`), like the chips, the exact-add button and the remove buttons. The spec "does not reload while an add after a failed load is pending" checks that the button is disabled while the add is pending and enabled again afterwards.
- The `messages.xlf` changes in 2274f57a7 only move line numbers. No unit was added or changed, and `messages.de.xlf` is unchanged in that commit.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | A change is matched to the task by id only, so a slow add from an earlier visit of the same task can add a duplicate row**
    - File: `web/src/app/features/gtd/task-detail/task-reminders.ts:182`
    - Category: Correctness
    - Problem: `add()` and `change()` check `taskId !== this.taskId()` to drop results for another task. Sequence: the user starts an add on task A, goes to task B and back to A while the POST is still pending. The new visit's GET can answer after the server committed the POST but before the POST response arrives. The list then already holds the new reminder, and the late POST response appends it again (`reminders.update(... [...all, reminder])`). That gives two rows with the same id, and the `@for ... track reminder.id` loop then has duplicate keys. The same check lets a late remove failure restore the `before` snapshot of the earlier visit (line 208) over the list the new visit loaded. This is the change-side version of the load issue fixed in round 2. It only happens if the user leaves the task and comes back within one request's latency. A reload fixes the view, and the server data is correct. Unchanged in 59fa20743.
    - Why it matters: a stale response can still change the list of a newer visit. The result is cosmetic (a duplicate or outdated row until the next reload), while the load path is already fully guarded against this.
    - Suggested fix: in `add()`, do not append a reminder whose id is already in the list. Optionally, apply change results (and rollbacks) only when no load has started since the change began, for example by comparing a `loadSeq` captured in `change()`.

## Review conclusion

No material issues found.

Notes from verification (not findings):
- The presets, the 09:00 default for a date without a time, the rule that "1 hour before" needs a due time, and the future-only filtering match the recorded assumption.
- The service calls `listReminders` with itemType TASK and itemId, and `createReminder` with itemType TASK and channel NOTIFICATION. A 404 on delete counts as removed.
- The component sits in the task detail, with the saved task as the anchor for the presets. It stays inside the task's touches (`web/src/app/features/gtd/task-detail`, `web/src/locale`).
- Every load path ends in `loaded` or `failed`. A failed load shows the error and a Retry button, and no "No reminders." text. The controls are locked while a load or a change is in flight.
- Browser notification firing and navigation use the unchanged `core/notifications`.
- Criterion 2 is recorded as skipped with a reason.
- Focused tests ran on 2274f57a7: `npm --prefix web run test:ci -- --include='src/app/features/gtd/task-detail/**/*.spec.ts'` -> 8 files, 370 tests passed. This reviewer did not run tests on 59fa20743, as instructed (the project check was running at the same time). The reviewer did not run Gradle or `sh scripts/check.sh`.
- The parent worker still has to record evidence for criteria 1 and 4 in the task file.
