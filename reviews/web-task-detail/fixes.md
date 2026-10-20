# Fix Report

Date: 2026-10-20

Task: web-task-detail - Task detail editor

Scope: current task, `web/src/app/features/gtd/task-detail` only. Two fix rounds:

- Round 1: TW-1 (first `tests.md`) and CR-1, CR-2, CR-3, CR-4 (first `review.md`, commit 93e96703).
  Committed in 27456db6 (changes from an interrupted fixer run) and 0e87a2a4 (that run checked
  27456db6, completed CR-3 and added regression tests). An earlier version of this report cited a
  commit df724f18 and called the changes uncommitted. Both statements were wrong: df724f18 is not
  in the task history, and the round 1 changes are in 27456db6 and 0e87a2a4.
- Round 2: CR-1 and CR-2 from the second `review.md` (HEAD 0e87a2a4), and TW-2, TW-3, TW-4 from
  the second `tests.md` (commit e304ba25). Second-review CR-1 is the same problem as TW-2. Round 2
  changes are uncommitted in the working tree: `task-detail.ts`, `task-detail.spec.ts`,
  `task-checklist.ts`, `task-checklist.spec.ts`.

The first and second reviews both use the ids CR-1 and CR-2 for different findings. Below, the
first review's findings are named "R1 CR-n" and the second review's findings "R2 CR-n".

## Fixed findings

### Round 1

### TW-1 - Pressing the chosen "Next occurrence" mode again shows no mode selected

Files changed:
- `web/src/app/features/gtd/task-detail/task-detail.ts` (27456db6)
- `web/src/app/features/gtd/task-detail/task-detail.html` (27456db6)
- `web/src/app/features/gtd/task-detail/task-detail.spec.ts` (0e87a2a4)

Problem:
`Segmented` clears its own model when the chosen option is pressed again. `setRepeatMode` ignored
`undefined`, so the draft kept the mode but no button was shown as pressed.

Fix:
The template passes the picker (`#modePicker`) to `setRepeatMode(mode, picker)`. On `undefined`
it puts the draft's current mode back into `picker.value`. The draft is edited only when the mode
really changes. 0e87a2a4 added `switches the repeat mode after the chosen one was pressed again`.
The original TW-1 test is unchanged.

Validation:
- focused suite - Pass

### R1 CR-1 - Background reload overwrites edits typed while it runs; a stale reload can replace a newer save

Files changed:
- `web/src/app/features/gtd/task-detail/task-detail.ts` (27456db6)
- `web/src/app/features/gtd/task-detail/task-detail.spec.ts` (27456db6, 0e87a2a4)

Problem:
An event-triggered `load` checked `dirty()`/`saving()` only when it started, and always applied
its answer when it returned.

Fix:
`load` takes a kind (`initial`, `background`, `conflict`). A `background` answer is dropped when
the form is dirty or saving when the answer arrives, or when its version is older than the one
shown (`isStale`). `save()` increments `loadSeq`, so a GET started before the save is discarded.
The 412 path starts its own `conflict` load after that increment, so that load applies. Tests:
`keeps what the user typed while a background reload was running`, `does not replace a newer save
with an older background reload`, `ignores a background answer older than the version shown`
(27456db6); `still applies the conflict reload when a background reload was pending`, `shows a
change made elsewhere after the own save` (0e87a2a4).

Validation:
- focused suite - Pass

### R1 CR-2 - Checklist toggle and remove are not serialized

Files changed:
- `web/src/app/features/gtd/task-detail/task-checklist.ts` (27456db6)
- `web/src/app/features/gtd/task-detail/task-checklist.html` (27456db6)
- `web/src/app/features/gtd/task-detail/task-checklist.spec.ts` (27456db6)

Problem:
A toggle or remove could start while another checklist change was running. It then sent an old
item version (false 412), and the shared `busy` flag was cleared by the first change to finish.

Fix:
`toggleItem` and `removeItem` return early while `busy()`, like `addItem` and `reorder`. The
checkbox and the remove button are disabled while busy. Tests: `lets no second toggle or remove
start while a change is saving`, and `disables moving while a change is saving` now also checks
the checkboxes and remove buttons.

Validation:
- focused suite - Pass

### R1 CR-3 - Field errors other than the title are not linked to their inputs

Files changed:
- `web/src/app/features/gtd/task-detail/task-detail.html` (27456db6, 0e87a2a4)
- `web/src/app/features/gtd/task-detail/task-detail.spec.ts` (27456db6, 0e87a2a4)

