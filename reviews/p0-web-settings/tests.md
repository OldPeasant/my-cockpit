# Test Report

Date: 2026-10-20

Task: p0-web-settings - Settings page: structure and general section

Scope: task commit a75b722117c946abe0e7f84860281204fbb3a59b on base 40301a8f4aee5144bf58cc316d75f43698e73517 (`git diff 40301a8f..a75b722 -- web`). The parent named 6ab3f1f; that is the pre-rebase copy of the same commit (`git diff 6ab3f1f a75b722` only differs in `tasks/p0-web-settings.md`, the web/ content is identical). Branch `main`, HEAD a75b722, uncommitted changes are only the three test files listed below.

Requirements:
- `tasks/p0-web-settings.md` acceptance criteria and the 3 recorded assumptions (sync at login via `provideSettingsSync()`, General at `/settings`, per-row auto-save with serialized saves and reload after 412)
- `SPEC.md` §4.2 (ETag/If-Match), §4.7 (Settings)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`

## Test files changed

- `web/src/app/features/settings/settings.service.spec.ts`
- `web/src/app/features/settings/general/general-settings.spec.ts`
- `web/src/app/features/settings/settings.routes.spec.ts`

## Tests added or updated

Count: 15 (13 new tests; 2 existing tests strengthened, none weakened: "reloads the server state after a 412, so the next change sends the new ETag" now checks that the next PATCH sends the reloaded ETag, and "shows a change at once and keeps the server answer with its ETag" also checks the theme after the server answer)

New tests:
- service: `keeps the previous values and rethrows the 412 when the reload fails too`, `rolls back on a network failure as well`, `loads the settings first when a change comes before they were loaded`, `sends a null reset as is and shows the default from the server answer`, `runs overlapping changes one after the other, the second from the rolled-back state`, `keeps a saved change when an older load answers after it` (FAILS, see TW-1)
- component: `saves a time zone from the list when the field is left`, `saves the time zone on Enter`, `offers the matching zones while typing, also with spaces for underscores`, `does not save when a row is left unchanged or only gains surrounding spaces`, `saves the week start picked from the list`, `shows the reloaded server value after a 412`
- sync provider: `loads again when another user logs in after a logout`

## Requirement coverage

Focused command (F): `cd web && npx ng test --watch=false --include 'src/app/features/settings/**/*.spec.ts'`; full command (C): `cd web && npm run check`.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: child routes + placeholders for general, gtd, security, calendars, integrations, mail, backup | `opens General at /settings`, `has a placeholder for %s` (6 cases); `app.spec.ts` `lazy-loads the settings page at /settings` | F, C | Pass |
| AC2: time zone edit (IANA list) via settings API | `offers IANA time zones including UTC`, `saves a time zone from the list when the field is left`, `saves the time zone on Enter`, `offers the matching zones while typing, also with spaces for underscores`, `does not save an unknown time zone and puts the saved one back on blur` | F, C | Pass |
| AC2: week start edit | `saves the week start picked from the list` | F, C | Pass |
| AC2: theme edit, applied at once (optimistic) and rolled back on error | `applies a new theme at once and rolls it back when saving fails`, `shows a change at once and keeps the server answer with its ETag`, `rolls the values and the theme back when the server refuses the change`, `rolls back on a network failure as well` | F, C | Pass |
| AC2: energy/creativity labels edit, validation, rollback | `saves a changed label group with the ETag and shows it as saved`, `puts the saved label back when saving fails`, `does not save a blank label`, `does not save when a row is left unchanged or only gains surrounding spaces`, `sends a null reset as is and shows the default from the server answer` | F, C | Pass |
| AC2: PATCH with If-Match, 412 handling, serialized saves (assumption 3) | `reloads the server state after a 412, so the next change sends the new ETag`, `keeps the previous values and rethrows the 412 when the reload fails too`, `runs overlapping changes one after the other, the second from the rolled-back state`, `loads the settings first when a change comes before they were loaded`, `shows the reloaded server value after a 412` | F, C | Pass |
| AC2: optimistic state must not be overwritten by an older load answering after a save | `keeps a saved change when an older load answers after it` | F, C | Fail (TW-1) |
| AC2: server theme wins over the local one at login (ThemeService) | `loads the settings and applies the server theme over the local one`, `loads the settings on login, so the server theme wins over the local one`, `loads again when another user logs in after a logout`, `keeps the local theme without a toast when loading fails` | F, C | Pass |
| AC2: load failure shows error and can retry | `shows the error and retries when loading fails` | F, C | Pass |
| AC3: Vitest component/service tests cover general save and rollback on error | all of the above | F | Pass (the save/rollback tests themselves pass) |
| AC3: `cd web && npm run check` passes | whole web suite | C | Fail: 1 failed / 308 passed, caused by TW-1 test only (api:check and lint passed; `ng build` alone: pass) |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include 'src/app/features/settings/**/*.spec.ts'`

