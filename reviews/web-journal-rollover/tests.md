# Test Report

Date: 2026-10-26

Task: web-journal-rollover - Journal: Today rolls over at midnight, prompt before dropping conflicting edits

Scope: base `e6fe2984bfdb907ad2fc2392898e4d735129cbfb`..HEAD `70f57d663e8d5043875cb5a37f8cbad5ad603163`
on `main`. Task-owned commits (supplied by the parent worker, checked with `git log`):
`70f57d663` (feat(web): journal rolls over at midnight, asks before leaving a conflict, ignores stale
heatmaps) plus `e9bcada61`, `73a7bdf13`, `a0c51e05b` (assumption notes in `tasks/` only). Production
files changed: `web/src/app/features/journal/{journal-page.ts, journal.routes.ts, journal-dates.ts,
leave-dialog.ts (new)}`. The tests below are uncommitted working-tree changes on top of HEAD.

Requirements:
- `tasks/web-journal-rollover.md` acceptance criteria and its three recorded assumptions
- `reviews/web-journal/review.md` findings CR-1, CR-2, CR-3 (background)
- `AGENTS.md`, `docs/ARCHITECTURE.md` ("Coverage"), existing spec conventions
  (`web/src/app/features/calendar/edit/scope-dialog.spec.ts` for dialogs)

## Test files changed

- `web/src/app/features/journal/leave-dialog.spec.ts` (new)
- `web/src/app/features/journal/journal.routes.spec.ts`
- `web/src/app/features/journal/journal-page.spec.ts`

## Tests added or updated

Count: 22 (8 in `leave-dialog.spec.ts`, 5 in `journal.routes.spec.ts`, 9 in `journal-page.spec.ts`).
No existing test was changed, weakened or skipped.

## Requirement coverage

All tests below ran with `cd web && npx ng test --watch=false --include='src/app/features/journal/**/*.spec.ts'`
(6 files, 132 tests passed) and again in the full suite (`npm run coverage`, `sh scripts/check.sh`).
Tests marked "(existing)" were written by the implementer; the others are new.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: /journal moves to the new day after midnight in the user's time zone, saving the old day first, heatmap includes it | (existing) `midnight > moves Today on to the new day, saving the old day first, ...`, `checks the day when the tab shows again`, `keeps a day opened by its date ...`, `keeps a day in conflict under its date ...`, `checks again a minute later when the heatmap fails`, `journal dates > dayIn > *`; new: `midnight > stays on the old day while the server still says so, and asks again a minute later`, `keeps a day saved while the new heatmap loads in that heatmap`, `stops checking the day once the journal is left`, `does not move on when the journal was left while the new heatmap loaded` | focused command above | Pass |
| AC2: leaving a day whose edits are in conflict asks keep mine / discard / stay | (existing) `leaving a conflict > *` (stay, discard, keep-mine, keep-mine fails, leaving the journal, no conflict); new: `LeavePrompt > opens the leave dialog and resolves the choice`, `stays when the dialog closes without a choice`, `with the real dialog > explains the conflict and offers stay, discard and keep mine, in that order`, `resolves 'stay'/'discard'/'keep-mine' when its button is clicked` (3), `stays when the dialog is dismissed with Escape`, `stays when the backdrop is clicked`; `journal routes > asks the page before leaving a day`, `leaveDay > passes the date of another journal day, and returns the answer of the page`, `passes null for today (/journal)`, `passes undefined for another page, even with a date param of its own`, `looks at the deepest route only, and passes undefined for a root without a config`; `leaving a conflict > does not ask when the same day is opened by its other URL` (2 directions), `saves nothing and stays when the conflict was resolved while asking` | focused command above | Pass |
| AC3: a heatmap response arriving after a newer load does not overwrite the newer heatmap/today (CR-3) | (existing) `navigation > opens only the day chosen last when the route changes before the first heatmap arrived`; new: `midnight > shows only the newest heatmap when two day checks overlap and the newer answers first`, `ignores the older of two overlapping day checks when it answers first`, `keeps a day saved while the new heatmap loads in that heatmap` (savedSince re-apply) | focused command above | Pass |
| AC4: Vitest specs cover all three; `cd web && npm run check` passes | all of the above; `npm run check` ran as the web part of `.taskcheck` | `sh scripts/check.sh` (from `web/`: `sh ../scripts/check.sh`) | Pass |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/journal/**/*.spec.ts'`

Result:

Pass (6 test files, 132 tests; baseline before the new tests: 5 files, 110 tests).

## Full test execution

Command:

`cd web && npm run coverage` (the whole web Vitest suite with coverage)

Result:

Pass (176 test files, 3700 tests), exit 0.

## .taskcheck

Result:

Pass. `sh scripts/check.sh` (all parts: tfp gate tests, server `./gradlew check`, web `npm run check`
incl. ESLint, tests and build, android ktlint + unit tests + lint) exit 0, ending with
"== all checks passed". `npx prettier --check src/app/features/journal/` clean after formatting the
new spec.

## Bugs found

