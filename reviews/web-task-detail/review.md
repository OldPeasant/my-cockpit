# Code Review

Date: 2026-10-21

Task: web-task-detail - Task detail editor

Scope: `git diff 0e302d91..94325b03 -- web` (committed code at HEAD): the `web/src/app/features/gtd/task-detail/` feature folder plus the `tasks` route line in `web/src/app/features/gtd/gtd.routes.ts`. This is the third review, on the final revision after the fixes for R2 CR-1 and TW-2..TW-4 (a1822b65) and the final test pass (c0e95e04, 94325b03).

Requirements:
- tasks/web-task-detail.md acceptance criteria 1-4 and the recorded assumptions (routed page /tasks/:id, plain Markdown textarea, waiting-for as free text, move-then-patch save, client-side RRULE validator, only Close confirms discarding edits)
- SPEC.md §4.2 (If-Match / 412), §5.2 (task fields)
- docs/ARCHITECTURE.md, AGENTS.md "Project notes"
- reviews/web-task-detail/fixes.md (round 2), reviews/web-task-detail/tests.md (third run), previous review.md (R2 CR-1, R2 CR-2)

Git range:
- Branch: main
- HEAD: 94325b0303ddeaf0718fb6115fb033f476a42bb1
- Base/start: 0e302d91260eaa7c18f630493c052de155b8a23f
- Commits reviewed: 9 commits touching `web/` (7dd31f0a, 3dc98d8d, 4834eed4, f3aed0f3, 63957523, aa5dafe1, a1822b65, c0e95e04, 94325b03); no uncommitted changes at review time

Summary: 0 P0, 0 P1, 1 P2, 0 P3

## Earlier findings

- R2 CR-1 / TW-2 / TW-3 (a save of the previous task answering after the route id changed): resolved. `save()` returns before applying the result, the "Saved" flash, the error or the 412 conflict reload when `task.id !== this.taskId()` (`task-detail.ts:262`, `:268`). `finally` still clears `saving`. A move that already went through still gets its PATCH with the version the move returned, which is correct: both belong to the previous task.
- TW-4 and the checklist part of R2 CR-1: resolved. `addItem` captures the task id and appends the new step only while it is still current (`task-checklist.ts:63-67`). `change()` returns before the rollback, the 412 reload and the error when the id changed (`task-checklist.ts:123`, `:130`). A late successful toggle or reorder calls `replace()` with an item of the previous task, which matches no id in the new list, so it changes nothing.
- R2 CR-2 (link navigation drops unsaved edits): out of scope by the recorded assumption #6. Not reported again.
- The tests named in tests.md exist and cover these paths. I ran `cd web && npx ng test --watch=false --include='src/app/features/gtd/task-detail/**/*.spec.ts'` -> 6 files, 169 tests passed.

Acceptance criteria against the code: AC1 every §5.2 field listed is in `task-detail.html` (title, Markdown notes, list, project, contexts, energy, creativity, estimate, due date and time, scheduled, tickler, waiting for and since), the checklist supports add/toggle/remove/reorder, and the recurrence editor has presets plus a validated custom RRULE with the mode. AC2 `TaskDetailService.update/move` and the checklist writes send `If-Match`, and a 412 sets the conflict banner and reloads. AC3 `<app-linked-items>` is in the form. AC4 the spec files cover validation, 412 handling and presets. `python tasks.py verify web-task-detail` still shows criteria 1-4 unrecorded. The parent worker has to record the evidence, including an `npm run check` run on this revision.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Text typed while a save is running is overwritten when the save finishes**
    - File: `web/src/app/features/gtd/task-detail/task-detail.ts:246-264`; `web/src/app/features/gtd/task-detail/task-detail.html:56-66`, `:76-86` (and the other inputs)
    - Category: Correctness
    - Problem: `save()` sends the `draft` it read at the start. When the request succeeds it runs `this.draft.set(draftOf(current))` (line 264) without checking whether the draft changed in the meantime. The form stays editable during a save: only the submit button is disabled (`task-detail.html:335`), and the title, notes and other inputs have no `disabled`/`readonly` binding. So if the user presses Ctrl+Enter or Ctrl+S (the advertised shortcut, `onKeydown`) in the notes and keeps typing, everything typed until the PATCH (or move + PATCH) answers is replaced by the saved version. The `[value]` bindings then reset the textarea, and the "Unsaved changes" state disappears, so the user gets no hint that anything was lost. No test types during a pending save. `does not save twice while a save is running` only presses the shortcut again.
    - Why it matters: Notes are the main free-text field, and saving with the keyboard while still typing is a normal habit. Losing input without any notice is an overlapping-write defect of the kind listed in the review checklist. It is not a wrong-task write, so P2.
    - Suggested fix: At the end of a successful save, compare `this.draft()` with the `draft` the save started from. If it is the same object, replace it with `draftOf(current)` as now. If it differs, set `task` to `current` and keep the user's newer draft, so it stays dirty against the new version and the next save sends only the remaining changes with the new ETag. Alternatively, make the fields read-only while `saving()`. Add a test that types into `#td-notes` while the PATCH is pending and asserts that the text is still there and still unsaved after the answer.

## P3 Low

None.

## Review conclusion

Material issues found; fix before completing the task. R2 CR-1 and TW-2..TW-4 are resolved, and R2 CR-2 is excluded by a recorded assumption. CR-1 (P2) loses input typed during a save and should be fixed, or recorded as an accepted limitation, before `done`. Evidence for AC1-AC4 still has to be recorded with `tasks.py verify`.
