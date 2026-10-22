# Test Report

Date: 2026-10-22

Task: web-habits (Habits UI), final pass on the code after the CR-1/CR-2 fixes (round 2)

Scope: `git diff 91479195047fb9c5755b6846ec9021d1b387f38e..953d7e6be47f316b139913f51d94f800fd3278f6 -- web/`. The parent supplied the base and the task commits 91fd3a73, 32e29161, 77c76f62, 980687e2, 4731a876 and 953d7e6b. I checked them with `git log --oneline 91479195..HEAD` and `git diff --stat 91479195..HEAD`. All production changes are in `web/src/app/features/habits/`. The only other web change is the one-line `'/habits'` addition in `web/src/app/app.spec.ts`, which is a test. HEAD was 953d7e6be47f316b139913f51d94f800fd3278f6 on main when I measured. My only uncommitted change is `web/src/app/features/habits/habits-page.spec.ts`. The latest commit, 953d7e6b, fixes CR-1 (a quiet reload overwrote local changes, now guarded by `changeSeq`) and CR-2 (a conflict on another row cleared an open edit form, now handled by `reloadQuietly()` in `run()`) in `habits-page.ts`.

Requirements:
- `tasks/web-habits.md` acceptance criteria 1 and 2, plus the recorded assumptions: a tap counts up to the target and then wraps to 0, the check targets `stats.today`, there is no delete action, and messages.xlf is not regenerated
- SPEC.md §8.5 (habits list, one-tap check, streaks, 30-day completion) and §4.2 (If-Match)
- `reviews/web-habits/review.md` (CR-1, CR-2) and `reviews/web-habits/fixes.md`
- docs/ARCHITECTURE.md "Coverage"

## Test files changed

- `web/src/app/features/habits/habits-page.spec.ts` (4 tests added; no existing test changed)

## Tests added or updated

Count: 4 added. The habits specs now have 71 tests: 45 page, 14 form, 8 display and 4 service. No existing test was changed, weakened or skipped.

I checked the round-2 fixes against the committed specs first:
- CR-1/TW-2: `keeps a check made while the reload on becoming visible is still pending` passes now. It covers the case where the change finishes before the reload answers, on the check path only.
- CR-2: `keeps the typed values of an open edit form when a check on another habit gets a 412` passes. So does the updated `reloads the habits after a check conflict and keeps the error on the row`, which checks that there is no loading flash and that the row error stays. This also shows that a failed change does not drop its own conflict reload.

Four regression tests for the `changeSeq` guard were missing. I added them:
- `drops the reload on becoming visible when it answers while a check is still running`: the reload answers *before* the PUT. The stale count (0/3) must not replace 1/3 while the check is pending. After the PUT the row shows 2/3, and the next tap sends `count: 3` and not 2 again. This covers the "change started" increment in `run()` and the lost tap on count habits that the review describes.
- `does not bring back a habit archived while the reload on becoming visible is pending`: the archive path through `run()`. An archived habit must not come back from a stale reload.
- `keeps a saved edit when an earlier conflict reload answers after the save`: the edit path through `save()`. A 409 on another habit starts a quiet reload. An edit that is saved before the reload answers keeps its name and version, and the next save sends `If-Match: "2"`. The other row's error stays.
- `keeps a created habit when an earlier conflict reload answers after the create`: the create path through `save()`. A 412 on a check starts a quiet reload. A habit created before the reload answers stays in the list.

All four pass against 953d7e6b. I checked by reading the code that each one would fail without the matching `changeSeq` increment: the stale `habits.set()` would bring back 0/3, the archived row, the old name and version, or a list without the new habit. I did not mutate production code to prove this.

## Requirement coverage

