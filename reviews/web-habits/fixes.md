# Fix Report

Date: 2026-10-22

Task: web-habits - Habits UI

Scope: current task, limited by the parent to `web/src/app/features/habits/`. This is round 2. Its findings come from the second review (`reviews/web-habits/review.md`, CR-1 and CR-2) and from `reviews/web-habits/tests.md` (TW-2). The base is HEAD 90397e7a on main. The changes below are left uncommitted.

Round 1 (commit b9162130), for reference: CR-1/TW-1 made the conflict reload sequenced (`reloadQuietly()` with a `loadSeq` guard). CR-3 added the reload on `visibilitychange`. CR-2 moved the streak plurals to ICU. CR-4 was invalid because the specs already covered it. All passed in that round.

## Fixed findings

### CR-1 / TW-2 - A quiet reload that answers late undoes local changes

Files changed:
- `web/src/app/features/habits/habits-page.ts`

Problem:
`reloadQuietly()` dropped its result only when a `load()` had started in the meantime. A check, archive/unarchive, create or edit could start or finish while the reload was still pending. The reload's older list then overwrote that change on screen.

Fix:
A new counter, `changeSeq`, goes up when a change in `run()` or `save()` starts and again when it finishes (on success or on failure). `reloadQuietly()` records the counter when it starts. It applies its result only if `loadSeq` and `changeSeq` are both unchanged. The test-writer's spec `keeps a check made while the reload on becoming visible is still pending` is unchanged and now passes.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/habits/**/*.spec.ts'` - Pass (4 files, 67 tests)

### CR-2 - A check/archive conflict cleared another habit's open edit form

Files changed:
- `web/src/app/features/habits/habits-page.ts`
- `web/src/app/features/habits/habits-page.spec.ts`

Problem:
After a 409/412 on a check or archive, `run()` called `load()`. `load()` set the list to null, so an open edit form on another habit was rebuilt and lost what the user had typed.

Fix:
`run()` now calls `reloadQuietly()` on a conflict, the same way the edit-conflict path does. In `run()` and `save()`, the "finished" increment of `changeSeq` happens in `catch` before the conflict reload starts. This way the failed change does not drop its own reload. The row error is set before the reload and stays visible.

Spec changes:
- New spec `keeps the typed values of an open edit form when a check on another habit gets a 412`. It checks that:
  - the typed name in A's form survives;
  - B's row error stays;
  - B's reloaded count is shown;
  - the next save of A sends the reloaded version (`If-Match: "4"`).
- The existing spec `reloads the habits after a check conflict and keeps the error on the row` used to expect the "Loading habits…" flash, which this fix removes on purpose. It now expects the list to stay on screen with the row error. Its other assertions are unchanged: the reloaded state is applied and the error stays.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/habits/**/*.spec.ts'` - Pass (4 files, 67 tests)

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include='src/app/features/habits/**/*.spec.ts'` - Pass (4 files, 67 tests)
- `cd web && npx eslint --max-warnings=0 src/app/features/habits` - Pass
- `cd web && npx prettier --check src/app/features/habits` - Pass

Full test suite:
- `cd web && npm run check` - Pass. The script chains api:check, lint, e2e:typecheck, test:ci and `ng build` with `&&`. The build ran to completion, so every earlier step passed. The build showed the existing initial-bundle budget warning. This change does not affect it, because habits is a lazy chunk.

Lint/type-check/build:
- Included in `npm run check` - Pass

`.taskcheck`:
- Not run. The parent worker must run `sh scripts/check.sh`.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
