# Test Report

Date: 2026-10-26

Task: web-habits-shared-labels - Share habit check label/error helpers between Habits view and Today

Scope: base 9aa3e939377d854f140c726067645ac97e875d0b (merge-base with origin/main, supplied by the
parent) .. HEAD 0041235a9ee1b2851dc90fac2e0852419e7e5170 (the single task commit, branch `main`),
plus the uncommitted test changes listed below. Production files changed by the task:
`web/src/app/features/habits/habit-display.ts`, `web/src/app/features/habits/habits-page.ts`,
`web/src/app/features/gtd/today/today-habits.ts`. No production file is uncommitted.

Requirements:
- `tasks/web-habits-shared-labels.md` acceptance criteria
- `reviews/web-today-habits/review.md` CR-3 (origin of the task), referenced by the task notes
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`

## Test files changed

- `web/src/app/features/habits/habit-display.spec.ts` (uncommitted additions from an earlier,
  interrupted test-writer session; reviewed and kept unchanged)
- `web/src/app/features/gtd/today/today-habits.spec.ts` (one test added in this session)

## Tests added or updated

Count: 6

- `habit-display.spec.ts` / `checkLabel`: "offers to check a once-a-day habit not done today",
  "offers to uncheck a once-a-day habit done today", "reads the count of today against a target
  above 1" (0, 2, 3 of 3; smallest target 2; a count above a lowered target).
- `habit-display.spec.ts` / `errorMessage`: "shows the detail of an API error, else its title",
  "shows a generic message for anything that is not an API error" (Error, string, undefined, null,
  plain object).
- `today-habits.spec.ts`: "shows a generic message for a failed check that is not an API error"
  (Today previously had no component test of the non-ApiError branch; the Habits view already had
  one, `habits-page.spec.ts` "shows a generic message for a failure that is not an API error").

Review of the earlier session's additions: they test observable output of the exported helpers
with fictitious data, use the existing `habit()` fixture, and match the wording the component specs
already assert through `aria-label`. No change was needed.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: one shared check-label implementation, used by both components | `checkLabel` unit tests (3) in `habit-display.spec.ts`; component `aria-label` assertions in `habits-page.spec.ts` ("checks a habit...", "unchecks...", target-above-1 tests) and `today-habits.spec.ts` ("shows only the habits due today...", "checks a habit with one tap", "unchecks a done habit with the next tap"). Sharing itself verified by inspecting commit 0041235a: both components bind `protected readonly checkLabel = checkLabel` imported from `habit-display.ts`; the duplicates are deleted. | `cd web && npx ng test --watch=false --include=src/app/features/habits/habit-display.spec.ts --include=src/app/features/habits/habits-page.spec.ts --include=src/app/features/gtd/today/today-habits.spec.ts` | Pass (3 files, 89 tests) |
| AC1: one shared error-message implementation, used by both components | `errorMessage` unit tests (2) in `habit-display.spec.ts`; `habits-page.spec.ts` "shows a generic message for a failure that is not an API error" plus API-detail cases; `today-habits.spec.ts` "shows a generic message for a failed check that is not an API error" (new) plus 409/412/400 detail/title cases. Sharing verified by inspection: both components call `errorMessage` from `habit-display.ts`; local `message()` functions are removed. | same as above | Pass |
| AC2: existing Vitest tests of both components still pass | all of `habits-page.spec.ts`, `today-habits.spec.ts` | same as above | Pass |
| AC2: `cd web && npm run check` passes | whole web suite, lint, api check, e2e typecheck, build | `npm --prefix web run check` (equivalent to `cd web && npm run check`) | Pass (lint clean, 175 files / 3671 tests passed, build completed; the pre-existing initial-bundle budget warning is a warning, not an error) |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include=src/app/features/habits/habit-display.spec.ts --include=src/app/features/habits/habits-page.spec.ts --include=src/app/features/gtd/today/today-habits.spec.ts`

Result:

Pass - 3 test files, 89 tests (88 before the Today test was added, also passing).

## Full test execution

Command:

`npm --prefix web run check` (api:check, lint `--max-warnings=0`, e2e:typecheck, test:ci, ng build)

Result:

Pass - run twice (before and after adding the Today test); final run: "All files pass linting",
Test Files 175 passed, Tests 3671 passed, build output written to `web/dist/web`.

## .taskcheck

Result:

See the line appended below once `sh scripts/check.sh` finished.

## Bugs found

None. No test exposed a production defect.

## Coverage