Result:

Fail: 3 files, 34 tests, 33 passed, 1 failed (`SettingsService > keeps a saved change when an older load answers after it`, TW-1). Before adding tests: 21/21 passed.

## Full test execution

Command:

`cd web && npm run check`

Result:

Fail (exit 1): `api:check` pass, `ng lint --max-warnings=0` "All files pass linting.", `ng test --watch=false` 22 files, 309 tests: 308 passed, 1 failed (TW-1). `ng build` did not run because the chain stopped; run separately (`cd web && npx ng build`): exit 0.

## .taskcheck

Result:

Fail (`sh scripts/check.sh`, exit 1): "tfp: completion gate tests" and "server: ktlint + tests" passed; "web" failed on the TW-1 test only; android step not reached.

## Bugs found

### A stale settings load that answers after a save overwrites the saved values, theme and ETag

Finding ID:

`TW-1`

Test:

`SettingsService > keeps a saved change when an older load answers after it` (`web/src/app/features/settings/settings.service.spec.ts:218`)

Production source:

`web/src/app/features/settings/settings.service.ts:28-33` (`load()` unconditionally calls `accept()` and `theme.setMode()`), interacting with `settings.providers.ts:26` (login sync load) and `general/general-settings.ts:151` (page load)

Requirement:

AC2: optimistic update + error rollback via the settings API; SPEC.md §4.2 optimistic locking via ETag/If-Match. At login (or a direct open of /settings) two GETs run at once: the login sync and the settings page. If the page's GET answers first, the user saves a change (PATCH OK, ETag "2"), and then the login GET answers.

Expected behavior:

The saved change (e.g. theme `dark`) stays in `settings()`, the theme stays applied, and the ETag stays the one the PATCH returned.

Actual behavior:

The older GET answer replaces `settings()` with the pre-save values (`theme` back to `light`, received 'light' expected 'dark'), re-applies the old theme to the page, and stores the old ETag "1"; the next save then fails with 412 and shows an error toast plus a rollback. The data on the server is not lost; the screen and theme are wrong until the next reload. Possible fix (for the fixer to decide): ignore a load answer that was started before the latest accepted response (e.g. a sequence counter) or share one in-flight load.

Severity:

Low (needs a reversed response order between two concurrent GETs; self-heals after the 412 reload)

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Coverage

Status: partial-failed. The project command `npm run coverage` exited 1 because of the TW-1 test and wrote no coverage (Vitest does not report on failure; `web/coverage/web/` did not exist after the run). The numbers below come from a fresh diagnostic run of the same command with only the TW-1 test filtered out (309 tests: 308 passed, 1 skipped by the filter). They are diagnostic, not verified; a fresh `npm run coverage` is needed after TW-1 is fixed.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: 126/126 (100%) executable lines, measured by intersecting lcov `DA` records of the diagnostic run with the added lines of `git diff -U0 40301a8f..a75b722` per file (removed and non-executable lines excluded). Changed-branch coverage not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: p0-web-settings; base 40301a8f4aee5144bf58cc316d75f43698e73517 (from the parent); HEAD a75b722117c946abe0e7f84860281204fbb3a59b; single task commit a75b722 (= 6ab3f1f before rebase), scope from `git diff --name-only 40301a8f..a75b722 -- web/src`.
- Source/test snapshot identifier (including uncommitted inputs): production files = HEAD a75b722 (unmodified); uncommitted test files sha256: `settings.routes.spec.ts` 1525b63c611a95945ff4cde9c2abe211c577129a123ce74f0b636d45e0a905ef, `settings.service.spec.ts` 486c48d2bd10fccc4f6445d62f8f42ccb5ee26448d87eb18f2e055138e7b5e93, `general/general-settings.spec.ts` 4402aa451381044abf4080148520d9e13f92184f31ccb758566cc13cb35137a3 (re-checked unchanged after the run).
- Exact included files: `web/src/app/app.routes.ts`, `web/src/app/features/settings/general/general-settings.ts`, `settings-layout.ts`, `settings-sections.ts`, `settings.providers.ts`, `settings.routes.ts`, `settings.service.ts`. Excluded: `general/general-settings.html` and `.scss` (template/style, not instrumented by the provider; template behavior is exercised by the component tests), `features/settings/testing.ts` (test helper), `*.spec.ts`, `app.spec.ts` (tests). No renamed or deleted files. All 7 scoped files were present in the report (none missing).
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + @vitest/coverage-v8 5.0.5 (Angular `ng test` builder), node v24.21.0; in `web/`: `npm run coverage` (exit 1, no report), then diagnostic `npx ng test --watch=false --coverage --coverage-reporters=text-summary --coverage-reporters=json-summary --coverage-reporters=json --coverage-reporters=lcov --filter '^(?!.*keeps a saved change when an older load answers after it)'` (full suite minus the TW-1 test).
- Run start/completion time and exit status: `npm run coverage` 2026-10-20T12:40:36Z-12:40:49Z exit 1; diagnostic run 2026-10-20T12:40:59Z-12:41:12Z exit 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 cdd6a7d96c7f8828441a41b96d3c58a8be279747fc792adfad413420c92fa530, `coverage-summary.json` 6fed2133d3ab19ca63c38ab6970a32a220e4db23db2fdd60491e75f4c985d55a, `lcov.info` 140b755d6e7dcc53b329de18b5f57cae373b8e683f731b74996392cb8535a0ae.
- Evidence outputs are fresh and inputs stayed unchanged: yes; the coverage directory did not exist after the failed run and was written by the diagnostic run (files timestamped 14:41:12 +0200); test-file hashes verified before and after.
- Final run after latest test additions and production fixes: yes for the latest test additions; no production fix yet (TW-1 open), so a fresh run is needed after the fix.

