# Test Report

Date: 2026-10-22

Task: web-today-habits - Today view: habits due

Scope: second pass on the final revision. Task base `1c092335a2909e97cec38e57d680049129ca72ef` .. HEAD `97b339fa29322566c54b2e18c50450301569460d` on `main`; task commits `12badc077` (assumption), `a6613d25e` (implementation), `4145de0ae` (tests and first reports), `97b339fa2` (fix for CR-1/TW-1/TW-2: new `refresh()` on `visibilitychange`). Uncommitted: only `web/src/app/features/gtd/today/today-habits.spec.ts` (3 tests added in this pass); `reviews/web-today-habits/review.md` is also modified in the working tree, by the code-reviewer, not by this helper.

Requirements:
- `tasks/web-today-habits.md` acceptance criteria (1) habits due today with one-tap check, (2) Vitest component/service tests cover the habits section; `cd web && npm run check` passes
- Task assumption: section hidden without active habits, "No habits due today" when none is due, registered via `provideTodaySection` in `today.routes.ts` (order 10)
- SPEC.md §5.3 / §8.5 (as cited in the production code), `docs/ARCHITECTURE.md` "Coverage", parent instructions (fixed `refresh()` behavior: skip while a check runs, full `load()` after a failed load, quiet reload only with habits on screen, nothing during the first load)
- `reviews/web-today-habits/fixes.md` (fixer's replacement of the TW-1 test)
- Reference behavior: `web/src/app/features/habits/habits-page.ts` (`refresh()` on visibility return)

## Test files changed

- `web/src/app/features/gtd/today/today-habits.spec.ts` (this pass and first pass)
- `web/src/app/features/gtd/today/today-view.spec.ts` (first pass, unchanged since)

## Tests added or updated

Count: 3 new tests in this pass (21 in total over both passes, plus the fixer's 2 replacement tests for TW-1). No existing test changed in this pass.

This pass:
- `loads in full on return after a failed load, ending in the error again if it fails`: after a failed load, a return clears the error and shows the loading note (full `load()`), a second return during that load sends no request, and a failing reload ends in the error with retry again. Fails on the pre-fix code (the old quiet reload kept the error on screen and sent a second request).
- `keeps the habits and shows no load error when a reload on return fails`: the quiet reload on return ignores a failure and keeps the rows.
- `reloads on return while hidden without habits, showing a habit added meanwhile`: the empty list counts as "habits on screen", so a return reloads quietly (section stays hidden while it runs) and shows a newly added habit.

Fixer tests checked: `ends the loading note when the page is shown again during the first load` (no second request, first load's habits shown) and `ends the loading note with a retry when the first load fails after a return` correctly express the specified "nothing during the first load" behavior; `afterEach(http.verify())` also guarantees no stray request in the second one. TW-2 test `shows the habits when a reload on return succeeds after a failed load` unchanged and passing.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| (1) Today shows the habits due today (route registers the section, TodayView renders it) | `today routes > registers the habits section, so the routed Today view shows today's habits` | `cd web && npx ng test --watch=false --include='src/app/features/gtd/today/*.spec.ts'` | Pass |
| (1) Only due habits, progress, streak (days and weeks), count, hidden/empty states | `shows only the habits due today, with progress and streak`, `shows a weekly streak in weeks`, `says so when no habit is due today`, `stays hidden without any active habit` | same | Pass |
| (1) One-tap check / uncheck / count towards target | `checks a habit with one tap`, `unchecks a done habit with the next tap`, `counts up towards a target above one`, `marks a habit done once its target above one is reached` | same | Pass |
| (1) Check errors: row error, 404 drops row, 409/412 quiet reload, failed conflict reload stays quiet, other errors no reload, only last error shown | `shows a failed check next to the habit`, `drops a habit deleted meanwhile`, `shows a 409 conflict and reloads quietly, keeping the habits on screen`, `shows a 412 conflict ...`, `keeps the habits and no load error when the conflict reload fails`, `does not reload after a check that failed other than with a conflict`, `shows only the error of the last failed check` | same | Pass |
| (1) Concurrency: double tap, reverse-order checks, quiet reload vs. check both orders | `sends one check for a double tap`, `checks two habits at the same time, answered in reverse order`, `drops a quiet reload answered after a check that started meanwhile`, `drops a quiet reload answered while a check that started after it is running` | same | Pass |
| (1) `refresh()` on return: skip while a check runs | `does not reload on return while a check is running` | same | Pass |
| (1) `refresh()` on return: quiet reload with habits on screen (incl. empty list), failure keeps rows | `reloads quietly when the page becomes visible again`, `keeps the habits and shows no load error when a reload on return fails` (new), `reloads on return while hidden without habits, showing a habit added meanwhile` (new) | same | Pass |
| (1) `refresh()` on return: nothing during the first load, loading always ends | `ends the loading note when the page is shown again during the first load`, `ends the loading note with a retry when the first load fails after a return` (fixer, replacing TW-1) | same | Pass |
| (1) `refresh()` on return: full load after a failed load, error clears, nothing during that load | `shows the habits when a reload on return succeeds after a failed load` (TW-2), `loads in full on return after a failed load, ending in the error again if it fails` (new) | same | Pass |
| (1) Visibility: hidden does not reload, listener removed on destroy | `does not reload when the page is hidden`, `stops listening for visibility changes once destroyed` | same | Pass |
| (1) Loading / failed load / retry | `shows loading, then a failed load with a retry` | same | Pass |
| (2) Web tests and lint pass | whole web suite + lint | `cd web && npm run coverage` (all 139 spec files) and `cd web && npx ng lint --max-warnings=0` | Pass (2859 tests; lint clean). `npm run check` itself not run by this helper; it runs the same lint and test suite |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/gtd/today/*.spec.ts'`

Result:

Pass: 3 files, 85 tests passed (run after the final Prettier formatting, 2026-10-22 04:04:10 +0200).

## Full test execution

Command:

`cd web && npm run coverage` (full web suite, all spec files, no filters)

Result:

Pass: 139 files, 2859 tests passed, exit status 0. Also `npx prettier --write` on the changed spec (formatting only) and `cd web && npx ng lint --max-warnings=0`: all files pass linting.

## .taskcheck

Result:

Not run: `sh scripts/check.sh` also runs the Gradle parts (server, android), which this helper does not run; the parent runs `.taskcheck`. Its web part (lint + the same Vitest suite) passed above.

## Bugs found

None in this pass. TW-1 and TW-2 from the first pass are fixed in `97b339fa2`: their tests (TW-1 as replaced by the fixer, see `fixes.md`) and the new full-load test pass on the final code.

## Coverage

Status: verified (fresh, unfiltered run on the final revision plus this pass's tests, exit 0).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not separately measured. `today-habits.ts` and `today-habits.html` are new files in this task (every line is a changed line, so whole-file equals changed-line for them); in `today.routes.ts` the task added the import and the `providers` line, and the file's 2/2 statements are covered.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-today-habits`; base `1c092335a2909e97cec38e57d680049129ca72ef` (from parent); HEAD `97b339fa29322566c54b2e18c50450301569460d`; task commits `12badc077`, `a6613d25e`, `4145de0ae`, `97b339fa2`; file scope from `git diff --stat 1c092335a..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `97b339fa2` plus uncommitted `today-habits.spec.ts` sha256 1f687008ba4f6545bf47b4d94c4fe4a3e6751e9d872146cd4c5e69a38034d916; `today-view.spec.ts` 991e68977043901b31476875269996d8b50de6919fc792601aa7e7cc444388c6; production `today-habits.ts` 07602f5e2d5c744f8440953312ff57d348fceabfc7e60231a3b72215dc43f730, `today-habits.html` 0956d7add932aeeeb7fa5bec0a986f61dcdf64b7fac9b05aaf33199e8d02bacb, `today.routes.ts` 852d57d1af04e486d49c579ec9691dbea7fd655a537d28ad879aa3726184a58f (same before and after the runs).
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/features/gtd/today/today-habits.ts`, `today-habits.html`, `today.routes.ts`. Excluded `today-habits.scss` (stylesheet, no executable behavior), spec files (tests), `tasks/` and `reviews/` (metadata). No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + `@vitest/coverage-v8` 5.0.5; `npm run coverage` in `web/`, no filters; all 139 spec files, 2859 tests.
- Run start/completion time and exit status: run 1 started 2026-10-22 04:02:04 +0200, reports written 04:02:44 +0200, 2859 passed; run 2 (same command, to capture the exit status) exit 0, reports written 04:03:52 +0200. Numbers below are identical in both runs.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` sha256 862d1b78e0d3b8dd94c7c21832811f045cef5500289a0599ec9bfb5171c63570 (both runs); `web/coverage/web/coverage-final.json` sha256 2b1b57305c34483b05fb7095b1ae13aafb5223f8970a1adac30e673ee0cd5579 (run 2; run 1 cfe36da4ecea52ca9ebdd5e174a41e55ba29871f23700857f2cf13a90126c8e2). Also `lcov.info`, `lcov-report/`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The first-pass diagnostic reports (summary f5a72e82..., final 69f27cc4..., written 03:56:17) were replaced: new timestamps and hashes after each run; input hashes unchanged.
- Final run after latest test additions and production fixes: yes (after the fix commit `97b339fa2` and this pass's tests).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/gtd/today/today-habits.ts` | 80/81 (98.8%) | 48/53 (90.6%) | 23/23 (100%) | 61/61 (100%) |
| `web/src/app/features/gtd/today/today-habits.html` | 80/80 (100%) | 16/16 (100%) | 2/2 (100%) | 48/48 (100%) |
| `web/src/app/features/gtd/today/today.routes.ts` | 2/2 (100%) | N/A (0/0) | 1/1 (100%) | 2/2 (100%) |
| Aggregate (summed counters, 3 measured files; `.scss` excluded, none unmeasured) | 162/163 (99.4%) | 64/69 (92.8%) | 26/26 (100%) | 111/111 (100%) |

`refresh()` (`today-habits.ts:81-88`) is fully covered, all its branches (busy, failed, habits on screen, still loading).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `today-habits.ts:92` (statement + branch 0: `if (this.busy().has(habit.id)) return;`) | Double tap sends one check | Covered behaviorally by `sends one check for a double tap`: the button is disabled while busy, so the guard is not reachable from the UI. Justified gap. |
| `today-habits.ts:100` branch 1, `:105` branch 1 (`?? null` when `habits` is null) | Defensive: a check starts only from a rendered row, and nothing resets habits to null during a check (a full load runs only from the retry or `refresh()` after a failed load, when no rows exist) | Not reachable through the UI. Justified gap. |
| `today-habits.ts:145` branch 1 (`message()` for a non-`ApiError`) | Generic "Something went wrong." | The HTTP layer turns every HTTP failure into `ApiError`; only a programming error reaches it. Justified gap. |
| `today-habits.ts:28` branch 0 | Compiler-generated branch at the class declaration (decorator), no behavior | Justified gap. |

## Unable to test

- `.taskcheck` (`sh scripts/check.sh`) not run by this helper (it includes the Gradle parts); the parent runs it.
- Real browser behavior of `visibilitychange` (tab switch, phone unlock) is simulated by dispatching the event with a mocked `document.visibilityState`; no Playwright run (not named by the criteria).

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.
