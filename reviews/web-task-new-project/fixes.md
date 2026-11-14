# Fix Report

Date: 2026-11-14

Task: web-task-new-project - Web task detail: create a new project from the project picker

Scope: current task; finding CR-1 from `reviews/web-task-new-project/review.md`

## Fixed findings

### CR-1 — A retry silently assigns the previously created project even when a different title is entered

Files changed:
- `web/src/app/features/gtd/task-detail/task-detail.ts`
- `web/src/app/features/gtd/task-detail/task-detail.spec.ts`

Problem:
`createProject()` reused the remembered `createdProject` for the task whatever title was entered, so after a failed save (for example a 412 conflict reload or Discard) a retry with a corrected title assigned the earlier project and never created the one asked for.

Fix:
- `createdProject` now also remembers the trimmed title it was created with. `createProject()` reuses the remembered project only when the task id and the trimmed entered title both match; otherwise it creates a new project and replaces the remembered entry with it.
- `discard()` forgets the remembered project, since Discard drops the form back to the saved task.
- The conflict reload deliberately keeps the remembered entry: with the title check, a retry after a conflict with the same title reuses the project (no duplicate, criterion 2), and a different title creates the new one. Forgetting it on conflict would create a second project with the same title on the most common retry path.
- Doc comments of the field and of `createProject()` updated.
- New spec cases in the 'New project from the project picker' describe:
  - 'after a failed save, a retry with a different title creates that project' (412 conflict, then " Garden ": a second project is created, the task is saved with it and the reloaded version's If-Match);
  - 'after a conflict, a retry with the same title reuses the created project' (412 conflict, then "  Garage ": no second create, the task gets p9);
  - 'Discard after a failed save forgets the created project' (500 on save, Discard, then the same title creates again).
  The existing case 'a failed save keeps the new project chosen; a retry does not create another' still covers same-title reuse after a 500.
- Mutation check: with the title comparison and the Discard reset temporarily removed, the different-title and Discard cases failed (2 failed, 164 passed); with the fix restored all pass.

Validation:
- `cd web && npx ng test --watch=false --include src/app/features/gtd/task-detail/task-detail.spec.ts` — Pass (166 passed)
- `npx prettier --check` on both changed files — Pass
- `npx eslint` on both changed files — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `npx ng test --watch=false --include src/app/features/gtd/task-detail/task-detail.spec.ts` — 166 passed

Full test suite:
- Not run (parent worker runs `npm run check` and `sh scripts/check.sh`; Gradle excluded on instruction)

Lint/type-check/build:
- `npx prettier --check` and `npx eslint` on the changed files — Pass; the test build compiled the changed TypeScript without errors

`.taskcheck`:
- Not run (parent worker runs it; it includes Gradle, which this fixer was told not to run)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
