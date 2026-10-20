# Fix Report

Date: 2026-10-20

Task: p0-web-settings - Settings page: structure and general section

Scope: the current task. Findings TW-1 (`reviews/p0-web-settings/tests.md`) and CR-1..CR-4 (`reviews/p0-web-settings/review.md`), all approved by the parent worker. Base 40301a8f4aee5144bf58cc316d75f43698e73517, reviewed HEAD 5e3573ae266678820b5cb366ef9770a2e2a783d5 plus the report commit ddd0c3e. The fixes are uncommitted changes in the working tree on `main`.

## Fixed findings

### CR-1 / TW-1 - An older settings load overwrote a saved change (values, theme, ETag)

Files changed:
- `web/src/app/features/settings/settings.service.ts`
- `web/src/app/features/settings/settings.service.spec.ts`

Problem:
`load()` always accepted its GET answer. If a GET started before a change (an optimistic update or an accepted answer) and answered after it, it brought back the old values, the old theme and the old ETag, so the next save failed with a 412. A GET that answered while a PATCH was in flight replaced the optimistic value in the same way.

Fix:
The service now counts every change of its state (`version`, increased in a new private `show()` that `accept()`, the optimistic update and the rollback use) and the PATCHes in flight (`inFlight`). `load()` records the version when it starts. When it answers after a newer state is shown, or while a PATCH is in flight, it returns the current state and leaves the values, theme and ETag alone. The 412 reload in `save()` starts after the rollback, with no PATCH in flight, so it is still applied. Sharing one in-flight GET between callers was not added: the sequence check alone fixes the bug, and the TW-1 test expects two separate GETs. The TW-1 test is unchanged and now passes. New tests: `keeps the shown change and the ETag when a load answers while the save is in flight` (it also checks that the next PATCH sends the ETag from the PATCH answer) and `still takes a load that starts after the last change`.

Validation:
- `cd web && npx ng test --watch=false --include 'src/app/features/settings/**/*.spec.ts'` - Pass (3 files, 39 tests)

### CR-2 - A save answer reset the whole label group and undid a second theme click

Files changed:
- `web/src/app/features/settings/general/general-settings.ts`
- `web/src/app/features/settings/general/general-settings.spec.ts`

Problem:
After any save answered, `showField()` reset the whole row's control, including a whole label group. That wiped a label the user was typing in another input of the same group, and made a quickly re-clicked theme toggle jump back to the first choice.

Fix:
The component keeps a baseline for each input (`timeZone`, `energyLabels.LOW`, ...): the value the input got from the server, or the value it had when it was sent. It also counts the unanswered saves for each row. When a save answers, success or failure, `refresh()` writes the service state only into inputs whose value still equals their baseline, and skips rows that still have a save pending. So a label typed into the group meanwhile stays, and the second theme click wins. On a failure the sent inputs that are unchanged since go back to the rolled-back values. Loading and the time zone blur restore still fill the inputs directly. New tests: `keeps a label typed in while another label of the group saves`, `keeps a typed label when another label of the group fails to save`, `keeps the second theme picked while the first one still saves`.

Validation:
- `cd web && npx ng test --watch=false --include 'src/app/features/settings/**/*.spec.ts'` - Pass

### CR-3 - After a 412 only the failed row showed the reloaded state

Files changed:
- `web/src/app/features/settings/general/general-settings.ts`
- `web/src/app/features/settings/general/general-settings.spec.ts`

Problem:
After a failed save, in particular after the reload that follows a 412, only the failed row was refreshed. Other rows kept stale values while the service and the theme already held the reloaded state.

Fix:
This uses the same `refresh()` as CR-2. After every save answer, every row the user is not editing and that has no save pending is filled from `service.settings()`, which holds the reloaded state after a 412. The existing test `shows the reloaded server value after a 412` now reloads a different time zone, week start and energy label as well, and checks that those rows show them.

Validation:
- `cd web && npx ng test --watch=false --include 'src/app/features/settings/**/*.spec.ts'` - Pass

### CR-4 - The feature README did not list the new shell provider

Files changed:
- `web/src/app/features/README.md`

Problem:
The table "Not routed, but provided to the shell" had no row for `provideSettingsSync()`.

Fix:
Added the row `settings/ | settings.providers.ts -> provideSettingsSync() | loads GET /settings on every login and applies the server theme`. Prettier realigned the columns of the existing table rows, but their content did not change. The file is outside the task's `touches`. The parent approved this one-line exception.

Validation:
- `cd web && npx prettier --write src/app/features/settings src/app/features/README.md` - Pass
- `cd web && npm run check` (lint covers formatting) - Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include 'src/app/features/settings/**/*.spec.ts'` - Pass: 3 files, 39 tests passed (34 before plus 5 new; 1 existing test extended; no test weakened, skipped or removed)

Full test suite:
- `cd web && npm run check` - Pass: api:check ok, `ng lint` "All files pass linting.", `ng test` 22 files, 314 tests passed, `ng build` complete

Lint/type-check/build:
- Covered by `npm run check` above (lint, TypeScript build) - Pass

`.taskcheck`:
- Pass: `sh scripts/check.sh` ended with "== all checks passed" (tfp gate tests, server ktlint + tests, web, android ktlint + unit tests + lint)

Coverage was not re-measured by the fixer. The test-writer's coverage numbers in `tests.md` were diagnostic. A fresh `npm run coverage` belongs to the test-writer's re-run on the final code.

I did not run the new tests against the original production code: a temporary file swap was not approved in this session. The tests' pass/fail logic was checked by reading the code.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
