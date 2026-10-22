# Test Report

Date: 2026-10-22

Task: web-habits (Habits UI)

Scope: task commit 2226ef19e720b72d5e845b720c4a2842250da16b on main, base 40a04b17293e02c853fb96c1f775770355949039 (supplied by the parent). Production changes are only under `web/src/app/features/habits/`. The rest of `git diff --stat 40a04b17..2226ef19` is two `tasks/*.md` files, and those are out of scope. HEAD = 2226ef19 at measurement time. The only uncommitted changes are the test files listed below (plus `reviews/web-habits/review.md` from the code-reviewer, which I did not touch).

Requirements:
- `tasks/web-habits.md` acceptance criteria 1 and 2 (supplied by the parent)
- SPEC.md §8.5 (habits: schedule daily / N per week / weekdays, target_count, reminder_time, archived; one-tap check, streaks, completion % over the last 30 days)
- SPEC.md §4.2 (optimistic locking via If-Match, Problem Details), docs/ARCHITECTURE.md "Coverage"

## Test files changed

- `web/src/app/features/habits/habit-form.spec.ts` (new, 14 tests)
- `web/src/app/features/habits/habits-page.spec.ts` (20 tests added, existing 10 unchanged)
- `web/src/app/features/habits/habit-display.spec.ts` (1 test added, existing 8 unchanged)

## Tests added or updated

Count: 35 added (habit specs went from 22 to 57 tests). No existing test was changed, weakened or skipped.

## Requirement coverage

Focused command (F): `cd web && npx ng test --watch=false --include='src/app/features/habits/**/*.spec.ts'`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 habit list, grouped by due today | `groups the habits by due today ...`, `shows only the "not due" group when nothing is due today`, `shows the loading state until the habits arrive`, `offers to retry a failed load` | F | Pass |
| AC1/AC2 one-tap check toggle | `checks a habit with one tap ...`, `unchecks a done habit with the next tap`, `counts a habit with a target up one per tap`, `sends one check for a double tap`, display `toggles a once-a-day habit between 0 and 1`, `counts up to the target, then back to 0` | F | Pass |
| AC1/AC2 streak display and stats (30-day %) | `groups the habits ... shows streaks and the 30-day completion`, `checks a habit with one tap and shows the new streak`, display `labels streaks in days or weeks`, `rounds the 30-day completion ...` | F | Pass |
| AC1 create | `creates a habit with the form`, `does not send a habit without a name`, `shows a failed create in the form without reloading`, `closes the new-habit form with Escape ...` | F | Pass |
| AC1 edit (If-Match, cancel, conflicts) | `edits a habit with its version as If-Match`, `keeps the edit form after a 412, reloads, and saves again with the new version`, `keeps the form and its error when the reload after a 409 fails`, `disables the form while saving and closes it on cancel`, `keeps the form open when Escape is pressed while saving`, `pre-fills the form with the days of a weekday habit` | F | Pass |
| Habit form: schedule switching, weekday toggle, times per week, times a day, reminder, validation | `habit-form.spec.ts` (all 14 tests) | F | Pass |
| Archive / unarchive / archived view | `archives a habit and shows the archived ones without a check`, `unarchives a habit from the archived view`, `switches between the active and archived habits` | F | Pass |
| Row errors: 409/412 reload, 404 drop, generic error | `reloads the habits after a check conflict ...`, `reloads the habits after a 412 on archive`, `drops a habit that is gone (404) ...`, `shows a failed check next to the habit ...`, `shows a generic message for a failure that is not an API error` | F | Pass |
| Late responses of an earlier view are ignored | `ignores habits of an earlier view that arrive late`, `ignores a failed load of an earlier view that arrives late` | F | Pass |
| Late conflict reload must not overwrite another view | `ignores a conflict reload of the active habits that arrives after switching to archived` | F | **Fail (TW-1)** |
| Service: list/create/update/setCheck, If-Match, silent errors | `habits.service.spec.ts` (4 tests) | F | Pass |
| AC2 `cd web && npm run check` passes | whole web suite | `cd web && npm run check` | **Fail**: api:check, lint and e2e:typecheck passed; test:ci failed only on the TW-1 test; `ng build` was therefore not reached |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/habits/**/*.spec.ts'`

Result:

Fail: 4 files, 56 passed, 1 failed (TW-1, a production defect; the test stays in the file).

