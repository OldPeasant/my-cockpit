# Test Report

Date: 2026-10-22

Task: web-today-habits - Today view: habits due

Scope: task base `1c092335a2909e97cec38e57d680049129ca72ef` .. HEAD `a6613d25eb3780a079810997efc0c60cbddf3e5a` on `main`. The task commit given by the parent (`553be17ef0c8eb08e46ccd6eacd5296c1645d264`) has the same code diff as HEAD; `git diff 553be17ef a6613d25e` differs only in `tasks/web-today-habits.md` (an assumption line). Uncommitted: only the two spec files changed by this report (plus the untracked `reviews/web-today-habits/` written by the reviewers).

Requirements:
- `tasks/web-today-habits.md` acceptance criteria (1) habits due today with one-tap check, (2) Vitest component/service tests cover the habits section; `cd web && npm run check` passes
- Task assumption: section hidden without active habits, "No habits due today" when none is due, registered via `provideTodaySection` in `today.routes.ts` (order 10)
- SPEC.md §5.3 / §8.5 (as cited in the production code), `docs/ARCHITECTURE.md` "Coverage", parent instructions (route registration, check/reload race, 409/412 quiet reload)
- Reference behavior: `web/src/app/features/habits/habits-page.ts` (same one-tap check, `refresh()` on visibility return)

## Test files changed

- `web/src/app/features/gtd/today/today-habits.spec.ts`
- `web/src/app/features/gtd/today/today-view.spec.ts`

## Tests added or updated

