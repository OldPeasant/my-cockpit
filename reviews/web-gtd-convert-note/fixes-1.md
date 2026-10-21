# Fix Report

Date: 2026-10-21

Task: web-gtd-convert-note - Inbox/task detail: to reference

Scope: current task. The parent worker supplied findings CR-3 and CR-4 from `reviews/web-gtd-convert-note/review.md`, on top of commits 4e0c4b53 (implementation) and 59c9af2b (tests).

## Fixed findings

### CR-3 - The "Moved to reference" status stays up while later items are processed with other actions

Files changed:
- `web/src/app/features/gtd/inbox/inbox-page.ts`
- `web/src/app/features/gtd/inbox/inbox-page.spec.ts`

Problem:
`lastNote` was reset only in `startProcessing()`. After converting item A, the banner "Moved to reference as the note A" stayed up while later items were moved to Next, Someday and so on.

Fix:
In `run()`, after a non-reference action succeeds, `lastNote` is cleared. A failed action leaves it alone: the store rolls the failure back, so the banner still describes the last action that went through. New test: "forgets the offered note once a later item gets another action, not when it fails". It converts t1, checks that a failed move of t2 keeps the link, then checks that a successful move of t2 removes the banner.

Validation:
- New test run against the unfixed `inbox-page.ts` (HEAD version) - Fail (expected: the banner was still shown)
- `npm --prefix web run test:ci -- --include=src/app/features/gtd/inbox/inbox-page.spec.ts --include=src/app/features/gtd/task-detail/task-detail.spec.ts` with the fix - Pass
- `npm --prefix web run check` - Pass

### CR-4 - A change event can still start a background reload while the conversion runs

Files changed:
- `web/src/app/features/gtd/task-detail/task-detail.ts`
- `web/src/app/features/gtd/task-detail/task-detail.spec.ts`

Problem:
The TASK change-event handler checked `dirty()` and `saving()` but not `converting()`. A non-DELETED event that arrived during the conversion therefore started a background load. If that load got a 404 (the task was already trashed), the view switched to "not found" before the navigation to the note.

Fix:
Added `&& !this.converting()` to the handler condition, so no background reload starts while the conversion runs. Loads started before the conversion were already cancelled by the existing `loadSeq++` in `toReference()`, so `isStale` did not need to change. New test: "does not reload on a change event while the conversion runs". It makes GET answer 404, sends an UPDATED event while the conversion is pending, and checks that there is no second GET, no "not found" view, and that the navigation to the note still happens.

Validation:
- New test run against the unfixed `task-detail.ts` (HEAD version) - Fail (expected: 2 GET calls instead of 1)
- Same focused test command as above with the fix - Pass
- `npm --prefix web run check` - Pass

## Not fixed

### CR-1 - Committed specs still said "To reference" is disabled, and the new action had no tests

Status:
Invalid (already resolved)

Reason:
The parent worker says this is already handled by test commit 59c9af2b. The current specs cover the action, and the full web check passes. No change was made.

### CR-2 - /notes/:id route

Status:
Out of scope

Reason:
The parent worker accepted it: the `/notes/:id` route comes with the task web-notes-editor. No change was made.

## Validation summary

Focused tests:
- `npm --prefix web run test:ci -- --include=src/app/features/gtd/inbox/inbox-page.spec.ts --include=src/app/features/gtd/task-detail/task-detail.spec.ts`: without the production fixes, 2 failed and 127 passed. Both failures were the new regression tests, as expected. With the fixes, all pass.

Full test suite:
- `npm --prefix web run check` (api:check, lint, e2e:typecheck, test:ci, ng build) - Pass. All files pass linting; 83 test files and 1540 tests passed; the build finished. The build also prints a warning that the initial bundle is over its size budget. These changes did not cause that warning.

Lint/type-check/build:
- Included in `npm --prefix web run check` - Pass

`.taskcheck`:
- Not run. The parent worker asked for the web check only. `scripts/check.sh` also covers server/ and android/, which these changes do not touch.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
