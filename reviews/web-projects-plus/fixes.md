# Fix Report

Date: 2026-11-08

Task: web-projects-plus - Web projects: delete project, all tasks in project detail

Scope: current task; findings CR-1, CR-2, CR-3 from reviews/web-projects-plus/review.md (base 7268744e106c4478bda5fe8a63937bba2a0a7706, HEAD e54172f83)

## Fixed findings

### CR-1 — Page flips to "Project not found" while the delete runs; focus is lost if the server refuses

Files changed:
- `web/src/app/features/gtd/projects/project-detail-page.ts`
- `web/src/app/features/gtd/projects/project-detail-delete.spec.ts`

Problem:
`ProjectStore.remove` removes the project optimistically, so `project()` became undefined while the DELETE
ran: the page showed "Project not found", the disabled menu button was never visible, and after a refusal
the focused element had been removed from the DOM.

Fix:
The page keeps a private `deleting` signal with the project being deleted; `project()` falls back to it
(same id only) while the store no longer has the project. It is set right before the request, cleared
after a failure, and cleared after the navigation to /projects settles on success. On failure the focus
goes back to the "More project actions" button (`.head button.more`) with `afterNextRender` and the
component injector, as elsewhere in the file. The shared ProjectStore is unchanged.
Tests: new "keeps the page content while the delete runs instead of "Project not found"" (heading and
task rows stay, menu button disabled) and "moves the focus back to the menu button after a refused
delete" (focus moved elsewhere during the pending request, since jsdom keeps focus on a disabled button;
after the refusal it is on the menu button). The test-writer's "does not ask or delete twice while the
delete runs" asserted the menu is hidden while pending; it now asserts the heading stays and the menu
button is disabled, which is the behaviour the review asked for. Both new tests were confirmed to fail
with the fallback or the focus call disabled.

Validation:
- `cd web && npx ng test --watch=false --include src/app/features/gtd/projects` — Pass (8 files, 120 tests)

### CR-2 — Delete uses the project version captured before the confirmation dialog

Files changed:
- `web/src/app/features/gtd/projects/project-detail-page.ts`
- `web/src/app/features/gtd/projects/project-detail-delete.spec.ts`

Problem:
The project read before the dialog opened was passed to `projects.remove`, so a project updated while the
dialog was open got a stale If-Match (412).

Fix:
After the dialog closes, `remove()` re-reads `this.projects.find(shown.id)`; if the project is gone it
stops, otherwise it deletes that current version (also re-checking the delete busy flag). The snackbar and
Undo use the current version too.
Tests: "deletes the version current when the confirmation closes (fresh If-Match)" (store refreshed to
version 7 while the dialog is open, DELETE sends If-Match with 7; fails when the stale copy is used) and
"does not delete a project that disappeared while the confirmation was open".

Validation:
- `cd web && npx ng test --watch=false --include src/app/features/gtd/projects` — Pass

### CR-3 — `autoFocus: 'dialog'` makes `cdkFocusInitial` on Cancel ineffective

Files changed:
- `web/src/app/features/gtd/projects/project-detail-page.ts`
- `web/src/app/features/gtd/projects/project-detail-delete.spec.ts`

Problem:
The dialog was opened with `autoFocus: 'dialog'`, so Material ignored `cdkFocusInitial` on Cancel, unlike
the other delete confirmations.

Fix:
Dropped `autoFocus: 'dialog'`; the default focus uses `cdkFocusInitial` on Cancel (as trash/purge-dialog).
`delete-project-dialog.ts` needed no change. Test: "opens the confirmation with the default focus (Cancel
via cdkFocusInitial)" checks no `autoFocus` override is passed.

Validation:
- `cd web && npx ng test --watch=false --include src/app/features/gtd/projects` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include src/app/features/gtd/projects` — Pass (8 files, 120 tests)
- Mutation checks: disabling the snapshot fallback, the focus call, or the re-read each made the matching new test fail; code restored afterwards.

Full test suite:
- Not run by the fixer (`npm run check` / `sh scripts/check.sh` left to the parent worker).

Lint/type-check/build:
- `cd web && npx eslint src/app/features/gtd/projects` — Pass
- `cd web && npx prettier --check src/app/features/gtd/projects` — Pass
- `cd web && npx tsc -p tsconfig.app.json --noEmit` — Pass
- No SCSS touched (style budget unaffected).

`.taskcheck`:
- Not run (parent worker runs it before `done`).

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