Also: `cd web && npx eslint --max-warnings=0 src/app/features/habits` passed (exit 0), and `cd web && npx prettier --check src/app/features/habits` passed.

## Full test execution

Command:

`cd web && npm run check`

Result:

Fail: 137 files, 2731 passed, 1 failed (only TW-1). In the filtered coverage run below, the whole web suite passes with only that test deselected (2731 passed, 1 skipped by filter).

## .taskcheck

Result:

Not run. `scripts/check.sh` runs `npm run -s check` for web, and that fails right now because of the TW-1 test (shown above). The server and Android parts are unaffected by this task. The parent must run `.taskcheck` again after TW-1 is fixed.

## Bugs found

### A conflict reload of the active habits overwrites the archived view

Finding ID:

`TW-1`

Test:

`HabitsPage > ignores a conflict reload of the active habits that arrives after switching to archived` (`web/src/app/features/habits/habits-page.spec.ts`)

Production source:

`web/src/app/features/habits/habits-page.ts:194-201` (`reloadKeepingForm`), started from `save()` at line 139

Requirement:

AC1 (habit list with archive, SPEC.md §8.5). The page's own design rule (the `loadSeq` comment at line 74): "habits that arrive for an earlier view are ignored".

Expected behavior:

1. An edit save fails with 412/409, which starts `reloadKeepingForm()` (GET `archived=false`).
2. The user switches to "Archived" (GET `archived=true`).
3. The archived response arrives first, then the late active-list reload response.

The archived view must keep showing the archived habits, here the empty-state text "No archived habits".

Actual behavior:

`reloadKeepingForm()` calls `this.habits.set(habits)` without checking `loadSeq` or the current view. The late active list replaces the archived list. The "Archived" toggle stays pressed, but the view shows the active habit "Read 20 pages" with its check, Edit and Archive buttons. The same applies the other way round: if a later `load()` starts while the reload is pending, the reload can overwrite it with stale data. A likely fix is to take a `loadSeq` snapshot in `reloadKeepingForm` (without bumping it, or bumping it so later loads still win) and ignore the result if the sequence or view has changed.

Severity:

Low. It needs a conflict followed by a quick view switch. The wrong list stays until the next reload, and actions on it go to the real habit ids.

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Coverage

Status: verified (fresh run with one known-failing test deselected, see below).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. Every in-scope file is new in this task, apart from `habits.routes.ts` (8 changed lines), so whole-file coverage is effectively changed-line coverage for the new files.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-habits; base 40a04b17293e02c853fb96c1f775770355949039; HEAD 2226ef19e720b72d5e845b720c4a2842250da16b; task commit 2226ef19 and base were supplied by the parent. File scope comes from `git diff --stat 40a04b17 2226ef19` restricted to `web/src`, without `*.spec.ts` and the test helper `testing.ts`.
- Source/test snapshot identifier (including uncommitted inputs): production files are unchanged from HEAD (sha256: habits-page.ts daefe16d…, habit-form.ts 3a292061…, habit-display.ts eac2bb71…, habits.service.ts d2b27767…, habits.routes.ts a290896a…, habits-page.html 15b0da40…, habit-form.html f67a9085…). Uncommitted specs (sha256): habit-form.spec.ts 2f222528…, habits-page.spec.ts ff071e11…, habit-display.spec.ts 34132d8b…, habits.service.spec.ts 35b4012f… (unchanged). Combined hash of all habits `*.ts`/`*.html` files before and after the final run: 991a7f4b… (identical).
- Exact included files: `habit-display.ts`, `habit-form.ts`, `habit-form.html`, `habits-page.ts`, `habits-page.html`, `habits.service.ts`, `habits.routes.ts` (all under `web/src/app/features/habits/`). Excluded: `*.spec.ts` and `testing.ts` (test helper); `*.scss` (styles, not instrumented); `tasks/*.md` (not code). Nothing was renamed or deleted.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + @vitest/coverage-v8 5.0.5 via `@angular/build:unit-test`. Command: `npm run coverage -- --filter='^(?!.*ignores a conflict reload of the active habits).*$'` in `web/`. This is the full web suite with only the TW-1 test deselected. Without the filter the run exits 1 and writes no report (I tried this first, at 00:19:19Z-00:20:30Z; no `coverage/` output was produced).
- Run start/completion time and exit status: 2026-10-22T00:22:48Z to 00:23:35Z, exit 0 (137 files, 2731 passed, 1 deselected).
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` (sha256 58516c2d…), `web/coverage/web/coverage-summary.json` (sha256 32a4c40a…), `lcov.info`. Written at 02:23:35 local time (+0200) by this run.
- Evidence outputs are fresh and inputs stayed unchanged: yes. `web/coverage/` did not exist before my first successful run. Report mtimes fall inside the final run window. Input hashes were the same before and after.
- Final run after latest test additions and production fixes: yes for the latest test additions. No production fixes have been made yet (TW-1 is open), so a new run is needed after the fix.

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/habits/habit-display.ts` | 34/34 (100%) | 46/47 (97.87%) | 14/14 (100%) | 31/31 (100%) |
| `web/src/app/features/habits/habit-form.ts` | 35/35 (100%) | 16/16 (100%) | 13/13 (100%) | 25/25 (100%) |
| `web/src/app/features/habits/habit-form.html` | 102/102 (100%) | 6/6 (100%) | 11/11 (100%) | 66/66 (100%) |
| `web/src/app/features/habits/habits-page.ts` | 126/129 (97.67%) | 67/78 (85.90%) | 40/40 (100%) | 99/99 (100%) |
| `web/src/app/features/habits/habits-page.html` | 180/180 (100%) | 37/37 (100%) | 11/11 (100%) | 117/117 (100%) |
| `web/src/app/features/habits/habits.service.ts` | 10/10 (100%) | 5/5 (100%) | 5/5 (100%) | 7/7 (100%) |
| `web/src/app/features/habits/habits.routes.ts` | 1/2 (50%) | N/A (0/0) | 0/1 (0%) | 1/2 (50%) |
| **Aggregate (7 files, summed counters)** | 488/492 (99.19%) | 177/189 (93.65%) | 94/95 (98.95%) | 346/347 (99.71%) |

