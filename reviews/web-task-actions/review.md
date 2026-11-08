# Code Review

Date: 2026-11-08

Task: web-task-actions - Web task detail: complete, delete, undo, Markdown notes

Scope: third review, on the final code. `git diff 6871a30a616a9df59b921d877b9ed23119f78133..HEAD -- web/` (task commits cf34e97a8, 77769e63b, 67fb1f56e, 6e9442aa8, 9f4905fcd, 84f653507). Committed changes only. Uncommitted spec changes a test-writer may be making in parallel were not reviewed. The focus is the last fix commit 84f653507, which addresses the second review's CR-1 and CR-2. The earlier commits were reviewed twice before and checked again here for regressions.

Requirements:
- tasks/web-task-actions.md (acceptance criteria and the recorded assumptions)
- SPEC.md §4.2 (Trash, ETag/If-Match), §5.2 (task rules, checklist)
- docs/ARCHITECTURE.md conventions, AGENTS.md "Project notes"
- reviews/web-task-actions/review.md (second review) and reviews/web-task-actions/fixes.md (both fix rounds)

Git range:
- Branch: main
- HEAD: 84f65350795165c8d1f44aec8dc630455cc67d4c
- Base/start: 6871a30a616a9df59b921d877b9ed23119f78133
- Commits reviewed: 6

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Verification of the second review's findings

- CR-1 (Complete continued after a save that hit a 412): fixed. `save()` (`task-detail.ts:468-506`) now resolves to a boolean. It returns true only after a successful save when the same task is still shown (line 495). It returns false on every early return (line 472), on a switch during the save (line 487), and after a failure, including the 412 path through `failed()` (lines 498, 502). `toggleDone` (`task-detail.ts:309-313`) returns unless `save()` resolved to true, so the conflict reload no longer clears the way for `act()`, and the conflict notice stays. The other callers (`onKeydown` with `void this.save()`, and the template's submit binding) ignore the result, so they behave as before. Regression test: "does not complete when saving the changes first meets a 412" (`task-detail.spec.ts:2555`). It checks that `completeTask` is not called, the conflict message is shown, the form shows the reloaded title, the button still says Complete, and no Undo is offered.
- CR-2 (Complete could complete another task after a switch during the save): fixed. `toggleDone` captures `this.taskId()` before saving and returns when it differs afterwards (`task-detail.ts:310-312`). `save()` also returns false in that case, so either check stops the action. The `dirty()` check after the save is kept, so edits typed while the save ran still stop Complete. Regression test: "does not complete the new task when the editor switches during the save first" (`task-detail.spec.ts:2576`). It holds the PATCH, switches to t2, then releases the PATCH, and checks that `completeTask` is not called, t2 is shown unchanged, and no Undo is offered.
- CR-3 (duplicated Markdown styles, `renderMarkdown` imported from `gtd/projects`): still present. The parent worker deferred it on purpose. It is kept below so the decision stays visible.

No regressions from the fix were found. Also rechecked on the final code:
- Complete with an invalid draft is still blocked, because the button is disabled and `save()` returns false.
- After a successful save, `saving` is reset in `finally` before `toggleDone` continues, so `act()` is not blocked by `busy()`.
- All 13 new i18n ids are in both `messages.xlf` and `messages.de.xlf`.
- Delete, Undo and the list undo paths (`list-page.ts`, `next-view.ts`, `today-view.ts`, `project-detail-page.ts`) are unchanged since the second review.

Acceptance criteria, as far as code review can show:
1. Task detail has Complete/Reopen and Delete, and Delete navigates back through `closed`. Met in the code.
2. An Undo snackbar appears after completing or deleting in task detail, and after completing in Next, Today, the list pages and project detail. Met in the code. Undo in the lists covers completing only, which is recorded as an assumption.
3. Checklist items can be renamed inline. Met in the code.
4. Task notes are rendered as Markdown when not editing. Met in the code.
5. The new UI strings are in English and German. Met.
6. Unit tests cover the new behavior. Met. Whether `npm run check` and `sh scripts/check.sh` pass is for the parent worker to verify, because this review ran neither.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | The Markdown styles for notes duplicate the project description styles (deferred)**
    - File: `web/src/app/features/gtd/task-detail/task-detail-notes.scss:27-66`, `web/src/app/features/gtd/task-detail/task-detail.ts:25`
    - Category: Quality
    - Problem: The `.markdown` block copies `gtd/projects/project-description.scss` and adds only `cursor: pointer` on links. `task-detail` imports `renderMarkdown` from the sibling feature `gtd/projects`. The parent worker deferred this on purpose (first review CR-4, second review CR-3).
    - Why it matters: Later changes to Markdown rendering have to be made in two stylesheets, and the copies will drift.
    - Suggested fix: In a follow-up task, move `renderMarkdown` and a shared `.markdown` partial to `gtd/ui` or `shared`.

## Review conclusion

No material issues found. The second review's CR-1 and CR-2 are fixed, and each has a regression test. The only remaining finding is the P3 duplication, deferred on purpose by the parent worker.
