# Test Report

Date: 2026-10-20

Task: p0-web-settings - Settings page: structure and general section (second pass, final code)

Scope: task commits on base b63ff7b2327ae54ac592c645bc55f34e29ec4fc9 (`git merge-base HEAD origin/main`), measured HEAD 020f4669da36e07cc82fbe4de23e4921285d985b on branch `main`. Task-owned commits: e40391d (implementation), 5d38fdf (tests, first test report), d477e1e (review report), 020f466 (fixes TW-1, CR-1..CR-4). `git diff --stat b63ff7b..HEAD` lists only p0-web-settings files (reviews, `web/src/app/app.routes.ts`, `app.spec.ts`, `features/README.md`, `features/settings/**`). Uncommitted at the time of measurement: only `web/src/app/features/settings/general/general-settings.spec.ts` (this pass's 3 new tests). Earlier passes referred to base 40301a8f / HEAD a75b722; the task was rebased since, and the task commits above are the same work on the current main.

Requirements:
- `tasks/p0-web-settings.md` acceptance criteria and the 3 recorded assumptions (sync at login via `provideSettingsSync()`, General at `/settings`, per-row auto-save with serialized saves and reload after 412)
- `reviews/p0-web-settings/fixes.md` (fixed behavior to check: stale-load guard via `version`/`inFlight`; `refresh()` keeping inputs the user edited and rows with pending saves)
- `SPEC.md` §4.2 (ETag/If-Match), §4.7 (Settings); `docs/ARCHITECTURE.md` "Coverage"; `AGENTS.md`

## Test files changed

- `web/src/app/features/settings/general/general-settings.spec.ts`

## Tests added or updated

Count: 3 (new; no existing test changed, weakened, skipped or removed)

Review of the fixed behavior against the existing tests:
- `SettingsService` stale-load guard: covered by `keeps a saved change when an older load answers after it` (TW-1, version changed), `keeps the shown change and the ETag when a load answers while the save is in flight` (inFlight > 0, also checks the next If-Match), `still takes a load that starts after the last change` (guard does not block a fresh load), and the 412 reload tests (reload runs after the rollback with no PATCH in flight, so it is applied). No gap found.
- `GeneralSettings.refresh()`: covered for another label of the same group on success and failure, a second theme click on success, and all other rows after a 412 reload. Gaps found and closed with the new tests below: a pending row after a *failed* earlier save, an input the user changes again while its own save fails, and an edited input in another row while that row reloads after a 412.

New tests:
- `keeps the second theme picked when the first one fails` - Dark then System; first PATCH 400; System stays checked; the second PATCH sends `{theme: 'system'}` with If-Match `"1"` (rolled-back state) and ends with theme `system`.
- `keeps a label the user changes again while its own save fails` - LOW sent as "Tired", user types "Exhausted" before the 400; the input keeps "Exhausted" (not rolled back to "Low"), untouched labels stay.
- `keeps a time zone being typed while another row reloads after a 412` - partial zone "Asia/Tok" typed, theme save gets 412, reload brings UTC/SUNDAY; the zone input keeps "Asia/Tok", week start shows Sunday, theme shows Light.

## Requirement coverage

Focused command (F): `cd web && npx ng test --watch=false --include 'src/app/features/settings/**/*.spec.ts'`; full command (C): `cd web && npm run check`; T: `sh scripts/check.sh`.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: child routes + placeholders for general, gtd, security, calendars, integrations, mail, backup | `opens General at /settings`, `has a placeholder for %s` (6 cases); `app.spec.ts` `lazy-loads the settings page at /settings` | F, C | Pass |
| AC2: time zone edit (IANA list) via settings API | `offers IANA time zones including UTC`, `saves a time zone from the list when the field is left`, `saves the time zone on Enter`, `offers the matching zones while typing, also with spaces for underscores`, `does not save an unknown time zone and puts the saved one back on blur` | F, C | Pass |
| AC2: week start edit | `saves the week start picked from the list` | F, C | Pass |
| AC2: theme edit, optimistic and rolled back on error | `applies a new theme at once and rolls it back when saving fails`, `shows a change at once and keeps the server answer with its ETag`, `rolls the values and the theme back when the server refuses the change`, `rolls back on a network failure as well`, `keeps the second theme picked while the first one still saves`, `keeps the second theme picked when the first one fails` | F, C | Pass |
| AC2: energy/creativity labels edit, validation, rollback | `saves a changed label group with the ETag and shows it as saved`, `puts the saved label back when saving fails`, `does not save a blank label`, `does not save when a row is left unchanged or only gains surrounding spaces`, `sends a null reset as is and shows the default from the server answer`, `keeps a label typed in while another label of the group saves`, `keeps a typed label when another label of the group fails to save`, `keeps a label the user changes again while its own save fails` | F, C | Pass |
| AC2: PATCH with If-Match, 412 handling, serialized saves (assumption 3) | `reloads the server state after a 412, so the next change sends the new ETag`, `keeps the previous values and rethrows the 412 when the reload fails too`, `runs overlapping changes one after the other, the second from the rolled-back state`, `loads the settings first when a change comes before they were loaded`, `shows the reloaded server value after a 412`, `keeps a time zone being typed while another row reloads after a 412` | F, C | Pass |
| AC2: an older load must not overwrite a saved/optimistic change (TW-1, CR-1) | `keeps a saved change when an older load answers after it`, `keeps the shown change and the ETag when a load answers while the save is in flight`, `still takes a load that starts after the last change` | F, C | Pass |
| AC2: server theme wins over the local one at login (ThemeService) | `loads the settings and applies the server theme over the local one`, `loads the settings on login, so the server theme wins over the local one`, `loads again when another user logs in after a logout`, `keeps the local theme without a toast when loading fails` | F, C | Pass |
| AC2: load failure shows error and can retry | `shows the error and retries when loading fails` | F, C | Pass |
| AC3: Vitest component/service tests cover general save and rollback on error | all of the above | F | Pass |
| AC3: `cd web && npm run check` passes | whole web suite | C | Pass |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include 'src/app/features/settings/**/*.spec.ts'`

Result:

Pass: 3 files, 42 tests passed (39 before this pass plus 3 new).

## Full test execution

Command:

`cd web && npm run check`

Result:

Pass (exit 0): `api:check` ok, `ng lint` "All files pass linting.", `e2e:typecheck` ok, `ng test` 22 files, 317 tests passed, `ng build` complete. `npx prettier --check src/app/features/settings`: all files formatted.

## .taskcheck

Result:

Pass: `sh scripts/check.sh` ran "tfp: completion gate tests", "server: ktlint + tests", "web: eslint + tests + build", "android: ktlint + unit tests + lint" and ended with "== all checks passed".

## Bugs found

None in this pass.

Earlier finding status:
- TW-1 (stale settings load overwrites a saved change, values/theme/ETag): fixed in 020f466; the original failing test `SettingsService > keeps a saved change when an older load answers after it` is unchanged and passes. Status: resolved.

## Coverage

Status: verified. Fresh `npm run coverage` on HEAD 020f466 plus this pass's test file, exit 0, all 317 tests passed.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: 150/150 (100%) executable lines, measured by intersecting lcov `DA` records of this run with the added lines of `git diff -U0 b63ff7b..HEAD` per file (removed and non-executable lines excluded; the changed lines of `app.routes.ts` have no `DA` records of their own, so it contributes 0/0). Changed-branch coverage not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: p0-web-settings; base b63ff7b2327ae54ac592c645bc55f34e29ec4fc9 (`git merge-base HEAD origin/main`, as named by the parent); HEAD 020f4669da36e07cc82fbe4de23e4921285d985b; task commits e40391d, 5d38fdf, d477e1e, 020f466; file scope from `git diff --stat b63ff7b..HEAD` (only task files).
- Source/test snapshot identifier (including uncommitted inputs): production files = HEAD 020f466 (unmodified); uncommitted `web/src/app/features/settings/general/general-settings.spec.ts` sha256 c2214acef63bf9a7270456322a2f87a18f32cb33b53498050921fef9b9de4b19 (same before and after the runs; `git status` showed only this file modified).
- Exact included files: `web/src/app/app.routes.ts`, `web/src/app/features/settings/general/general-settings.ts`, `settings-layout.ts`, `settings-sections.ts`, `settings.providers.ts`, `settings.routes.ts`, `settings.service.ts`. Excluded: `general/general-settings.html` and `.scss` (template/style, not instrumented by the provider; behavior exercised by the component tests), `features/settings/testing.ts` (test helper), `*.spec.ts`, `app.spec.ts` (tests), `features/README.md` (docs). No renamed or deleted files. All 7 scoped files present in the report (none missing).
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + @vitest/coverage-v8 (Angular `ng test` builder), node v24.21.0; in `web/`: `npm run coverage` (`ng test --watch=false --coverage` with text-summary, json-summary, json, lcov reporters); full suite, no filter.
- Run start/completion time and exit status: 2026-10-20T12:52:39Z - 12:52:46Z, exit 0 (317 passed).
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 7aa446bb4165ff26ea7b6e3132340a24bf2c6012df3b7ae13ae7f59f0dab81ba, `coverage-summary.json` 3060e1280e81b209d4e90229a46575bd7d69e6db581c48d45cb1adf5dad7ed40, `lcov.info` ba8c4bfecf51163ce1692c36701c53e849c8204f2b131ac4379ae859036e2344.
- Evidence outputs are fresh and inputs stayed unchanged: yes; report files timestamped 14:52:46 +0200 (inside the run window, the provider cleans its output directory), hashes differ from the previous pass, test-file hash verified after the run.
- Final run after latest test additions and production fixes: yes (after the 020f466 fixes and this pass's 3 tests).

Values below use covered/total (percentage); zero-denominator metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/app.routes.ts` | 18/18 (100%) | N/A (0/0) | 17/17 (100%) | 18/18 (100%) |
| `web/src/app/features/settings/general/general-settings.ts` | 107/107 (100%) | 54/59 (91.53%) | 26/26 (100%) | 85/85 (100%) |
| `web/src/app/features/settings/settings-layout.ts` | 23/23 (100%) | 10/11 (90.91%) | 2/2 (100%) | 13/13 (100%) |
| `web/src/app/features/settings/settings-sections.ts` | 1/1 (100%) | N/A (0/0) | N/A (0/0) | 1/1 (100%) |
| `web/src/app/features/settings/settings.providers.ts` | 10/10 (100%) | 2/2 (100%) | 5/5 (100%) | 8/8 (100%) |
| `web/src/app/features/settings/settings.routes.ts` | 4/4 (100%) | N/A (0/0) | 3/3 (100%) | 4/4 (100%) |
| `web/src/app/features/settings/settings.service.ts` | 49/49 (100%) | 19/20 (95%) | 10/10 (100%) | 40/40 (100%) |
| Aggregate (summed counters, 7 files; html/scss and testing.ts excluded as above, none missing) | 212/212 (100%) | 85/92 (92.39%) | 63/63 (100%) | 169/169 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `general-settings.ts:145` branch 1, `:146` branch 1 | `zoneOptions` fallback to the full list for an empty query or a query without matches | Not added: display convenience, no requirement; the match path is covered by `offers the matching zones while typing...` |
| `general-settings.ts:183` branch 1 (`?? 1`) | pending counter missing when a save answers | Justified gap: defensive, unreachable (the counter is always set before the await) |
| `general-settings.ts:193` branch 1 (`if (saved)` false) | `settings()` is `null` on a time zone blur with an invalid value | Justified gap: defensive, the form only shows after a successful load and the service never resets to null |
| `general-settings.ts:72` branch 0, `settings-layout.ts:116` branch 0 | class declaration lines | Provider artifact of the decorated class, no behavior |
| `settings.service.ts:59` (branch counter `[13, -12]`) | `if (patch.theme)` implicit else | Provider artifact (negative v8-to-istanbul counter); patches without `theme` are exercised by several tests (`weekStart`, `timeZone`, labels) |

## Unable to test

- Real browser behavior of the autocomplete (mouse selection followed by blur ordering), real focus/blur when clicking another row, and the responsive layout: only jsdom was used; no Playwright criterion in this task. No follow-up required by the criteria.
- The `general-settings.html`/`.scss` files are not instrumented by the coverage provider; their behavior is covered through the component tests.

## Summary

Tests added and all validation passed. The TW-1/CR-1..CR-4 fixes are covered (the stale-load guard by 3 service tests, `refresh()` by the component tests listed above, 3 of them added in this pass); no production defects found in this pass; focused specs, `npm run check`, `.taskcheck` and a fresh task-scoped `npm run coverage` all pass.