Focused command (F): `cd web && npx ng test --watch=false --include='src/app/features/habits/**/*.spec.ts'`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 habit list grouped by due today, loading/empty/error states | `groups the habits by due today ...`, `shows only the "not due" group ...`, `shows the loading state ...`, `offers to retry a failed load` | F | Pass |
| AC1/AC2 one-tap check toggle | `checks a habit with one tap ...`, `unchecks a done habit with the next tap`, `counts a habit with a target up one per tap`, `sends one check for a double tap`, `shows a failed check next to the habit ...`, display `toggles a once-a-day habit between 0 and 1`, `counts up to the target, then back to 0` | F | Pass |
| AC1/AC2 streak display (ICU plurals) and 30-day stats | `groups the habits ... streaks and the 30-day completion`, `checks a habit with one tap and shows the new streak`, `uses the singular and plural of days and weeks for streaks`, display `rounds the 30-day completion ...` | F | Pass |
| AC1 create / edit (If-Match, 409/412, cancel, Escape) | `creates a habit with the form`, `edits a habit with its version as If-Match`, `keeps the edit form after a 412, reloads, and saves again ...`, `keeps the form and its error when the reload after a 409 fails`, the other form tests, `habit-form.spec.ts` (14) | F | Pass |
| Archive / unarchive / archived view, 404 drop, row errors | `archives a habit ...`, `unarchives a habit ...`, `switches between ...`, `drops a habit that is gone (404) ...`, `reloads the habits after a 412 on archive`, `shows a generic message ...` | F | Pass |
| Load ordering (earlier view, newer load) | `ignores habits of an earlier view that arrive late`, `ignores a failed load of an earlier view ...`, `ignores a conflict reload ... after switching to archived`, `ignores a conflict reload that arrives after a newer load of the same view`, `drops the reload on becoming visible when the view is switched meanwhile` | F | Pass |
| Reload on becoming visible | `reloads the habits when the page becomes visible again ...`, `does not reload when the page is hidden, a form is open or a check is running`, `does not reload while the habits are still loading`, `tries a failed load again ...`, `stops listening ... when destroyed`, `keeps the list when the reload on becoming visible fails` | F | Pass |
| CR-1/TW-2 a quiet reload never undoes a local change (check, archive, edit, create; the change started or finished meanwhile) | `keeps a check made while the reload on becoming visible is still pending`, `drops the reload on becoming visible when it answers while a check is still running` (new), `does not bring back a habit archived while ...` (new), `keeps a saved edit when an earlier conflict reload answers after the save` (new), `keeps a created habit when an earlier conflict reload answers after the create` (new) | F | Pass |
| CR-2 a conflict on another row keeps an open edit form; a failed change keeps its own reload | `keeps the typed values of an open edit form when a check on another habit gets a 412`, `reloads the habits after a check conflict and keeps the error on the row`, `reloads the habits after a 412 on archive` | F | Pass |
| Service: list/create/update/setCheck, If-Match, silent errors | `habits.service.spec.ts` (4) | F | Pass |
| AC2 `cd web && npm run check` passes | whole web suite | `cd web && npm run check` | Pass |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/habits/**/*.spec.ts'`

Result:

Pass: 4 files, 71 tests passed (00:41:54Z).

Also, `cd web && npx prettier --check src/app/features/habits` passes ("All matched files use Prettier code style!"), and `cd web && npx eslint --max-warnings=0 src/app/features/habits` passes (exit 0).

## Full test execution

Command:

`cd web && npm run check`

Result:

Pass (00:42:30Z to 00:43:17Z). The script chains api:check, lint, e2e:typecheck, test:ci and `ng build` with `&&`. test:ci ran 137 files with 2746 tests, and all passed. `ng build` then completed ("Output location: web/dist/web"), so every earlier step passed too. The build showed the existing warning that the initial bundle exceeds its budget (668 kB against 500 kB). habits is a lazy chunk, so this task does not affect it.

## .taskcheck

Result:

Fail, for environmental reasons; the parent must run it again. I ran `sh scripts/check.sh` from 00:44:31Z to 01:05:13Z. The server step failed: `./gradlew --quiet check` failed in `:test` with "Process 'Gradle Test Executor 4' finished with non-zero exit value 137", which means SIGKILL, likely because memory ran out. `BUILD FAILED in 20m 29s`. The machine's load average was about 190 at the time, because parallel agents were running. This task changes no server code, and the web and android steps were never reached. The web part of the check (`npm run check`) passes, as shown above. Because of `set -eu` the script ended at the server step. The `| tail` pipe in my command hid that exit code, so the background job reported exit 0. The parent must run `.taskcheck` again on a less loaded machine before `done`.

## Bugs found

None. The round-2 fixes (CR-1/TW-2 and CR-2) are covered by passing tests, and the new tests found no defect.

Note for the parent (a design consequence, not a defect): the `changeSeq` guard drops a conflict reload whenever any other change starts or finishes while that reload is pending. The conflicted row then keeps its old version and its error. The next action on it gets a 409/412 again, and that starts a new reload. `reloadQuietly()`'s comment documents this ("the next action shows the problem again"). In `keeps a saved edit when an earlier conflict reload answers after the save`, the WATER row keeps its error and version 1, as expected.

## Coverage