Count: 18 new tests (17 in `today-habits.spec.ts`, counting the 409 and 412 variants separately; 1 in `today-view.spec.ts`). No existing test changed.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| (1) Today shows the habits due today (route registers the section, TodayView renders it) | `today routes > registers the habits section, so the routed Today view shows today's habits` (new; RouterTestingHarness on the real `today.routes.ts`) | `cd web && npx ng test --watch=false --include='src/app/features/gtd/today/*.spec.ts'` | Pass |
| (1) Only due habits, progress, streak (days and weeks), count | `shows only the habits due today, with progress and streak`, `shows a weekly streak in weeks` (new), `says so when no habit is due today`, `stays hidden without any active habit` | same | Pass |
| (1) One-tap check / uncheck / count towards target | `checks a habit with one tap`, `unchecks a done habit with the next tap`, `counts up towards a target above one`, `marks a habit done once its target above one is reached` (new) | same | Pass |
| (1) Check errors: shown on the row, 404 drops the row, 409/412 quiet reload keeps rows, failed conflict reload stays quiet, other errors do not reload | `shows a failed check next to the habit`, `drops a habit deleted meanwhile`, `shows a 409 conflict and reloads quietly, keeping the habits on screen` (new), `shows a 412 conflict ...` (new), `keeps the habits and no load error when the conflict reload fails` (new), `does not reload after a check that failed other than with a conflict` (new), `shows only the error of the last failed check` (new) | same | Pass |
| (1) Concurrency: double tap, two checks in reverse order, quiet reload vs. check in both orders, no reload while a check runs | `sends one check for a double tap`, `checks two habits at the same time, answered in reverse order`, `drops a quiet reload answered after a check that started meanwhile`, `drops a quiet reload answered while a check that started after it is running`, `does not reload on return while a check is running` (all new) | same | Pass |
| (1) Reload on return (visibility): visible reloads, hidden does not, listener removed on destroy | `reloads quietly when the page becomes visible again`, `does not reload when the page is hidden` (new), `stops listening for visibility changes once destroyed` (new) | same | Pass |
| (1) Loading / failed load / retry | `shows loading, then a failed load with a retry` | same | Pass |
| (1) Loading must end, and a stale load error must clear, when a reload on return overlaps the first load or follows a failed one | `ends the loading note with a retry when a reload on return fails during the first load` (TW-1), `shows the habits when a reload on return succeeds after a failed load` (TW-2) | same | Fail (production defects TW-1, TW-2) |
| (2) `cd web && npm run check` passes | lint part: `npx ng lint --max-warnings=0` | `cd web && npx ng lint --max-warnings=0` | Pass (lint only). Full `npm run check` Not run: its `test:ci` step fails on TW-1/TW-2 (same suite as the full run below) |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/gtd/today/*.spec.ts'`

Result:

Fail: 3 files, 81 tests, 79 passed, 2 failed (TW-1, TW-2). Baseline before the new tests: 64 passed.

## Full test execution

Command:

`cd web && npm run coverage` (full web suite, all spec files)

Result:

Fail: 139 files, 2855 tests, 2853 passed, 2 failed (TW-1, TW-2 only). Also `npx prettier --check` on both changed spec files and `npx ng lint --max-warnings=0`: Pass.

## .taskcheck

Result:

Not run: `sh scripts/check.sh` runs `npm run check` for web, whose test step fails on TW-1/TW-2 as shown above; it also runs the Gradle parts, which this helper does not run (parent runs `.taskcheck`).

## Bugs found

### Loading note never ends when the page is shown again during the first load and that reload fails

Finding ID:

`TW-1`

Test:

`TodayHabits > ends the loading note with a retry when a reload on return fails during the first load`

Production source:

`web/src/app/features/gtd/today/today-habits.ts:55-57` (visibility handler) and `:63-75` (`load`)

Requirement:

Criterion (1): the Today view shows the habits due today; a load must end in the habits or an error with a retry (habits view reference: `HabitsPage.refresh()` does not reload quietly while a load is pending and does a full `load()` after a load error).

Expected behavior:

When the page becomes visible while the first (non-quiet) load is still pending, the section ends either with the habits or with "Today's habits could not be loaded." and the retry button.

Actual behavior:

The visibility handler starts `load(true)` regardless of the loading state; it increments `seq`, so the first load's successful answer is dropped, and the quiet reload's failure is ignored (`quietly`). `habits` stays `null` and "Loading habits…" stays on screen forever with no retry (only a later visibility change can recover it).

Severity:

Medium

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

### A successful reload on return keeps the load error after a failed first load

Finding ID:

`TW-2`

Test:

`TodayHabits > shows the habits when a reload on return succeeds after a failed load`

Production source:

`web/src/app/features/gtd/today/today-habits.ts:63-75` (`load(true)` never clears `failed`), `today-habits.html:12` (`failed()` is checked before the list)

Requirement:

Criterion (1); the reload on return exists so the view recovers and is current (production comment line 53).

Expected behavior:

After a failed load, returning to the page reloads; when that reload succeeds the habits are shown and the load error disappears.

Actual behavior:

The quiet reload sets `habits` but leaves `failed` true, so the section keeps showing "Today's habits could not be loaded." with the retry button although the habits were loaded.

Severity:

Low

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Coverage

Status: partial-failed. The full coverage run (`npm run coverage`) exits nonzero because of TW-1/TW-2 and writes no coverage (`web/coverage/web/` absent afterwards). The numbers below come from a diagnostic run of the full suite that deselected only those two failing tests with `--filter`; they are diagnostic, not a passing validation, and describe the pre-fix revision.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not separately measured. `today-habits.ts` and `today-habits.html` are new files (every line is a changed line, so whole-file equals changed-line for them); in `today.routes.ts` the task added the import and the `providers` line, and the file's 2/2 statements are covered.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-today-habits`; base `1c092335a2909e97cec38e57d680049129ca72ef` (from parent); HEAD `a6613d25eb3780a079810997efc0c60cbddf3e5a`; task commits `12badc077` (assumption only) and `a6613d25e` (code; same code diff as parent-given `553be17ef`); file scope from `git diff --stat 1c092335a HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `a6613d25e` plus uncommitted spec files, sha256 `today-habits.spec.ts` f52d4d34d72fb9dd42831fa30097f897a07125f65c013791650f9cc1b706e5c2, `today-view.spec.ts` 991e68977043901b31476875269996d8b50de6919fc792601aa7e7cc444388c6; production `today-habits.ts` bb631f77959ad36082ad092bc4d5cc5ad90880899a41b3a4d38f99e8b7172c65, `today-habits.html` 0956d7add932aeeeb7fa5bec0a986f61dcdf64b7fac9b05aaf33199e8d02bacb, `today.routes.ts` 852d57d1af04e486d49c579ec9691dbea7fd655a537d28ad879aa3726184a58f (identical before and after the run).
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/features/gtd/today/today-habits.ts`, `today-habits.html`, `today.routes.ts`. Excluded `today-habits.scss` (stylesheet, no executable behavior), `today-habits.spec.ts` (test), `tasks/web-today-habits.md` (task metadata). No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + `@vitest/coverage-v8` 5.0.5 (Node 24.21.0); `npm run coverage -- '--filter=^(?!.*(ends the loading note with a retry|reload on return succeeds after a failed load))'` in `web/`; all 139 spec files, 2853 tests run, 2 deselected (TW-1, TW-2).
- Run start/completion time and exit status: started 2026-10-22 03:55:31 +0200, reports written 03:56:17 +0200; 139 files passed, 2853 passed, 2 skipped by the filter. The unfiltered `npm run coverage` (just before) failed with 2 failed tests and produced no report.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 69f27cc4ef12b0aabde8d015b275c4d7d194775130dbaef6a99e6ccbc1e4a054, `web/coverage/web/coverage-summary.json` sha256 f5a72e820002b9e2f0c228b448debae8bc1394b131234e25339f02c73cd28351.
- Evidence outputs are fresh and inputs stayed unchanged: yes. `web/coverage/web/` did not exist before the diagnostic run (the failed run wrote nothing); input hashes were the same before and after.
- Final run after latest test additions and production fixes: yes for the test additions; no production fixes yet (TW-1/TW-2 open). Needs a fresh, unfiltered `npm run coverage` after the fix.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/gtd/today/today-habits.ts` | 74/75 (98.7%) | 44/49 (89.8%) | 22/22 (100%) | 56/56 (100%) |
| `web/src/app/features/gtd/today/today-habits.html` | 80/80 (100%) | 16/16 (100%) | 2/2 (100%) | 48/48 (100%) |
| `web/src/app/features/gtd/today/today.routes.ts` | 2/2 (100%) | N/A (0/0) | 1/1 (100%) | 2/2 (100%) |
| Aggregate (summed counters, 3 measured files; `.scss` excluded, none unmeasured) | 156/157 (99.4%) | 60/65 (92.3%) | 25/25 (100%) | 106/106 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `today-habits.ts:79` (statement + branch 0: `if (this.busy().has(habit.id)) return;`) | Double tap sends one check | Covered behaviorally by `sends one check for a double tap`: the button is disabled while busy, so the guard is not reachable from the UI. Justified gap. |
| `today-habits.ts:87` branch 1, `:92` branch 1 (`?? null` when `habits` is null) | Defensive: a check can only start from a rendered row, i.e. when habits are loaded, and nothing resets them to null during a check except a retry, which is only shown in the failed state | Not reachable through the UI. Justified gap. |
| `today-habits.ts:132` branch 1 (`message()` for a non-`ApiError`) | Generic "Something went wrong." | The HTTP layer turns every HTTP failure into `ApiError`; only a programming error would reach it. Justified gap. |
| `today-habits.ts:28` branch 0 | Compiler-generated branch at the class declaration (decorator), no behavior | Justified gap. |

## Unable to test

- Full `npm run check` and `.taskcheck` were not run to a passing result: they would fail on TW-1/TW-2; the parent should run them after the fix.
- Real browser behavior of `visibilitychange` (tab switch, phone unlock) is simulated by dispatching the event with a mocked `document.visibilityState`; no Playwright run (not named by the criteria).

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion.
