# Test Report

Date: 2026-10-22

Task: web-habits (Habits UI), second pass on the code after the fixes

Scope: `git diff dcbfae15eaeafd1591491534f472fffc8ed3d9bb..b9162130404aaccfce7a13f7a437ecde1a5919df -- web/` (base and scope supplied by the parent). Task commits: 47d7cc80, 2fa4b0bc, cc3b59d4, b9162130. Production changes are only in `web/src/app/features/habits/`. The other web change is one line (`'/habits'`) in `web/src/app/app.spec.ts`, which is a test. HEAD = b9162130404aaccfce7a13f7a437ecde1a5919df on main when I measured. My only uncommitted change is `web/src/app/features/habits/habits-page.spec.ts`. `reviews/web-habits/review.md` is also modified, but by the code-reviewer, and I did not touch it.

Requirements:
- `tasks/web-habits.md` acceptance criteria 1 and 2, plus the recorded assumptions (one-tap counts up to the target and then wraps; the check targets `stats.today`)
- SPEC.md §8.5 (habits list, one-tap check, streaks, 30-day completion)
- `reviews/web-habits/fixes.md` (TW-1/CR-1 conflict reload guard, CR-3 reload on `visibilitychange`, CR-2 ICU plural streaks)
- docs/ARCHITECTURE.md "Coverage"

## Test files changed

- `web/src/app/features/habits/habits-page.spec.ts` (3 tests added; no existing test changed)

## Tests added or updated

Count: 3 added. The habits specs now have 66 tests: 40 page, 14 form, 8 display, 4 service. No existing test was changed, weakened or skipped.

I checked the fixed behavior against the existing specs first:
- TW-1/CR-1: `ignores a conflict reload of the active habits that arrives after switching to archived` (the original TW-1 test, unchanged) and `ignores a conflict reload that arrives after a newer load of the same view` both pass. Nothing was missing.
- CR-2: `uses the singular and plural of days and weeks for streaks` covers 1 day, 0 weeks and 2 weeks. The other page tests cover '4 days', '12 days' and '1 week'. Nothing was missing.
- CR-3: five specs cover the reload on becoming visible: the next check goes to the new `stats.today`; no reload when the page is hidden, a form is open or a check is running; no reload while the habits are loading; a failed load is retried; no reload after the component is destroyed. Three cases were missing, so I added these tests:
  - `keeps a check made while the reload on becoming visible is still pending`: the user taps while the quiet reload is running, and the reload answers last. **Fails: TW-2.**
  - `drops the reload on becoming visible when the view is switched meanwhile`: the `loadSeq` guard on the visibility path. Passes.
  - `keeps the list when the reload on becoming visible fails`: the list stays, no load error is shown, and the check stays enabled. Passes.

## Requirement coverage

Focused command (F): `cd web && npx ng test --watch=false --include='src/app/features/habits/**/*.spec.ts'`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 habit list grouped by due today, loading/empty/error states | `groups the habits by due today ...`, `shows only the "not due" group ...`, `shows the loading state ...`, `offers to retry a failed load` | F | Pass |
| AC1/AC2 one-tap check toggle | `checks a habit with one tap ...`, `unchecks a done habit with the next tap`, `counts a habit with a target up one per tap`, `sends one check for a double tap`, display `toggles a once-a-day habit between 0 and 1`, `counts up to the target, then back to 0` | F | Pass |
| AC1/AC2 streak display (ICU plural, CR-2) and 30-day stats | `groups the habits ... streaks and the 30-day completion`, `checks a habit with one tap and shows the new streak`, `uses the singular and plural of days and weeks for streaks`, display `rounds the 30-day completion ...` | F | Pass |
| AC1 create / edit (If-Match, 409/412, cancel, Escape) | `creates a habit with the form`, `edits a habit with its version as If-Match`, `keeps the edit form after a 412, reloads, and saves again ...`, `keeps the form and its error when the reload after a 409 fails`, the rest of the form tests, `habit-form.spec.ts` (14) | F | Pass |
| Archive / unarchive / archived view, 404 drop, row errors | `archives a habit ...`, `unarchives a habit ...`, `switches between ...`, `drops a habit that is gone (404) ...`, `reloads the habits after a 412 on archive`, `shows a generic message ...` | F | Pass |
| TW-1/CR-1 sequenced conflict reload | `ignores a conflict reload of the active habits that arrives after switching to archived`, `ignores a conflict reload that arrives after a newer load of the same view` | F | Pass |
| CR-3 reload on becoming visible | `reloads the habits when the page becomes visible again ...`, `does not reload when the page is hidden, a form is open or a check is running`, `does not reload while the habits are still loading`, `tries a failed load again ...`, `stops listening ... when destroyed`, `drops the reload on becoming visible when the view is switched meanwhile`, `keeps the list when the reload on becoming visible fails` | F | Pass |
| CR-3: a check made during the quiet reload must not be undone by the reload | `keeps a check made while the reload on becoming visible is still pending` | F | **Fail (TW-2)** |
| Service: list/create/update/setCheck, If-Match, silent errors | `habits.service.spec.ts` (4) | F | Pass |
| AC2 `cd web && npm run check` passes | whole web suite | `cd web && npm run check` | **Fail**: api:check, lint and e2e:typecheck pass; test:ci fails only on the TW-2 test (2740 passed, 1 failed), so `ng build` was not reached |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/habits/**/*.spec.ts'`

