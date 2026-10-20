# Test Report

Date: 2026-10-20

Task: p0-web-settings - Settings page: structure and general section (third pass, final code after the CR-1 fix of the second review)

Scope: task commits on base 7c425ce2600f4bac91c024c541bc4ba2dd144cf8 (`git merge-base HEAD origin/main`, as named by the parent), measured HEAD 0df301561a14df382a604353110cdce650360a9d on branch `main`. Task-owned commits (`git log 7c425ce..HEAD`, all `p0-web-settings:`): d21fdf4 (implementation), 6a996a1 (tests, report), 4789fb9 (review report), ccbf783 (fixes TW-1, CR-1..4), 9055321 (test report), 9bbf846 (second review report), 0df3015 (fix of the second review's CR-1: `save()` compares with the last value sent for a row while saves of the row are pending). `git diff --name-only 7c425ce..HEAD` lists only p0-web-settings files. Uncommitted at measurement: `web/src/app/features/settings/general/general-settings.spec.ts` (this pass's 2 new tests) and `reviews/p0-web-settings/review.md` (changed by the code-reviewer, not by this pass; not a measured input).

Requirements:
- `tasks/p0-web-settings.md` acceptance criteria and the 3 recorded assumptions (sync at login via `provideSettingsSync()`, General at `/settings`, per-row auto-save with serialized saves and reload after 412)
- `reviews/p0-web-settings/fixes.md` (fixed behavior to check: the `lastSent` comparison in `GeneralSettings.save()`)
- `SPEC.md` §4.2 (ETag/If-Match), §4.7 (Settings); `docs/ARCHITECTURE.md` "Coverage"; `AGENTS.md`

## Test files changed

- `web/src/app/features/settings/general/general-settings.spec.ts`

## Tests added or updated

Count: 2 (new; no existing test changed, weakened, skipped or removed)

Review of the 0df3015 fix against the tests:
- The fix makes `save()` compare the row value with `lastSent` while `pending` > 0, else with the saved settings. 0df3015 added 2 tests for the case it fixes (a label and a theme changed back to their in-flight value while a newer save is queued: the third PATCH is sent). Both pass.
- Gaps found and closed with the new tests: (a) the comparison must still suppress a duplicate PATCH when the value equals the in-flight one (the `sameValue(value, lastSent)` true path while pending); (b) changing a row back to its original saved value while its save is in flight must still send a PATCH (the in-flight value, not the stale saved one, is what is compared).
- Not a defect: after a failed save `lastSent` keeps the refused value, but it is only read while `pending` > 0 and is overwritten by every new save, so a stale value is never compared.

New tests:
- `does not send a label again that only gains spaces while its save is in flight` - LOW sent as "Tired", then changed to "  Tired " before the answer: no second PATCH, row shows "Saved".
- `saves the original label when it is changed back while its save is in flight` - LOW sent as "Tired", changed back to "Low" before the answer: a second PATCH with `LOW: 'Low'` and If-Match `"2"`, the input ends at "Low".

## Requirement coverage

