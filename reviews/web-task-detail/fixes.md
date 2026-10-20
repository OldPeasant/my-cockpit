# Fix Report

Date: 2026-10-20

Task: web-task-detail - Task detail editor

Scope: current task, `web/src/app/features/gtd/task-detail` only. Findings TW-1
(`reviews/web-task-detail/tests.md`) and CR-1, CR-2, CR-3, CR-4 (`reviews/web-task-detail/review.md`).

An earlier fixer run was interrupted before it wrote a report. Its changes are committed as
df724f18. This run checked that commit against each finding and completed the gaps it found
(CR-3: a rule error shown without the custom-rule input was still not linked to any control). It
also added regression tests for the CR-1 edge cases that df724f18 did not test, and a TW-1
follow-up test. These changes are uncommitted in the working tree:
`task-detail.html`, `task-detail.spec.ts`.

## Fixed findings

### TW-1 - Pressing the chosen "Next occurrence" mode again shows no mode selected

Files changed:
- `web/src/app/features/gtd/task-detail/task-detail.ts` (df724f18)
- `web/src/app/features/gtd/task-detail/task-detail.html` (df724f18)
- `web/src/app/features/gtd/task-detail/task-detail.spec.ts` (this run)

Problem:
`Segmented` clears its own model when the chosen option is pressed again. `setRepeatMode` ignored
`undefined`, so the draft kept the mode but no button was shown as pressed.

Fix (df724f18, validated):
The template passes the picker (`#modePicker`) to `setRepeatMode(mode, picker)`. On `undefined`
it puts the draft's current mode back into `picker.value`. The draft is edited only when the mode
really changes. The re-emitted value equals the current mode, so it causes no edit and no loop.
This run added `switches the repeat mode after the chosen one was pressed again`. It checks that
after the reset the other mode can still be chosen, is shown as pressed, and is saved
(`recurrenceMode: 'FIXED'`). The original TW-1 test is unchanged and now passes.

Validation:
- focused suite (command below) - Pass

### CR-1 - Background reload overwrites edits typed while it runs; a stale reload can replace a newer save

Files changed:
- `web/src/app/features/gtd/task-detail/task-detail.ts` (df724f18)
- `web/src/app/features/gtd/task-detail/task-detail.spec.ts` (df724f18 and this run)

Problem:
An event-triggered `load` checked `dirty()`/`saving()` only when it started, and always applied
its answer when it returned.

Fix (df724f18, validated):
`load` now takes a kind (`initial`, `background`, `conflict`). A `background` answer is dropped
when the form is dirty or saving at the time the answer arrives, or when its version is older than
the one shown (`isStale`). `save()` increments `loadSeq`, so a GET that started before the save is
discarded. Checked against the questions from the parent:
- Conflict reload: `failed()` runs inside `save()` after the increment and starts its own
  `load(..., 'conflict')` with a new sequence number. That load is the newest, and `conflict` loads
  skip the `isStale` check (the form is dirty and saving at that point). So it applies, and a late
  answer from an earlier background GET is discarded. New test:
  `still applies the conflict reload when a background reload was pending`. The late old copy does
  not overwrite the reloaded "Theirs" version, and the next save sends `If-Match: "5"`.
- Reload after the own save: the SSE event after a save starts a background load when the save is
  finished (`saving()` false, form clean). An answer with the same or a newer version applies. New
  test: `shows a change made elsewhere after the own save`.
- df724f18 tests: `keeps what the user typed while a background reload was running`,
  `does not replace a newer save with an older background reload` (the next save uses
  `If-Match: "2"`, no false conflict), `ignores a background answer older than the version shown`.

Validation:
- focused suite - Pass

### CR-2 - Checklist toggle and remove are not serialized

Files changed:
- `web/src/app/features/gtd/task-detail/task-checklist.ts` (df724f18)
- `web/src/app/features/gtd/task-detail/task-checklist.html` (df724f18)
- `web/src/app/features/gtd/task-detail/task-checklist.spec.ts` (df724f18)

