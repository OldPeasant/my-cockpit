# Fix Report

Date: 2026-10-21

Task: web-gtd-convert-note - Inbox/task detail: to reference

Scope: current task, fix round 2. The parent worker supplied findings CR-5 (P2) and CR-6 (P3) from `reviews/web-gtd-convert-note/review.md`, on top of HEAD 2e8f5296. The previous round's report is archived as `fixes-1.md`.

## Fixed findings

### CR-5 - Task detail: a conversion that succeeds after the editor switched to another task still opens the old task's note and blocks the new task meanwhile

Files changed:
- `web/src/app/features/gtd/task-detail/task-detail.ts`
- `web/src/app/features/gtd/task-detail/task-detail.spec.ts`

Problem:
`toReference()` always navigated to the new note when the conversion succeeded, even if the editor had switched to another task by then. The component-wide `converting` flag also stayed `true` for the new task, so its button showed "Moving to reference…", the button was disabled, saving was refused and background reloads were skipped.

Fix:
The running state is now tied to a task id. A private `convertingId` signal holds the id of the task being converted, or null. `converting` is now `computed(() => convertingId() === taskId())`, so the template, `save()` and the change-event guard need no other change. On success, the component navigates only when `task.id === this.taskId()`, the same check `save()` and the failure path already use. In `finally`, `convertingId` is cleared only if it still holds this task's id, so a conversion of the old task that finishes late cannot clear the state of a conversion started later on the new task.
New spec `does not open the note nor block the new task when a conversion of the previous task succeeds late`: it starts the conversion of t1 and switches to t2. It then checks that t2's button reads "To reference (note)" and is enabled, and that an edit makes Save enabled. It resolves the conversion and checks that there was no navigation, that the edit is kept, and that saving t2 sends a PATCH for t2 with `If-Match: "7"`. This spec fails on the previous production code.

Validation:
- `npm --prefix web run test:ci -- --include=src/app/features/gtd/inbox/inbox-page.spec.ts --include=src/app/features/gtd/task-detail/task-detail.spec.ts` - Pass (2 files, 136 tests)
- Same command with the HEAD versions of the two production files put back temporarily - the new spec fails, as expected (production files restored afterwards)

### CR-6 - Inbox: overlapping actions that answer out of order leave a status line that is not about the latest action

Files changed:
- `web/src/app/features/gtd/inbox/inbox-page.ts`
- `web/src/app/features/gtd/inbox/inbox-page.spec.ts`

Problem:
`lastNote` was written in the order the answers arrived, not the order the actions started. If a slow conversion answered after a later action, it put back "Moved to reference" for an older item. If two conversions answered in reverse order, the line showed the first item's note.

Fix:
Each `run()` now takes a sequence number (`const seq = ++this.runSeq`). On success, the action sets `lastNote` to its note, or to null for actions other than "To reference", but only if `seq === this.runSeq`. Failures still leave the line unchanged, so the existing spec `a failed conversion keeps the note offered by the previous one` still holds.
The spec `counts two overlapping conversions that answer in reverse order` now also checks that the note of t2, the latest conversion, is offered. New spec `a slow conversion answering after a quicker later action leaves no note link`: it converts t1 slowly, trashes t2 quickly, then resolves the conversion. The line stays empty, the current item is t3, and progress is 67%. Both specs fail on the previous production code.

Validation:
- `npm --prefix web run test:ci -- --include=src/app/features/gtd/inbox/inbox-page.spec.ts --include=src/app/features/gtd/task-detail/task-detail.spec.ts` - Pass (2 files, 136 tests)
- Same command against the HEAD production files - both specs fail, as expected

## Not fixed

None.

Not part of the findings and left unchanged: a conversion still running from an earlier processing session can still set the line after `P` restarts processing, because `startProcessing()` does not advance `runSeq`. This case was not reported, and changing it would go beyond the requested scope.

## Validation summary

Focused tests:
- `npm --prefix web run test:ci -- --include=src/app/features/gtd/inbox/inbox-page.spec.ts --include=src/app/features/gtd/task-detail/task-detail.spec.ts` - Pass, 136 tests. With the old production code, 3 fail, which shows the specs cover both findings.

Full test suite:
- `npm --prefix web run check` (lint, all web unit tests, build) - Pass, exit 0: 83 files, 1547 tests. The build prints the existing warning that the initial bundle is over budget (651.08 kB against 500 kB). This change did not cause it.

Lint/type-check/build:
- Included in `npm --prefix web run check` - Pass

`.taskcheck`:
- `sh scripts/check.sh` - Fail (exit 1, after 9m 19s in the server Gradle `:test` task). 2 of 2907 server tests failed: `ClockConfigurationTests."the application clock is the system clock in UTC"` (a precision assertion: `...958544Z should be >= ...958544309Z`) and `ReminderEventsStreamTests."a fired reminder reaches a connected client after the commit as a reminder event with the item title"` (SSE timing). This round changed only files under `web/` and no server code. Both failures look like timing or precision flakes in the server and are outside this task's scope. The parent worker should re-run `.taskcheck`. The run also took well over the 2 minute target.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed. `npm --prefix web run check` passes. `.taskcheck` failed only on 2 server tests that this web-only change does not touch; see above.
