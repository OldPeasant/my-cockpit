# Fix Report

Date: 2026-11-08

Task: web-task-actions - Web task detail: complete, delete, undo, Markdown notes

Scope: current task. The approved findings are CR-1, CR-3 and CR-5 from `reviews/web-task-actions/review.md` and TW-1 and TW-2 from `reviews/web-task-actions/tests.md`. Base 4102a26a8, HEAD 32f9aba05. The changes are uncommitted in the working tree.

## Fixed findings

### CR-1 - Every checklist step is struck through, not just the done ones (P1)

Files changed:
- `web/src/app/features/gtd/task-detail/task-checklist.scss`
- `web/src/app/features/gtd/task-detail/task-checklist.spec.ts`

Problem:
Under `.step`, the strike-through and muted color applied to every `.step-text`. The rename input's `min-width: 0` and body-medium font applied only under `&.done`.

Fix:
Restored `&.done .step-text { color: muted; text-decoration: line-through; }`. Moved the `.rename { min-width: 0; font: body-medium; }` rule out of `&.done`, so it now applies to every step.

Tests added:
- "strikes through only the done steps": checks the `done` class per step and reads `getComputedStyle(label).textDecoration` (`['', 'line-through', '']`).
- "styles the rename input of an open step too": checks the computed `min-width` of the rename input on an open step.
jsdom applies the component stylesheet for these simple properties. It cannot resolve the `font: var(...)` shorthand, so the font is not asserted. To prove the tests catch the bug, I ran them against the HEAD stylesheet: both failed (2 failed, 42 passed). They pass with the fix.

Validation:
- `npx ng test --watch=false --include='src/app/features/gtd/task-detail/task-checklist.spec.ts'` - Pass (44 passed)

### CR-3 - Edits typed while Complete/Reopen runs are silently discarded (P2)

Files changed:
- `web/src/app/features/gtd/task-detail/task-detail.ts`
- `web/src/app/features/gtd/task-detail/task-detail.spec.ts`

Problem:
After `service.complete`/`uncomplete` answered, `toggleDone` called `showSaved(result)`. That replaced the draft unconditionally and overwrote anything typed while the request ran.

Fix:
`toggleDone` now captures the draft before the action. On success it always sets `task`, but replaces the draft only when `this.draft()` is still that captured draft. This is the same pattern `save()` uses. `reopenFromUndo` follows the same rule. It captures the draft only when the form is clean, then always updates `task` to the reopened version (when the same task is shown and nothing else is running). It replaces the draft only when the form was clean and has not changed since. As a result, unsaved edits survive an Undo and are now compared with the reopened version. Before, the editor kept showing the completed version, so a later save would hit a 412. `showSaved` had no callers left and was removed.

Tests added (task-detail.spec.ts, "complete, reopen and delete"):
- "keeps edits typed while completing runs, compared with the completed version": the edits stay, "Unsaved changes" is shown, the button says Reopen, Undo is offered, and Save sends If-Match "2".
- "keeps edits typed while reopening runs".
- "Undo keeps edits typed while it runs and shows the task reopened".
- Extended the existing "Undo does not replace unsaved edits made after completing" test: the button now shows Complete, and Save uses the reopened version (If-Match "3").

Validation:
- `npx ng test --watch=false --include='src/app/features/gtd/task-detail/task-detail.spec.ts'` - Pass (142 passed)

### CR-5 - The guard against the DELETED event ends before the page navigates away (P3)

Files changed:
- `web/src/app/features/gtd/task-detail/task-detail.ts`
- `web/src/app/features/gtd/task-detail/task-detail.spec.ts`

Problem:
The DELETED event was ignored only while `acting() === 'delete'`. That flag clears in `act()`'s `finally`, right after `closed.emit()`. An event arriving before the route changed flashed the "moved to the Trash" view.

Fix:
New private field `deletedId`. `remove()` sets it before the request and keeps it after success. A failed delete clears it. A switch to another task id also clears it, so a later deletion by someone else still shows. The event handler ignores DELETED only for `deletedId`.

Tests added:
- "does not show \"moved to the Trash\" for its own deletion event arriving after the answer": the event arrives after `closed` was emitted.
- "shows \"moved to the Trash\" when someone else deletes it after its own delete failed": a guard test.
The existing tests "does not show ... for its own deletion event" (event before the answer) and "still shows ... during a completion" still pass.