Problem:
Notes, scheduled, waiting for, estimate, due time, tickler and custom rule errors had no id and
no `aria-describedby`. The rule error was outside its row.

Fix:
27456db6: each of these errors has an id (`td-notes-error`, `td-scheduled-error`,
`td-waiting-error`, `td-estimate-error`, `td-due-time-error`, `td-tickler-error`,
`td-rule-error`), and the matching input sets `[attr.aria-describedby]` while the error is shown.
The custom-rule error is inside its row. 0e87a2a4: a server rule error shown while a preset is
chosen has the id `td-repeat-error`, referenced by the Repeat select. Tests: `links every field
error to its field`, `links a server error on notes and due time to the field`, `links a server
rule error to the repeat choice when no custom rule is edited`.

Validation:
- focused suite - Pass

### R1 CR-4 - Save shortcut hint is hard-coded and wrong on macOS

Files changed:
- `web/src/app/features/gtd/task-detail/task-detail.html` (27456db6)
- `web/src/app/features/gtd/task-detail/task-detail.spec.ts` (27456db6)

Problem:
The `kbd` hint "Ctrl ↵" had no i18n marking and showed only Ctrl, although Cmd works too.

Fix:
The hint is marked `i18n="@@taskDetail.saveShortcut"`, reads `Ctrl/⌘ ↵` and is `aria-hidden`. The
submit button sets `aria-keyshortcuts="Control+Enter Meta+Enter Control+S Meta+S"`, matching
`onKeydown`. Test: `announces the save shortcuts on the save button`.

Validation:
- focused suite - Pass

### Round 2

### R2 CR-1 / TW-2 - A save of the previous task puts that task back into the editor after the route id changed (including a 412)

Files changed:
- `web/src/app/features/gtd/task-detail/task-detail.ts`
- `web/src/app/features/gtd/task-detail/task-detail.spec.ts`

Problem:
`TaskDetailPage` reuses one `TaskDetail` for every `/tasks/:id`. `save()` captured task A and,
after its awaits, always ran `task.set`/`draft.set` (success) or `task.set` plus `failed()`
(error). After a switch to task B, that put A back into the form shown under B, and the next save
PATCHed A. A 412 for A ran `load(this.taskId(), 'conflict')`, which reloaded B with a false
"changed elsewhere" banner. Other errors for A were shown on B too.

Fix:
In `save()`, after the move/patch answered and in the `catch`, the method returns when
`task.id !== this.taskId()`. A move or patch that went through for A stays saved on the server.
The form, the "Saved" note, server field errors, the save error and the conflict reload are all
skipped. `finally` still clears `saving`, so B can be saved afterwards. The background-reload
logic from R1 CR-1 is unchanged.

Tests:
- `keeps showing the new task when a save of the previous task answers late` (TW-2, committed in
  e304ba25, unchanged): now passes.
- New: `shows no conflict for the new task when a save of the previous task gets a 412 late`. The
  save of t1 is rejected with 412 after the switch to t2. It checks that no conflict message is
  shown, that no extra GET is sent, that the form still shows t2 and that the form stays clean.
  Without the `catch` guard this test fails (checked by removing the guard temporarily).

Validation:
- focused suite - Pass

### TW-3 - A checklist add answering after the task switched appends to the new task's list and clears its input

Files changed:
- `web/src/app/features/gtd/task-detail/task-checklist.ts`
- `web/src/app/features/gtd/task-detail/task-checklist.spec.ts`

Problem:
`addItem` appended the answered item and cleared `newItem` without checking that `taskId()` was
still the task the step was added to.

Fix:
`addItem` keeps the task id from when the user pressed Add. It sends the request for that id and,
when the answer arrives, appends the item and clears the input only if the id is still current.

Tests:
- `does not show a step added to the previous task in the new one` (committed in e304ba25): passes
  now. I added two lines to it: the user types into the input after the switch, and the test
  checks that this text is still there after the late answer. The committed test did not check
  the "clears its input" part of TW-3. No assertion was weakened.

Validation:
- focused suite - Pass

### TW-4 - A checklist change failing after the switch restores the previous task's steps or shows its error

Files changed:
- `web/src/app/features/gtd/task-detail/task-checklist.ts`
- `web/src/app/features/gtd/task-detail/task-checklist.spec.ts`