Status: verified (whole-file coverage of the three task-changed production files, fresh run after
the last test change, inputs unchanged during the run).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured by intersecting the task diff hunks
(`git diff -U0 9aa3e939..0041235a -- web/src`) with the provider's per-file line data: every
executable added/modified line (habit-display.ts 38-56; habits-page.ts 106, 161, 200, 251;
today-habits.ts 113, 137) is covered; all three files report 100% line coverage, so no changed
executable line is uncovered. Changed-branch coverage: the only new branches are in `checkLabel`
and `errorMessage` (habit-display.ts 38-56), and none of the uncovered branches listed below lies
in those lines.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-habits-shared-labels`; base `9aa3e939377d854f140c726067645ac97e875d0b`; HEAD `0041235a9ee1b2851dc90fac2e0852419e7e5170`; task commit and base supplied by the parent and confirmed with `git diff --name-only 9aa3e939..HEAD -- server/src/main web/src android/app/src/main` (3 files, matching the task `touches`).
- Source/test snapshot identifier (including uncommitted inputs): HEAD 0041235a plus uncommitted test files, sha256: `habit-display.spec.ts` 4e42e5181615eb083fbc685f6aa5a051e3407bda95a12c0d0f9c0c209467f729, `today-habits.spec.ts` ca968a6273b61e702de0ddc40df726f15f27b7735067deba10423fcf6f8ccf93; production files (committed, unchanged in the working tree): `habit-display.ts` 7dbaeb85..., `habits-page.ts` aa5f47a3..., `today-habits.ts` 1e67fc7d...
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/features/habits/habit-display.ts`, `web/src/app/features/habits/habits-page.ts`, `web/src/app/features/gtd/today/today-habits.ts`; no renames, deletions or exclusions; no server/android files changed.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + `@vitest/coverage-v8` 5.0.5 (Node 24.22.1); `npm --prefix web run coverage` (the `coverage` script of `web/package.json`, run in `web/`); full web suite.
- Run start/completion time and exit status: 2026-10-26T10:25:20+01:00 to 10:27:47+01:00; 175 test files / 3671 tests passed, coverage summary printed (exit 0).
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 0da9dadf410b11ea9fdf3e2de0a3b6173bf7f331760ec200e73ab88fa2e2029f, `web/coverage/web/coverage-summary.json` sha256 74e3b9e2891c0143c3b58b72fbae2798af2690b5ccf0f7d7b75060c95667f5cc (both written 10:27:46).
- Evidence outputs are fresh and inputs stayed unchanged: report files were rewritten at 10:27:46, after the run start; the sha256 of all five input files was identical before and after the run.
- Final run after latest test additions and production fixes: yes (no production fixes were made; this run includes the new Today test).

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/habits/habit-display.ts` | 40/40 (100%) | 46/47 (97.87%) | 15/15 (100%) | 37/37 (100%) |
| `web/src/app/features/habits/habits-page.ts` | 144/147 (97.95%) | 76/87 (87.35%) | 42/42 (100%) | 113/113 (100%) |
| `web/src/app/features/gtd/today/today-habits.ts` | 74/75 (98.66%) | 43/47 (91.48%) | 21/21 (100%) | 55/55 (100%) |
| **Aggregate (summed counters, all 3 scoped files measured, none excluded)** | 258/262 (98.47%) | 165/181 (91.16%) | 78/78 (100%) | 205/205 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `habit-display.ts:62` (`habit.timesPerWeek ?? 1` fallback in `scheduleLabel`) | Pre-existing schedule label, not changed by this task | Left; outside task scope |
| `habits-page.ts:137, 139` (`save()` guards: no form open / edited habit gone) | Pre-existing defensive guards | Left; outside task scope |
| `habits-page.ts:152-153` (create while the archived view is shown; list null) | Pre-existing create flow | Left; outside task scope |
| `habits-page.ts:179-180, 203, 249` (`?? null` fallbacks on a null list; archive of the habit being edited) | Pre-existing defensive fallbacks | Left; outside task scope |
| `habits-page.ts:60, 62` (`this.habits() ?? []` fallbacks in computed lists), `:43` and `today-habits.ts:34` (class-level branch counters reported by v8 on the class declaration) | Pre-existing | Left; outside task scope |
| `today-habits.ts:98` (check while the same habit is busy), `:106, :111` (`?? null` fallbacks) | Pre-existing guards; double tap is tested ("sends one check for a double tap") via the disabled button, so the early return is not reached | Left; outside task scope |

## Unable to test

Nothing in scope. "Used by both components" is structural; it was verified by inspecting the
task commit (both components import the helpers from `habit-display.ts`, local copies removed), and
behaviorally by component tests in both specs asserting the shared wording.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no
production defects found.