Problem:
A toggle or remove could start while another checklist change was running. It then sent an old
item version (false 412), and the shared `busy` flag was cleared by the first change to finish.

Fix (df724f18, validated):
`toggleItem` and `removeItem` return early while `busy()`, like `addItem` and `reorder` already
did. The checkbox and the remove button are disabled while `busy()`. All four entry points into
`change()` are now guarded, so `change()` calls can no longer overlap, and the single `busy` flag
is correct (set at the start, cleared in `finally` of the only running change, including the 412
reload). Toggles use the item from the rendered `items()`, which holds the version from the
server's last answer. Tests: `lets no second toggle or remove start while a change is saving`
(one request only, then the next toggle sends version 2), and the reorder test
`disables moving while a change is saving` now also asserts that the checkboxes and remove buttons
are disabled during a reorder. Dragging during a change reaches `reorder`, which ignores it.

Validation:
- focused suite - Pass

### CR-3 - Field errors other than the title are not linked to their inputs

Files changed:
- `web/src/app/features/gtd/task-detail/task-detail.html` (df724f18 and this run)
- `web/src/app/features/gtd/task-detail/task-detail.spec.ts` (df724f18 and this run)

Problem:
Notes, scheduled, waiting for, estimate, due time, tickler and custom rule errors had no id and
no `aria-describedby`. The rule error was outside its row.

Fix:
df724f18 (validated): each of these errors has an id (`td-notes-error`, `td-scheduled-error`,
`td-waiting-error`, `td-estimate-error`, `td-due-time-error`, `td-tickler-error`,
`td-rule-error`), and the matching input sets `[attr.aria-describedby]` while the error is shown.
The custom-rule error is now inside the rule's row.
This run: a server `recurrenceRule`/`recurrenceMode` error while a preset (not "custom") is
chosen was shown as a separate `role="alert"` paragraph that no control referenced. It now has the
id `td-repeat-error`, and the Repeat select references it through `aria-describedby`. New test:
`links a server rule error to the repeat choice when no custom rule is edited`. Existing tests from
df724f18: `links every field error to its field`, `links a server error on notes and due time to
the field`.

Validation:
- focused suite - Pass

### CR-4 - Save shortcut hint is hard-coded and wrong on macOS

Files changed:
- `web/src/app/features/gtd/task-detail/task-detail.html` (df724f18)
- `web/src/app/features/gtd/task-detail/task-detail.spec.ts` (df724f18)

Problem:
The `kbd` hint "Ctrl ↵" had no i18n marking and showed only Ctrl, although Cmd works too.

Fix (df724f18, validated):
The hint is marked `i18n="@@taskDetail.saveShortcut"` and reads `Ctrl/⌘ ↵`. It is `aria-hidden`.
The submit button announces the real shortcuts with
`aria-keyshortcuts="Control+Enter Meta+Enter Control+S Meta+S"`, matching `onKeydown`. Test:
`announces the save shortcuts on the save button`.

Validation:
- focused suite - Pass

## Not fixed

None.

Observation (no finding, not changed): when the route id changes while a save of the previous
task is still running, `save()` ends with `task.set(current)` for the previous task. This was
there before and is not part of CR-1 (which covers background reloads against edits and saves). The
parent can decide whether it needs a finding.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include='src/app/features/gtd/task-detail/**/*.spec.ts'` - Pass: 6 files, 160 tests passed
- `cd web && npx prettier --check src/app/features/gtd/task-detail/` - Pass: "All matched files use Prettier code style!"

Full test suite:
- `cd web && npm run check` - Pass (exit 0): lint "All files pass linting.", test:ci 51 files / 917 tests passed, build complete

Lint/type-check/build:
- included in `npm run check` (lint, e2e:typecheck, ng build) - Pass

`.taskcheck`:
- Not run: the sandbox did not allow this helper to run `sh scripts/check.sh` from the repository root. Its web step is `npm run check`, which passes. The parent worker must run `.taskcheck`.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