Result:

Fail: 4 files, 65 passed, 1 failed (TW-2, a production defect; the test stays in the file).

Also, `cd web && npx eslint --max-warnings=0 src/app/features/habits` passes (exit 0), and `cd web && npx prettier --check src/app/features/habits` passes ("All matched files use Prettier code style!").

## Full test execution

Command:

`cd web && npm run check`

Result:

Fail (exit 1). My first run (around 00:32Z) had 83 failures in 12 files. Almost all of them were 5000 ms timeouts in unrelated specs such as `settings/general/general-settings.spec.ts`, which points to machine load from parallel agents. The filtered coverage run right after it (full suite, only TW-2 deselected) passed all 137 files. The second run (finished 00:36:00Z) had 137 files, 2740 passed and 1 failed. The only failure was TW-2.

## .taskcheck

Result:

Not run. `scripts/check.sh` runs `npm run -s check` for web, and that currently fails on the TW-2 test (shown above). The parent must run `.taskcheck` after TW-2 is fixed.

## Bugs found

### A check made while the reload on becoming visible is pending is undone by the reload

Finding ID:

`TW-2`

Test:

`HabitsPage > keeps a check made while the reload on becoming visible is still pending` (`web/src/app/features/habits/habits-page.spec.ts:766`)

Production source:

`web/src/app/features/habits/habits-page.ts:221-229` (`reloadQuietly`), started by `refresh()` at line 212 (the CR-3 fix). The same applies to the conflict reload started at line 149.

Requirement:

AC1 one-tap check (SPEC.md §8.5): after a tap, the row shows the habit's new state. The fix for CR-3 (`fixes.md`) says the visibility reload must not interfere while the user works with the list, which is why `refresh()` skips the reload while a row is busy.

Expected behavior:

1. The page becomes visible, and `reloadQuietly()` sends GET `archived=false`.
2. Before that answers, the user taps the check of "Read 20 pages". PUT `.../checks/2026-10-22` answers with `doneToday: true`, `currentStreak: 5`, and the row shows it as done.
3. The GET then answers with the state it read before the check (`doneToday: false`).

The row must keep showing the check (aria-pressed "true", "5 days").

Actual behavior:

`reloadQuietly()` guards only against a newer `load()` (`seq === this.loadSeq`). It does not guard against a check or change made after it started, so `habits.set(habits)` replaces the checked row with the stale one. aria-pressed goes back to "false" (assertion at line 786: expected 'true', received 'false'). The server has the check, but the page shows it as not done until the next reload. Checks send an absolute count without If-Match. For a habit with a target above 1, the next tap therefore sends the same count again, and that tap is lost (for example, 2/3 shown as 1/3, and the next tap sends 2 again). This is a likely window: returning to the tab and tapping right away. A possible fix is to drop or merge the quiet reload's result when a check or change started (or finished) after the reload began, e.g. by bumping a separate change counter in `run()` and `save()`, or by reloading again after it.

Severity:

Low. The server data is correct, and the display fixes itself on the next reload. However, it breaks the one-tap check display right after returning to the tab, and it can swallow a tap on count habits.

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Coverage

