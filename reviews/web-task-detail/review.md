# Code Review

Date: 2026-10-21

Task: web-task-detail - Task detail editor

Scope: committed code at HEAD 65e739b9 on main: the `web/src/app/features/gtd/task-detail/` feature folder plus the `tasks` route line in `web/src/app/features/gtd/gtd.routes.ts`. This is the fourth review. It checks the fix for R3 CR-1 (65e739b9) and re-checks the whole task against its acceptance criteria.

Requirements:
- tasks/web-task-detail.md acceptance criteria 1-4 and the recorded assumptions (routed page /tasks/:id, plain Markdown textarea, waiting-for as free text, move-then-patch save, client-side RRULE validator, only Close confirms discarding edits)
- SPEC.md §4.2 (If-Match / 412), §5.2 (task fields)
- docs/ARCHITECTURE.md, AGENTS.md "Project notes"
- reviews/web-task-detail/fixes.md (rounds 1-3), previous review.md (R3 CR-1)

Git range:
- Branch: main
- HEAD: 65e739b9f61c9124bec47ae2bb4b55b381528ebe
- Base/start: 415aa28575661a4b53998995ea79a34e93abc478 (merge-base with origin/main); task code commits from d048b273 to 65e739b9 (`git log -- web/src/app/features/gtd`)
- Commits reviewed: 10 task commits touching `web/src/app/features/gtd` (d048b273, ee63f0f7, be29fc09, 048c23ab, 31d250b5, 56e5d3f5, 8769f6b1, a95267c9, 388b630c, 65e739b9). The working tree has an uncommitted change to `task-detail.spec.ts` from the concurrent test-writer run. It was not reviewed.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Earlier findings

- R3 CR-1 (text typed while a save runs was overwritten): resolved. `save()` still sets `task` to the saved version, so the next save sends the new ETag. It replaces the draft only when `this.draft()` is still the same object the save started from (`task-detail.ts:265`). `edit()` always creates a new draft object, so any edit during the save keeps the user's draft. `dirty`/`patchFor` then compare that draft with the saved version, so only the remaining changes are sent. `hasChanges` trims the title and normalizes the rule, so an edit that ends up equal to the saved value leaves the form clean. The error path does not change the draft. The 412 path still replaces the draft through the conflict reload, which is the behavior AC2 requires, and the banner says so. A change event during the save is skipped (`saving()`). One that arrives later is skipped while the kept draft is dirty. Tests: `keeps what the user typed while a save was running` (checks the text is kept, "Unsaved changes" and the second PATCH `If-Match: "2"` with only `notesMd`) and `is not dirty when the text typed during a save matches the saved version` (`task-detail.spec.ts:893-945`).
- R2 CR-1, TW-2..TW-4 (late answers after the task switched): still resolved. The guards are in `task-detail.ts:262`, `:269` and `task-checklist.ts:67`, `:130`, `:146-148`.
- R2 CR-2 (link navigation drops unsaved edits): out of scope by the recorded assumption. Not reported again.

## Acceptance criteria against the code

- AC1: `task-detail.html` has title, Markdown notes, list, project, contexts, energy, creativity, estimate, due date and time, scheduled date (SCHEDULED list), tickler, waiting for and since (WAITING list), and repeat presets with a validated custom RRULE and the mode. `TaskChecklist` adds, toggles, removes and reorders steps (drag and up/down). Each change is serialized while `busy`.
- AC2: `TaskDetailService.update`/`move` and the checklist update/delete send `If-Match: etag(version)`. A 412 on save sets `conflict` and starts a `conflict` load. A 412 on the checklist reloads the list and shows `CHECKLIST_CONFLICT`.
- AC3: `<app-linked-items [item]="ref()">` is in the form.
- AC4: The spec files cover draft validation (`task-draft.spec.ts`), 412 handling, recurrence presets (`recurrence.spec.ts`, `task-detail.spec.ts`), the service and the page. I ran `cd web && npx ng test --watch=false --include='src/app/features/gtd/task-detail/**/*.spec.ts'` -> 6 files, 173 tests passed. That run used the working tree, which includes the test-writer's uncommitted spec edits. fixes.md reports 171 tests for the committed revision. I did not run `npm run check` in this review.
- `python tasks.py verify web-task-detail` still shows criteria 1-4 as unrecorded. The parent worker has to record the evidence, including an `npm run check` run on the final revision, before `done`.

## Architecture, integration, security

- The task's code stays inside its own feature folder. The only line outside it is the `tasks` route in `gtd.routes.ts`, as the first assumption records. The editor uses the generated API functions and `etag` from `../data`. It does not call HTTP directly.
- Errors are silent in the service and shown next to the form. Problem Details field errors are mapped to their fields (`serverField`).
- Nothing is logged, and no unsafe HTML is rendered: the notes are a plain textarea. No secrets were found in the diff.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. R3 CR-1 is fixed and covered by regression tests, and the earlier fixes still hold. Before `done`, the parent worker has to record the evidence for AC1-AC4 with `tasks.py verify` and check the test-writer's uncommitted spec changes.