Problem:
`change()` ran the rollback (for remove this sets the old list `before`, which is task A's steps)
or a 412 reload plus the conflict message, and showed the error. It did this even when the
editor had switched to another task.

Fix:
`change()` keeps the task id when it starts. In `catch` it returns before the rollback, the 412
reload and the error message when that id is no longer current. `finally` still clears `busy`.
This covers toggle, remove, reorder and add, which all go through `change()`.

Tests:
- `does not put the steps of the previous task back when its remove fails late` (committed in
  e304ba25): passes now. I added `expect(alert()).toBeUndefined()` to it, because the committed
  test did not check the "or show its error" part of TW-4. No assertion was weakened.

Validation:
- focused suite - Pass

## Not fixed

### R2 CR-2 - Following a link out of the editor silently drops unsaved changes, while Close asks first

Status:
Out of scope (requires decision)

Reason:
The parent worker decided not to fix it in this task (P3, no acceptance criterion requires a
navigation confirmation). No code was changed for it.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include='src/app/features/gtd/task-detail/**/*.spec.ts'` - Pass: 6 files, 165 tests passed (before round 2: 164 tests, 3 failing)
- `cd web && npx prettier --check src/app/features/gtd/task-detail/` - Pass: "All matched files use Prettier code style!"

Full test suite:
- `cd web && npm run check` - Pass: "src/app/api/generated is up to date.", "All files pass linting.", e2e typecheck passed, test:ci 51 files / 922 tests passed, ng build complete

Lint/type-check/build:
- included in `npm run check` (lint, e2e:typecheck, ng build) - Pass

`.taskcheck`:
- `sh scripts/check.sh` from the repository root - Pass ("== all checks passed")

Remaining unresolved findings:
1 (R2 CR-2, not fixed by decision of the parent worker)

## Final status

All validated findings fixed and validation passed. R2 CR-2 was left unfixed on purpose: the
parent worker excluded it from this task.

## Round 3

Date: 2026-10-21

Task: web-task-detail - Task detail editor

Scope: R3 CR-1 from the third `review.md` (reviewed revision 94325b03), `web/src/app/features/gtd/task-detail` only. Changes are uncommitted; the parent worker commits them.

### Fixed findings

### R3 CR-1 - Text typed while a save is running is overwritten when the save finishes

Files changed:
- `web/src/app/features/gtd/task-detail/task-detail.ts`
- `web/src/app/features/gtd/task-detail/task-detail.spec.ts`

Problem:
When a save succeeded, `save()` always ran `this.draft.set(draftOf(current))`. The form stays
editable while a save runs, so any text typed before the PATCH (or move + PATCH) answered was
replaced by the saved version, and "Unsaved changes" disappeared. Confirmed against the code at
HEAD before changing it.

Fix:
`save()` still sets `task` to the saved version, so the next save uses its ETag. It replaces the
draft only if `this.draft()` is still the same object the save started from. If the user edited
meanwhile, their draft stays. `dirty` and `patchFor` compare it with the new `task`, so the form
stays dirty and the next save sends only the remaining changes. The "Saved" flash still runs, and
the status bar shows "Unsaved changes" first while the form is dirty. The error and conflict paths
did not change.

Tests added:
- `keeps what the user typed while a save was running`: types into `#td-notes` while the PATCH is
  pending. After the answer, the notes text and the title are kept, "Unsaved changes" is shown and
  Save is enabled. The next save sends `If-Match: "2"` with body `{ notesMd: 'Typed while saving' }`
  only. This test fails without the fix (checked by reverting the one line: 1 failed, 170 passed).
- `is not dirty when the text typed during a save matches the saved version`: an edit during the
  save that ends up equal to the saved value leaves the form clean.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/gtd/task-detail/**/*.spec.ts'` - Pass: 6 files, 171 tests passed

### Not fixed

None in this round. R2 CR-2 is still excluded by the parent worker's decision (see above).

### Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include='src/app/features/gtd/task-detail/**/*.spec.ts'` - Pass: 6 files, 171 tests passed
- `cd web && npx prettier --check src/app/features/gtd/task-detail` - Pass: "All matched files use Prettier code style!"
- `cd web && npm run lint` - Pass: "All files pass linting."

Full test suite:
- `cd web && npm run check` - Pass: 53 files, 983 tests passed

Lint/type-check/build:
- included in `npm run check` - Pass

`.taskcheck`:
- `sh scripts/check.sh` from the repository root - Pass ("== all checks passed")

Remaining unresolved findings:
0 from round 3 (R2 CR-2 stays excluded by decision)

### Final status

All validated findings fixed and validation passed.
