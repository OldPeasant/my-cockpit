# Fix Report

Date: 2026-10-22

Task: web-today-habits - Today view: habits due

Scope: current task, findings in reviews/web-today-habits/review.md (CR-1, CR-2, CR-3) and reviews/web-today-habits/tests.md (TW-1, TW-2); base 1c092335a, HEAD 4145de0ae

## Fixed findings

### CR-1 / TW-1 / TW-2 - Reload on return could leave the section loading or keep a stale load error

Files changed:
- `web/src/app/features/gtd/today/today-habits.ts`
- `web/src/app/features/gtd/today/today-habits.spec.ts`

Problem:
The `visibilitychange` handler called `load(true)` whenever no check was running. If the tab came back while the first load was still running, that first response was dropped, and if the quiet reload then failed, the section stayed on "Loading habits…" with no retry (TW-1). After a failed load, a successful quiet reload set the habits but left `failed` true, so the error still showed (TW-2).

Fix:
The handler now calls a new private `refresh()`, which works like `HabitsPage.refresh()`. It does nothing while a check is running. After a failed load it runs a full `load()`, which clears `failed`. When habits are on screen it reloads quietly. While the first load is still running it does nothing.

Test change (documented): the TW-1 test "ends the loading note with a retry when a reload on return fails during the first load" expected a second list request to be sent during the first load (`expectOne` after `becomeVisible()`). The parent asked for the opposite: skip while the first load is running, as `HabitsPage.refresh()` does. So that request can no longer exist. The test's own comment accepted either outcome ("Either the first load's habits or the load error with its retry, never loading forever"). I replaced it with two tests that keep its guarantee under the specified behavior:
- "ends the loading note when the page is shown again during the first load": no second request is sent, the first load's habits show, and there is no loading note.
- "ends the loading note with a retry when the first load fails after a return": the load error with its retry shows, and there is no loading note.

The TW-2 test is unchanged and passes.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/gtd/today/*.spec.ts'` - Pass (3 files, 82 tests)

## Not fixed

### CR-2 - The overlap and conflict handling has no tests

Status:
Invalid (already resolved, no code change needed)

Reason:
The test-writer's tests in `today-habits.spec.ts` now cover this: 409/412 conflict with a quiet reload (`shows a ${status} conflict and reloads quietly...`), a conflict reload that fails, a quiet reload dropped after an overlapping check (two tests), and no reload on return while a check is running. All of them pass.

### CR-3 - Check logic copied from HabitsPage instead of shared

Status:
Out of scope

Reason:
The fix would change `web/src/app/features/habits/`, which is outside this task's touches. The parent worker accepted this finding without a change.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include='src/app/features/gtd/today/*.spec.ts'` - Pass (82 passed)

Full test suite:
- `cd web && npm run check` - Pass (lint clean, 139 files, 2856 tests passed)

Lint/type-check/build:
- `cd web && npx ng lint --max-warnings=0` - Pass (all files pass linting)
- `npx prettier --write` on both changed files - no changes needed

`.taskcheck`:
- Not run (the parent worker runs `sh scripts/check.sh`; the web part passed through `npm run check`)

Remaining unresolved findings:
0 (CR-3 accepted by the parent as out of scope)

## Final status

All validated findings fixed and validation passed.