Focused command (F): `cd web && npx ng test --watch=false --include 'src/app/features/settings/**/*.spec.ts'`; full command (C): `cd web && npm run check`; T: `sh scripts/check.sh`.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: child routes + placeholders for general, gtd, security, calendars, integrations, mail, backup | `opens General at /settings`, `has a placeholder for %s` (6 cases); `app.spec.ts` `lazy-loads the settings page at /settings` | F, C | Pass |
| AC2: time zone edit (IANA list) via settings API | `offers IANA time zones including UTC`, `saves a time zone from the list when the field is left`, `saves the time zone on Enter`, `offers the matching zones while typing, also with spaces for underscores`, `does not save an unknown time zone and puts the saved one back on blur` | F, C | Pass |
| AC2: week start edit | `saves the week start picked from the list` | F, C | Pass |
| AC2: theme edit, optimistic and rolled back on error | `applies a new theme at once and rolls it back when saving fails`, `shows a change at once and keeps the server answer with its ETag`, `rolls the values and the theme back when the server refuses the change`, `rolls back on a network failure as well`, `keeps the second theme picked while the first one still saves`, `keeps the second theme picked when the first one fails`, `saves a theme picked back while a newer theme save is queued` | F, C | Pass |
| AC2: energy/creativity labels edit, validation, rollback | `saves a changed label group with the ETag and shows it as saved`, `puts the saved label back when saving fails`, `does not save a blank label`, `does not save when a row is left unchanged or only gains surrounding spaces`, `sends a null reset as is and shows the default from the server answer`, `keeps a label typed in while another label of the group saves`, `keeps a typed label when another label of the group fails to save`, `keeps a label the user changes again while its own save fails` | F, C | Pass |
| AC2: overlapping saves of one row compare with the value last sent (CR-1 of the second review, fixed in 0df3015) | `saves a label changed back to its in-flight value while a newer save is queued`, `saves a theme picked back while a newer theme save is queued`, `does not send a label again that only gains spaces while its save is in flight` (new), `saves the original label when it is changed back while its save is in flight` (new) | F, C | Pass |
| AC2: PATCH with If-Match, 412 handling, serialized saves (assumption 3) | `reloads the server state after a 412, so the next change sends the new ETag`, `keeps the previous values and rethrows the 412 when the reload fails too`, `runs overlapping changes one after the other, the second from the rolled-back state`, `loads the settings first when a change comes before they were loaded`, `shows the reloaded server value after a 412`, `keeps a time zone being typed while another row reloads after a 412` | F, C | Pass |
| AC2: an older load must not overwrite a saved/optimistic change (TW-1) | `keeps a saved change when an older load answers after it`, `keeps the shown change and the ETag when a load answers while the save is in flight`, `still takes a load that starts after the last change` | F, C | Pass |
| AC2: server theme wins over the local one at login (ThemeService) | `loads the settings and applies the server theme over the local one`, `loads the settings on login, so the server theme wins over the local one`, `loads again when another user logs in after a logout`, `keeps the local theme without a toast when loading fails` | F, C | Pass |
| AC2: load failure shows error and can retry | `shows the error and retries when loading fails` | F, C | Pass |
| AC3: Vitest component/service tests cover general save and rollback on error | all of the above | F | Pass |
| AC3: `cd web && npm run check` passes | whole web suite | C | Pass |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include 'src/app/features/settings/**/*.spec.ts'`

Result:

Pass: 3 files, 46 tests passed (44 at HEAD plus 2 new).

## Full test execution

Command:

`cd web && npm run check`

Result:

Pass: `api:check` ok, `ng lint --max-warnings=0` "All files pass linting.", `e2e:typecheck` ok, `ng test` 22 files, 321 tests passed, `ng build` "Application bundle generation complete". `npx prettier --check src/app/features/settings`: "All matched files use Prettier code style!".

## .taskcheck

Result:

Pass: `sh scripts/check.sh` ran "tfp: completion gate tests", "server: ktlint + tests", "web: eslint + tests + build" (321 tests passed), "android: ktlint + unit tests + lint" and ended with "== all checks passed".

## Bugs found

None in this pass.

Earlier finding status:
- TW-1 (stale settings load overwrites a saved change): fixed in ccbf783; `SettingsService > keeps a saved change when an older load answers after it` is unchanged and passes. Status: resolved.

## Coverage

Status: verified. Fresh `npm run coverage` on HEAD 0df3015 plus this pass's test file, exit 0, all 321 tests passed.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: 153/153 (100%) executable lines, measured by intersecting the lcov `DA` records of this run with the added lines of `git diff -U0 7c425ce..HEAD` per file (removed and non-executable lines excluded; the changed lines of `app.routes.ts` have no `DA` records of their own, so it contributes 0/0). Changed-branch coverage not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: p0-web-settings; base 7c425ce2600f4bac91c024c541bc4ba2dd144cf8 (from the parent, = `git merge-base HEAD origin/main`); HEAD 0df301561a14df382a604353110cdce650360a9d; task commits d21fdf4, 6a996a1, 4789fb9, ccbf783, 9055321, 9bbf846, 0df3015; file scope from `git diff --name-only 7c425ce..HEAD -- web/src` (only task files).
- Source/test snapshot identifier (including uncommitted inputs): production files = HEAD 0df3015 (unmodified); uncommitted `web/src/app/features/settings/general/general-settings.spec.ts` sha256 0aad3732a7cc4b446351aee3138f9896548f2e4ca93a6aa4d5dbf07ae7e0949c (same before and after the runs).
- Exact included files: `web/src/app/app.routes.ts`, `web/src/app/features/settings/general/general-settings.ts`, `settings-layout.ts`, `settings-sections.ts`, `settings.providers.ts`, `settings.routes.ts`, `settings.service.ts`. Excluded: `general/general-settings.html` and `.scss` (template/style, not instrumented by the provider; behavior exercised by the component tests), `features/settings/testing.ts` (test helper), `*.spec.ts`, `app.spec.ts` (tests), `features/README.md` (docs). No renamed or deleted files. All 7 scoped files are in the report (none missing).
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + @vitest/coverage-v8 (Angular `ng test` builder), node v24.21.0; in `web/`: `npm run coverage` (`ng test --watch=false --coverage`, text-summary/json-summary/json/lcov reporters); full suite, no filter.
- Run start/completion time and exit status: 2026-10-20T13:03:39Z - 13:03:58Z, exit 0 (22 files, 321 tests passed).
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 25cb16e325f907dc22339c420af488f72aa20417031b3efc74ea778202d66971, `coverage-summary.json` fdded7c94814e9ccb33b697389be536869dd6c6db02c7d0e8d65afb34068eb70, `lcov.info` 6a6495ad672093d38c38153eb80a7c2d342b3f427b409bb2980d797652d9bf9e.
- Evidence outputs are fresh and inputs stayed unchanged: yes; report files timestamped 15:03:58 +0200 (inside the run window; the provider rewrites its output directory), hashes differ from the previous pass, test-file hash verified after the run, production files unchanged (`git status` only lists the spec and the reviewer's `review.md`).
- Final run after latest test additions and production fixes: yes (after 0df3015 and this pass's 2 tests).

Values below use covered/total (percentage); zero-denominator metrics are N/A. Statements, branches and functions from `coverage-final.json`; lines from the provider's lcov `LH`/`LF`.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/app.routes.ts` | 18/18 (100%) | N/A (0/0) | 17/17 (100%) | 18/18 (100%) |
| `web/src/app/features/settings/general/general-settings.ts` | 110/110 (100%) | 56/61 (91.80%) | 26/26 (100%) | 88/88 (100%) |
| `web/src/app/features/settings/settings-layout.ts` | 23/23 (100%) | 10/11 (90.91%) | 2/2 (100%) | 13/13 (100%) |
| `web/src/app/features/settings/settings-sections.ts` | 1/1 (100%) | N/A (0/0) | N/A (0/0) | 1/1 (100%) |
| `web/src/app/features/settings/settings.providers.ts` | 10/10 (100%) | 2/2 (100%) | 5/5 (100%) | 8/8 (100%) |
| `web/src/app/features/settings/settings.routes.ts` | 4/4 (100%) | N/A (0/0) | 3/3 (100%) | 4/4 (100%) |
| `web/src/app/features/settings/settings.service.ts` | 49/49 (100%) | 19/20 (95%) | 10/10 (100%) | 40/40 (100%) |
| Aggregate (summed counters, 7 files; html/scss and testing.ts excluded as above, none missing) | 215/215 (100%) | 87/94 (92.55%) | 63/63 (100%) | 172/172 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `general-settings.ts:147` branch 1, `:148` branch 1 | `zoneOptions` fallback to the full list for an empty query or a query without matches | Not added: display convenience, no requirement; the match path is covered by `offers the matching zones while typing...` |
| `general-settings.ts:190` branch 1 (`?? 1`) | pending counter missing when a save answers | Justified gap: defensive, unreachable (the counter is always set before the await) |
| `general-settings.ts:200` branch 1 (`if (saved)` false) | `settings()` is `null` on a time zone blur with an invalid value | Justified gap: defensive, the form only shows after a successful load and the service never resets to null |
| `general-settings.ts:72` branch 0, `settings-layout.ts:116` branch 0 | class declaration lines | Provider artifact of the decorated class, no behavior |
| `settings.service.ts:59` (negative branch counter) | `if (patch.theme)` implicit else | Provider artifact (negative v8-to-istanbul counter); patches without `theme` are exercised by several tests (`weekStart`, `timeZone`, labels) |

Both branches of the new `this.pending.get(field) ? this.lastSent.get(field) : saved[field]` (line 176) and of `sameValue(value, latest)` (line 177) are covered.

## Unable to test

- Real browser behavior of the autocomplete (mouse selection followed by blur ordering), real focus/blur when clicking another row, and the responsive layout: only jsdom was used; no Playwright criterion in this task. No follow-up required by the criteria.
- The `general-settings.html`/`.scss` files are not instrumented by the coverage provider; their behavior is covered through the component tests.

## Summary

Tests added and all validation passed. The 0df3015 fix (compare with the last sent value while a row saves) is covered by its own 2 tests plus 2 added in this pass (no duplicate PATCH for the in-flight value; reverting to the saved value while in flight still saves). No production defects found; focused specs (46), `npm run check` (321), `.taskcheck` and a fresh task-scoped `npm run coverage` all pass.