Status: verified (a fresh run of the full web suite on 953d7e6b plus the 4 new tests, exit 0).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured with a tool. Every in-scope file except `habits.routes.ts` (8 changed lines) is new in this task, so whole-file coverage equals changed-line coverage for those files. For the round-2 fix (953d7e6b), I checked each changed executable line of `habits-page.ts` against `coverage-final.json`: lines 140, 143-145, 147-148, 158, 235, 238, 249, 252, 255 and 261. None is among the uncovered statements or branches listed below, and both outcomes of the guard at line 238 are covered.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-habits; base 91479195047fb9c5755b6846ec9021d1b387f38e; HEAD 953d7e6be47f316b139913f51d94f800fd3278f6; commits 91fd3a73..953d7e6b (6 commits). The parent supplied the base and commits, and I checked them with `git log`. The file scope comes from `git diff --name-only 91479195..HEAD -- web/src`.
- Source/test snapshot identifier (including uncommitted inputs): the production files equal HEAD. The combined sha256 of all `web/src/app/features/habits/*.ts` and `*.html` files (specs included) was `1e22ceff…79f5` before the focused run and the same after the coverage run. `habits-page.spec.ts` (uncommitted, 4 tests added) has sha256 `27c603a6…7f49`.
- Exact included files: `habit-display.ts`, `habit-form.ts`, `habit-form.html`, `habits-page.ts`, `habits-page.html`, `habits.service.ts` and `habits.routes.ts`, all in `web/src/app/features/habits/`. Excluded: `*.spec.ts` and `testing.ts` (the test helper), `web/src/app/app.spec.ts` (a test), and `*.scss` (styles, not instrumented). Nothing was renamed or deleted.
- Provider/version, command, working directory, and test selection: Vitest v5.0.5 + @vitest/coverage-v8 through `@angular/build:unit-test`, node v24.21.0. I ran `npm run coverage` in `web/` on the full web suite with no filter.
- Run start/completion time and exit status: 2026-10-22T00:43:23Z to 00:44:04Z. All 137 files and 2746 tests passed, and the coverage summary was printed.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` (sha256 eab6ff91…2b2a), `web/coverage/web/coverage-summary.json` (sha256 1a846b97…6f96) and `web/coverage/web/lcov.info`. All three were written at 02:44:04 +0200, inside the run window.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report mtimes fall inside this run, and the input hash is the same before and after.
- Final run after latest test additions and production fixes: yes. The run covers 953d7e6b (the final fix commit) with my 4 new tests.

Values below use covered/total (percentage). Statements, branches and functions come from the provider's counters in `coverage-final.json`. Lines come from `coverage-summary.json`.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/habits/habit-display.ts` | 33/33 (100%) | 40/41 (97.56%) | 13/13 (100%) | 30/30 (100%) |
| `web/src/app/features/habits/habit-form.ts` | 35/35 (100%) | 16/16 (100%) | 13/13 (100%) | 25/25 (100%) |
| `web/src/app/features/habits/habit-form.html` | 102/102 (100%) | 6/6 (100%) | 11/11 (100%) | 66/66 (100%) |
| `web/src/app/features/habits/habits-page.ts` | 150/153 (98.04%) | 82/93 (88.17%) | 44/44 (100%) | 119/119 (100%) |
| `web/src/app/features/habits/habits-page.html` | 194/194 (100%) | 39/39 (100%) | 11/11 (100%) | 126/126 (100%) |
| `web/src/app/features/habits/habits.service.ts` | 10/10 (100%) | 5/5 (100%) | 5/5 (100%) | 7/7 (100%) |
| `web/src/app/features/habits/habits.routes.ts` | 1/2 (50%) | N/A (0/0) | 0/1 (0%) | 1/2 (50%) |
| **Aggregate (7 files, summed counters)** | 525/529 (99.24%) | 188/200 (94.00%) | 97/98 (98.98%) | 374/375 (99.73%) |

All 7 scoped files were measured, and none is missing. The aggregate leaves out only the excluded files listed above.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `habits-page.ts:135,137` (`save` guards: no form open, already saving, edited habit missing) | Defensive. The form's own `saving` guard blocks a double submit | Justified gap: not reachable through the UI. The form test `neither saves nor cancels while saving` covers the guard there |
| `habits-page.ts:150#1,151#1` (create in the archived view; create while the list is null) | Defensive. "New habit" is hidden in the archived view, and switching views closes the form | Justified gap: not reachable through the UI |
| `habits-page.ts:177#1,178` (archiving the habit that is being edited) | Defensive. While editing, the form replaces the row's archive button | Justified gap: not reachable through the UI |
| `habits-page.ts:58#1,60#1,211#1,257#1` (`?? []` / `?? null` when the list is null) | A row result that arrives while a load shows the loading state | Low value. The ordering tests show that the fresh load wins |
| `habits-page.ts:41#0` | Branch the compiler emits on the class declaration, not behavior | Non-material |
| `habit-display.ts:41#1` (`timesPerWeek ?? 1`) | Fallback for the API contract | Non-material. The server always sends timesPerWeek for TIMES_PER_WEEK |
| `habits.routes.ts:7` (lazy `loadComponent`) | Route wiring | Non-material. The route is registered in `app.routes.ts`, and the lazy load runs only in a browser/e2e |

## Unable to test

- Browser-level behavior was not run in Playwright: a real tab switch firing `visibilitychange`, and focus and Escape in a real browser. The jsdom specs simulate `visibilityState`. The criteria do not name e2e, so no follow-up is required.
- The remaining CR-3 limitation in the round-1 fixes is documented, not a test gap: a check tapped on a stale tab that was never hidden and shown again uses the old date.

## Summary

Tests added and all validation passed, except `.taskcheck`. That failed for an environmental reason: an out-of-memory SIGKILL in the server's Gradle tests on an overloaded machine, outside this task. The parent must run it again. The round-2 fixes for CR-1/TW-2 (`changeSeq` guard) and CR-2 (quiet conflict reload) are covered: 4 new regression tests cover the check, archive, edit and create paths, including the case where the change starts before the reload answers. All 71 habits specs pass, and `cd web && npm run check` passes. No production defects were found. Task-scoped coverage is 99.24% of statements and 94.00% of branches, summed over 7 files, and the remaining gaps are justified above.