Status: verified (fresh run of the full web suite with the one known-failing TW-2 test deselected; without the filter, the run exits 1 and writes no report).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. Every in-scope file except `habits.routes.ts` (8 changed lines) is new in this task, so whole-file coverage is effectively changed-line coverage for those files.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-habits; base dcbfae15eaeafd1591491534f472fffc8ed3d9bb; HEAD b9162130404aaccfce7a13f7a437ecde1a5919df; commits 47d7cc80, 2fa4b0bc, cc3b59d4, b9162130. The parent supplied the base and the scope, and I checked them with `git log`/`git diff --stat dcbfae15..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): production files equal HEAD. Combined sha256 of all `web/src/app/features/habits/*.ts` and `*.html` (specs included), before and after the run: 40775c18…6fee (identical). Spec sha256: habits-page.spec.ts c5ccbfb3… (uncommitted, 3 tests added), habit-form.spec.ts 2f222528…, habit-display.spec.ts b65f43f9…, habits.service.spec.ts 35b4012f….
- Exact included files: `habit-display.ts`, `habit-form.ts`, `habit-form.html`, `habits-page.ts`, `habits-page.html`, `habits.service.ts`, `habits.routes.ts` (all in `web/src/app/features/habits/`). Excluded: `*.spec.ts`, `testing.ts` (test helper), `web/src/app/app.spec.ts` (test), `*.scss` (styles, not instrumented). Nothing was renamed or deleted.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + @vitest/coverage-v8 ^5.0.5 via `@angular/build:unit-test`, node v24.21.0. Command run in `web/`: `npm run coverage -- --filter='^(?!.*keeps a check made while the reload on becoming visible is still pending).*$'`. This is the full web suite with only TW-2 deselected.
- Run start/completion time and exit status: 2026-10-22T00:33:42Z to 00:34:58Z, exit 0 (137 files, 2740 passed, 1 skipped by the filter).
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` (sha256 4e2be369…), `web/coverage/web/coverage-summary.json` (sha256 9aa5ca80…), `lcov.info`. All were written at 02:34:57-58 +0200, inside the run window.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report mtimes fall inside this run, Vitest rewrites `coverage/` on each run, and the input hash is the same before and after.
- Final run after latest test additions and production fixes: yes. The run covers the code after the fixes (b9162130) and my 3 new tests. TW-2 is open, so a new run is needed after it is fixed.

Values below use covered/total (percentage). Statements, branches and functions come from the provider's counters in `coverage-final.json`. Lines come from `coverage-summary.json`.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/habits/habit-display.ts` | 33/33 (100%) | 40/41 (97.56%) | 13/13 (100%) | 30/30 (100%) |
| `web/src/app/features/habits/habit-form.ts` | 35/35 (100%) | 16/16 (100%) | 13/13 (100%) | 25/25 (100%) |
| `web/src/app/features/habits/habit-form.html` | 102/102 (100%) | 6/6 (100%) | 11/11 (100%) | 66/66 (100%) |
| `web/src/app/features/habits/habits-page.ts` | 140/143 (97.90%) | 80/91 (87.91%) | 44/44 (100%) | 109/109 (100%) |
| `web/src/app/features/habits/habits-page.html` | 194/194 (100%) | 39/39 (100%) | 11/11 (100%) | 126/126 (100%) |
| `web/src/app/features/habits/habits.service.ts` | 10/10 (100%) | 5/5 (100%) | 5/5 (100%) | 7/7 (100%) |
| `web/src/app/features/habits/habits.routes.ts` | 1/2 (50%) | N/A (0/0) | 0/1 (0%) | 1/2 (50%) |
| **Aggregate (7 files, summed counters)** | 515/519 (99.23%) | 186/198 (93.94%) | 97/98 (98.98%) | 364/365 (99.73%) |

All 7 scoped files were measured, and none are missing. The aggregate leaves out only the excluded files listed above. The test for the TW-2 path did not run in this measurement, but it would only execute lines that are already covered.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `habits-page.ts:130,132` (`save` guards: no form open / already saving / edited habit missing) | Defensive. The form's own `saving` guard blocks a double submit | Justified gap: not reachable through the UI. The form test `neither saves nor cancels while saving` covers it |
| `habits-page.ts:141#1,142#1` (create in the archived view; create while the list is null) | Defensive. "New habit" is hidden in the archived view, and switching views closes the form | Justified gap: not reachable through the UI |
| `habits-page.ts:166#1,167` (archive the habit currently being edited) | Defensive. The row's archive button is replaced by the form while editing | Justified gap: not reachable through the UI |
| `habits-page.ts:58#1,60#1,200#1,240#1` (`?? []` / `?? null` when the list is null) | A row result arriving while a load shows the loading state | Low value. The ordering tests show that the fresh load wins |
| `habits-page.ts:41#0` | Branch the compiler emits on the class declaration, not behavior | Non-material |
| `habit-display.ts:41#1` (`timesPerWeek ?? 1`) | Fallback for the API contract | Non-material. The server always sends timesPerWeek for TIMES_PER_WEEK |
| `habits.routes.ts:7` (lazy `loadComponent`) | Route wiring | Non-material. The route is registered in `app.routes.ts`, and the lazy load runs only in a browser/e2e |
| `habits-page.ts:221-229` (stale result of the quiet reload after a check) | AC1 one-tap check | TW-2. The covering test fails and stays in place |

## Unable to test

- AC2's `ng build` step was not reached in `npm run check`, because test:ci fails on TW-2. It needs a new run after the fix.
- Browser-level behavior (a real tab switch firing `visibilitychange`, focus and Escape in a browser) was not run in Playwright. The jsdom specs simulate `visibilityState`. The criteria do not name e2e, so no follow-up is required.
- The remaining CR-3 limitation in `fixes.md` (a check tapped on a stale tab before it was hidden and shown again uses the old date) is a documented limitation, not a test gap.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion. The fixes for TW-1/CR-1, CR-2 and CR-3 are tested and pass. One new defect, TW-2 (Low), was found on the new visibility reload path: a quiet reload that answers after a check undoes the check on screen. Task-scoped coverage is 99.23% statements and 93.94% branches (summed over 7 files), and the remaining gaps are justified above.