Values below use covered/total (percentage); zero-denominator metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/app.routes.ts` | 18/18 (100%) | N/A (0/0) | 17/17 (100%) | 18/18 (100%) |
| `web/src/app/features/settings/general/general-settings.ts` | 86/86 (100%) | 44/49 (89.80%) | 21/21 (100%) | 71/71 (100%) |
| `web/src/app/features/settings/settings-layout.ts` | 23/23 (100%) | 10/11 (90.91%) | 2/2 (100%) | 13/13 (100%) |
| `web/src/app/features/settings/settings-sections.ts` | 1/1 (100%) | N/A (0/0) | N/A (0/0) | 1/1 (100%) |
| `web/src/app/features/settings/settings.providers.ts` | 10/10 (100%) | 2/2 (100%) | 5/5 (100%) | 8/8 (100%) |
| `web/src/app/features/settings/settings.routes.ts` | 4/4 (100%) | N/A (0/0) | 3/3 (100%) | 4/4 (100%) |
| `web/src/app/features/settings/settings.service.ts` | 38/38 (100%) | 14/15 (93.33%) | 9/9 (100%) | 30/30 (100%) |
| Aggregate (summed counters, 7 files; html/scss and testing.ts excluded as above) | 180/180 (100%) | 70/77 (90.91%) | 57/57 (100%) | 145/145 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `general-settings.ts:139` branch 1, `:140` branch 1 | `zoneOptions` fallback to the full list for an empty query or a query without matches | Not added: display convenience, no requirement; the match path is covered by `offers the matching zones while typing...` |
| `general-settings.ts:176` branch 1, `:185` branch 1 | `settings()` is `null` after a failed save / on blur | Justified gap: defensive, unreachable once the form is shown (it only shows after a successful load, and the service never resets to null) |
| `general-settings.ts:71` branch 0, `settings-layout.ts:116` branch 0 | class declaration lines | Provider artifact of the decorated class, no behavior |
| `settings.service.ts:50` (branch counter `[6, -5]`) | `if (patch.theme)` implicit else | Provider artifact (negative v8-to-istanbul counter); patches without `theme` are exercised by several tests (`weekStart`, `timeZone`, labels) |

## Unable to test

- Real browser behavior of the autocomplete (mouse selection of an option followed by blur ordering) and the responsive layout: only jsdom was used; no Playwright criterion in this task. No follow-up required by the criteria.
- The `general-settings.html`/`.scss` files are not instrumented by the coverage provider; their behavior is covered through the component tests.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion (TW-1, Low: a stale concurrent settings load overwrites a saved change; the failing test keeps `npm run check` and `.taskcheck` red until fixed). All other acceptance-criteria tests pass; coverage figures are diagnostic (partial-failed) and need a fresh `npm run coverage` after the fix.