All 7 scoped files were measured; none are missing. The aggregate leaves out only the excluded files listed above.

For comparison, an intermediate run earlier in this session (00:20:45Z-00:21:52Z, same filtered command, before the last 5 tests were added) left `habits-page.ts` at 125/129 statements and 65/78 branches, `habits-page.html` at 36/37 branches, and `habit-display.ts` at 45/47 branches. Those gaps led to the last 5 added tests.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `habits-page.ts:120,122` (`save` guards: no form open / already saving / edited habit missing) | Defensive. The form's own `saving` guard blocks double submits, and the edited habit can only disappear through a reload | Justified gap: not reachable through the UI. Double submit is covered at the form level (`neither saves nor cancels while saving`) |
| `habits-page.ts:131#1,132#1` (create while in archived view; create while the list is null) | Defensive. "New habit" is hidden in the archived view, and switching views closes the form | Justified gap: not reachable through the UI |
| `habits-page.ts:156#1,157` (archive the habit currently being edited) | Defensive. The row's archive button is replaced by the form while editing | Justified gap: not reachable through the UI |
| `habits-page.ts:57#1,59#1,190#1,212#1` (`?? []` / `?? null` when the list is null) | A response arriving while a reload shows the loading state | Low value. The conflict-reload tests show that the list is replaced by the fresh load |
| `habits-page.ts:40#0` | Branch the compiler emits on the class declaration (decorator/metadata output), not behavior | Non-material |
| `habit-display.ts:52#1` (`timesPerWeek ?? 1` for a TIMES_PER_WEEK habit without timesPerWeek) | API contract fallback | Non-material. The server always sends timesPerWeek for that schedule |
| `habits.routes.ts:7` (lazy `loadComponent`) | Route wiring | Non-material. The route is registered in `app.routes.ts`, and lazy loading runs only in the browser/e2e |

## Unable to test

- AC2's `ng build` step of `npm run check` was not reached because test:ci fails on TW-1. Needs a new run after the fix.
- Browser-level behavior (real focus and Escape in a browser, visual progress ring) was not run in Playwright. This task's criteria do not name e2e, so no follow-up is required.
- Daily reminders (`reminder_time`): the UI only stores and shows the time. Delivering reminders is outside this task.

## Summary

I added the tests, and they found one production defect (TW-1, Low). It must go to the `fixer` before the task is completed. All other acceptance-criterion behavior (check toggle, streak and stats display, create/edit, archive, conflict paths) is tested and passes. Task-scoped coverage is 99.19% statements and 93.65% branches; the remaining gaps are justified above.