Validation:
- `npx ng test --watch=false --include='src/app/features/gtd/task-detail/task-detail.spec.ts'` - Pass

### TW-1 - Complete answering after a task switch offers no Undo (P3)

Files changed:
- `web/src/app/features/gtd/task-detail/task-detail.ts`
- `web/src/app/features/gtd/task-detail/task-detail.spec.ts`

Problem:
`toggleDone` returned before calling `undo.completed(...)` when the editor had switched tasks, unlike Delete.

Fix:
For Complete, `undo.completed(task, () => this.reopenFromUndo(result))` is now called before the switched-task check. `reopenFromUndo` already reopens the old task without touching the one now shown.

Tests added:
- "offers Undo for a completion that answers after switching to another task": Undo is offered for t1. Undo sends `uncompleteTask` for t1 with If-Match "2", and t2 stays as it was (title, Complete button).

Validation:
- `npx ng test --watch=false --include='src/app/features/gtd/task-detail/task-detail.spec.ts'` - Pass

### TW-2 - Project detail closes "Choose next step" on undo by title (P3)

Files changed:
- `web/src/app/features/gtd/projects/project-detail-page.ts`
- `web/src/app/features/gtd/projects/projects-pages.spec.ts`

Problem:
The prompt was closed when `prompt().done === task.title`. Undoing one of two tasks with the same title could close the other task's prompt.

Fix:
The prompt state is now a `NextStepPrompt` interface with `done` (the displayed title, unchanged) and a new `doneId`. `complete()` opens it with `{ done: task.title, doneId: task.id }` and closes it on undo only when `doneId === task.id`. The template and the displayed text are unchanged.

Tests added:
- "does not close the prompt of another completed task with the same title". This test failed with the title comparison and passes with the id comparison.

Validation:
- `npx ng test --watch=false --include='src/app/features/gtd/projects/projects-pages.spec.ts'` - Pass (22 passed)

## Not fixed

### CR-2 - The new behaviour has almost no unit tests in the reviewed commits

Status:
Invalid (already resolved)

Reason:
The test-writer's tests are committed in 32f9aba05 and cover the listed behaviours. The parent worker excluded this finding from the fixer's scope.

### CR-4 - The Markdown styles for notes duplicate the project description styles

Status:
Out of scope

Reason:
The parent worker deliberately deferred sharing the Markdown styles and `renderMarkdown` across gtd features. No change was made.

## Validation summary

Focused tests (from `web/`; run as `npm --prefix web exec -- ng test ...` from the repo root):
- `npx ng test --watch=false --include='src/app/features/gtd/task-detail/task-checklist.spec.ts'` - Pass, 44 passed
- `npx ng test --watch=false --include='src/app/features/gtd/task-detail/task-detail.spec.ts'` - Pass, 142 passed
- `npx ng test --watch=false --include='src/app/features/gtd/projects/projects-pages.spec.ts'` - Pass, 22 passed
- Regression check: the new tests were run against the HEAD versions of the production files and fail there: checklist 2 failed, task-detail 6 failed, projects 1 failed. The fixed files were then restored.
- `npx ng test --watch=false --include='src/app/features/gtd/**/*.spec.ts'` - Pass, 33 files, 868 tests

Full test suite:
- `npm run test:ci` (web) - Pass, 190 files, 4423 tests

Lint/type-check/build:
- `npm run lint` (web) - Pass, all files pass linting
- `npx tsc -p web/tsconfig.app.json --noEmit` and `npx tsc -p web/tsconfig.spec.json --noEmit` - Pass
- `npx prettier --check` on the 6 changed files - Pass, after `prettier --write` on `projects-pages.spec.ts`
- `npm run check` (full web check incl. build) - Not run; left for the parent worker

`.taskcheck`:
- Not run. `sh scripts/check.sh` includes the Gradle server and Android checks, which the parent worker runs.

Remaining unresolved findings:
0 of the approved findings. CR-2 is already resolved and CR-4 is deferred by decision.

## Final status

All validated findings fixed and validation passed.

The test-writer's coverage numbers in `tests.md` are stale because the production code changed. Re-run the test-writer and code-reviewer on the final code.