None. No new or existing test failed against the production code.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: 69/69 (100%) executable added/modified lines covered. Method: `git diff -U0
e6fe2984b..70f57d663` on the four production files, intersected with the provider's `DA:` line
records in `web/coverage/web/lcov.info` (lines without a `DA` record are non-executable and not
counted). Per file: `journal-page.ts` 36/36, `journal.routes.ts` 4/4, `journal-dates.ts` 7/7,
`leave-dialog.ts` 22/22. Changed-branch coverage was not measured separately.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-journal-rollover`;
  base `e6fe2984bfdb907ad2fc2392898e4d735129cbfb`; HEAD `70f57d663e8d5043875cb5a37f8cbad5ad603163`;
  commits and file list from the parent worker, confirmed by `git log --oneline e6fe2984b..HEAD` and
  `git diff --stat e6fe2984b..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `70f57d663` plus uncommitted
  specs, sha256: `journal-page.spec.ts` 88aa7d6c0df0..., `journal.routes.spec.ts` 41c41ebc49f3...,
  `leave-dialog.spec.ts` 2032cfe5e0d9... (new); production: `journal-page.ts` 4bde4640a0b0...,
  `journal.routes.ts` c7a0e84b8b86..., `journal-dates.ts` 42979e6556d3..., `leave-dialog.ts`
  87b2a7d9786a... (hashes of all files in `web/src/app/features/journal/` taken before and after
  the run were identical).
- Exact included files; renamed/deleted/excluded files and reasons: included
  `web/src/app/features/journal/journal-page.ts`, `journal.routes.ts`, `journal-dates.ts`,
  `leave-dialog.ts`. Excluded: `*.spec.ts` (tests), `tasks/web-journal-rollover.md` (task notes).
  No renamed or deleted files.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with
  `@vitest/coverage-v8` ^5.0.5; `npm run coverage` in `web/`; whole web suite.
- Run start/completion time and exit status: 2026-10-26T10:21:17+01:00 to 10:22:23+01:00, exit 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json`
  (sha256 6f7267872d5e...), `web/coverage/web/coverage-final.json` (sha256 e08a6418b5a7..., written
  10:22:23), `web/coverage/web/lcov.info` (sha256 12610fa1fe05...).
- Evidence outputs are fresh and inputs stayed unchanged: yes. Vitest rewrote `coverage/`
  (coverage-final.json timestamp is the end of this run) and the source/test hashes did not change
  during the run.
- Final run after latest test additions and production fixes: yes (after the last test change, a
  Prettier reformat of `leave-dialog.spec.ts`; no production changes).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/journal/journal-page.ts` | 298/302 (98.67%) | 184/193 (95.33%) | 56/56 (100%) | 238/239 (99.58%) |
| `web/src/app/features/journal/journal.routes.ts` | 12/12 (100%) | 6/6 (100%) | 3/3 (100%) | 9/9 (100%) |
| `web/src/app/features/journal/journal-dates.ts` | 47/47 (100%) | 37/37 (100%) | 10/10 (100%) | 42/42 (100%) |
| `web/src/app/features/journal/leave-dialog.ts` | 32/32 (100%) | 15/16 (93.75%) | 2/2 (100%) | 22/22 (100%) |
| **Aggregate (all 4 files, summed counters, none missing)** | 389/393 (98.98%) | 242/252 (96.03%) | 71/71 (100%) | 311/312 (99.68%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `journal-page.ts:277` (false branch of `if (this.discardSeq === seq)` in `useTheirs` finally) | Task code: a "Discard my edits" reload of an older day ending must not clear the flag of a newer discard. Needs a conflict on a second day while the first discard is still loading. | Not added: a rare race; the main discard flag path (no prompt after "Discard my edits") is covered by the existing `ignores the entry "use-theirs" reloads ...` tests. Low risk. |
| `journal-page.ts:167` (`?? ''` fallback after `!this.today()` returned) | Not task code; unreachable fallback for the type checker. | Not material. |
| `journal-page.ts:190` (`step` beyond today) | Not task code; the Next button is disabled on today. | Not material for this task. |
| `journal-page.ts:250` (flush with no draft while in `error`) | Not task code. | Out of scope. |
| `journal-page.ts:434, 439, 443` (`persist` answer after another day was opened) | Not task code (pre-existing save paths). | Out of scope. |
| `journal-page.ts:506` (`sameDraft` short-circuit operands) | Not task code. | Out of scope. |
| `leave-dialog.ts:52` (one `if` branch at `export class LeaveDialog {}`) | No source branch: an Angular-compiler artifact of the component decorator. | Not material. |

## Unable to test

- Midnight behavior in a real browser (real clock, a real tab hidden over midnight): not run. The
  tests fake `Date` and `setInterval` and dispatch `visibilitychange` on `document`; AC1 does not
  name a browser/E2E run. No follow-up needed for the acceptance criteria.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production
defects found. Changed lines are fully covered (69/69); the only uncovered task branch (`useTheirs`
discard-flag race, `journal-page.ts:277`) is a justified low-risk gap.
