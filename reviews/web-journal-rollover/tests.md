# Test Report

Date: 2026-10-26

Task: web-journal-rollover - Journal: Today rolls over at midnight, prompt before dropping conflicting edits

Scope: second round on the final code. Base `201761c39b9bc14189a34aca560d03e0e32f3ba0`..HEAD
`b214899447c0e5b1d1ac49e88755847b142a94d6` on `main` (base and commit list supplied by the parent
worker, checked with `git log --oneline 201761c39..HEAD`). Task-owned commits: `0d0177b0d`
(feat(web): journal rolls over at midnight, asks before leaving a conflict, ignores stale heatmaps),
`20b4aaa92` (tests and review reports of round 1), `b21489944` (fixer: CR-1 "Save my version" join,
CR-2 comment). Production files changed: `web/src/app/features/journal/{journal-page.ts,
journal.routes.ts, journal-dates.ts, leave-dialog.ts (new)}`. This round focused on the fixer's change
to `journal-page.ts` (`keeping` / `keepMine` / `saveMine` / `canLeave`). The new tests of this round are
uncommitted working-tree changes in `journal-page.spec.ts` on top of HEAD.

Requirements:
- `tasks/web-journal-rollover.md` acceptance criteria and its recorded assumptions
- `reviews/web-journal-rollover/review.md` (CR-1, CR-2) and `reviews/web-journal-rollover/fixes.md`
- `AGENTS.md`, `docs/ARCHITECTURE.md` ("Coverage"), existing spec conventions

## Test files changed

Round 1 (committed in `20b4aaa92`):
- `web/src/app/features/journal/leave-dialog.spec.ts` (new)
- `web/src/app/features/journal/journal.routes.spec.ts`
- `web/src/app/features/journal/journal-page.spec.ts`

Fixer (committed in `b21489944`): `web/src/app/features/journal/journal-page.spec.ts` (2 tests).

Round 2 (this round, uncommitted):
- `web/src/app/features/journal/journal-page.spec.ts`

## Tests added or updated

Count this round: 4 new tests in `journal-page.spec.ts` (`leaving a conflict` describe). Round 1 added
22; the fixer added 2. No existing test was changed, weakened or skipped.

New this round, for the fixer's `keeping` logic:
- `stays when asked to while "Save my version" from the banner runs, and lets it finish`: 'stay' does
  not wait for the running save; the save still completes (If-Match "3"), the user stays, state Saved.
- `sends one reload and one save when "Save my version" is clicked twice while it runs`: a second
  banner click joins the running one (one GET, one PUT).
- `starts a new "Save my version" after the previous one ended in a conflict again`: the finished
  attempt is cleared, so a new click reloads and saves again (If-Match "5"). Guards against `keeping`
  never being reset, which would make the button do nothing.
- `still joins "Save my version" of the next day when the old day's one ends late`: a keep-mine of the
  24th that ends after the 22nd's keep-mine started must not clear the 22nd's `keeping`; leaving the
  22nd with "Save my version" then waits for it (no second GET, one PUT). Covers the false branch of
  `if (this.keeping?.done === done)` (`journal-page.ts:291`), the CR-1 bug in its cross-day form.

These tests encode behavior; they were not mutation-checked by editing production code (not
permitted for the test-writer). By inspection: without the join, test 2 sees two GETs; without the
reset, test 3 sees no GET; with an unconditional reset, test 4 sees a second GET of the 22nd.

## Requirement coverage

All tests below ran with `cd web && npx ng test --watch=false --include='src/app/features/journal/**/*.spec.ts'`
(6 files, 138 tests passed) and again in the full web suite (`cd web && npm run coverage`, 176 files,
3713 tests passed). Tests marked "(existing)" were written by the implementer; "(fixer)" by the fixer.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: /journal moves to the new day after midnight in the user's time zone, saving the old day first, heatmap includes it | (existing) `midnight > moves Today on to the new day, saving the old day first, ...`, `checks the day when the tab shows again`, `keeps a day opened by its date ...`, `keeps a day in conflict under its date ...`, `checks again a minute later when the heatmap fails`, `journal dates > dayIn > *`; round 1: `midnight > stays on the old day while the server still says so, and asks again a minute later`, `keeps a day saved while the new heatmap loads in that heatmap`, `stops checking the day once the journal is left`, `does not move on when the journal was left while the new heatmap loaded` | focused command above | Pass |
| AC2: leaving a day whose edits are in conflict asks keep mine / discard / stay | (existing) `leaving a conflict > *` (stay, discard, keep-mine, keep-mine fails, leaving the journal, no conflict); round 1: `LeavePrompt > *` (8), `journal routes > asks the page before leaving a day`, `leaveDay > *` (4), `leaving a conflict > does not ask when the same day is opened by its other URL` (2), `saves nothing and stays when the conflict was resolved while asking`; (fixer) `waits for "Save my version" from the banner instead of saving again`, `stays when "Save my version" from the banner ends in a conflict again`; round 2: the 4 tests listed above | focused command above | Pass |
| AC3: a heatmap response arriving after a newer load does not overwrite the newer heatmap/today (CR-3) | (existing) `navigation > opens only the day chosen last when the route changes before the first heatmap arrived`; round 1: `midnight > shows only the newest heatmap when two day checks overlap and the newer answers first`, `ignores the older of two overlapping day checks when it answers first`, `keeps a day saved while the new heatmap loads in that heatmap` | focused command above | Pass |
| AC4: Vitest specs cover all three; `cd web && npm run check` passes | all of the above | `cd web && npm run coverage` (same Vitest suite as `npm run check`, plus coverage); `npx eslint src/app/features/journal`; `npx prettier --check src/app/features/journal` | Pass (see note on `npm run check` below) |

`npm run check` / `.taskcheck` were not re-run in this round; the parent worker runs `.taskcheck`
before `done`. ESLint and Prettier on the journal folder and the whole web test suite were run.

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/journal/**/*.spec.ts'`

Result:

Pass (6 test files, 138 tests; 134 after the fixer, 4 added this round).

`cd web && npx eslint src/app/features/journal` exit 0; `cd web && npx prettier --check src/app/features/journal`
"All matched files use Prettier code style!".

## Full test execution

Command:

`cd web && npm run coverage` (the whole web Vitest suite with coverage)

Result:

Pass (176 test files, 3713 tests), exit 0, on the final run (10:33:21-10:34:25).

Note: two earlier runs of this command in this round (10:28 and 10:30, before the 4th new test) each
had one failure, `src/app/app.spec.ts > App > builds every navigation section, so none shows the
coming soon placeholder`: "Test timed out in 5000ms" (5177 ms). The machine was heavily loaded by
other agents at the time (load average 25.9 on 16 cores). The same spec passed alone with coverage
(`npx ng test --watch=false --coverage --include='src/app/app.spec.ts'`, 43 passed) and in the next
two full runs (10:31 and 10:33, exit 0). It is not in the task's files and does not depend on the
spec changes of this round. Treated as a load-dependent flaky timeout, not a task defect. The parent
may want to watch for it in `.taskcheck`.

## .taskcheck

Result:

Not run in this round (the parent worker runs `sh scripts/check.sh` before `done`). Round 1 ran it
with exit 0 on the pre-fix revision.

## Bugs found

None. No new or existing test failed against the final production code.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: 78/78 (100%) executable added/modified lines covered. Method: `git diff -U0
201761c39..HEAD` on the four production files (added-line numbers at HEAD, which equals the working
tree for production files), intersected with the provider's `DA:` records in
`web/coverage/web/lcov.info`; lines without a `DA` record are non-executable and not counted. Per
file: `journal-page.ts` 45/45, `journal.routes.ts` 4/4, `journal-dates.ts` 7/7, `leave-dialog.ts`
22/22. Changed-branch coverage was not measured separately.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-journal-rollover`;
  base `201761c39b9bc14189a34aca560d03e0e32f3ba0`; HEAD `b214899447c0e5b1d1ac49e88755847b142a94d6`;
  commits `0d0177b0d`, `20b4aaa92`, `b21489944` from the parent worker, confirmed by
  `git log --oneline 201761c39..HEAD` and `git diff --stat 201761c39..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `b21489944` plus the
  uncommitted `journal-page.spec.ts` (sha256 d0bf3e239772...). Production sha256: `journal-page.ts`
  4c175a145662..., `journal.routes.ts` c7a0e84b8b86..., `journal-dates.ts` 42979e6556d3...,
  `leave-dialog.ts` 87b2a7d9786a...; other specs unchanged from HEAD (`journal.routes.spec.ts`
  41c41ebc49f3..., `leave-dialog.spec.ts` 2032cfe5e0d9..., `journal-dates.spec.ts` 481bc8e7cd36...).
  Hashes of all files in `web/src/app/features/journal/` were identical before and after the run.
- Exact included files; renamed/deleted/excluded files and reasons: included
  `web/src/app/features/journal/journal-page.ts`, `journal.routes.ts`, `journal-dates.ts`,
  `leave-dialog.ts`. Excluded: `*.spec.ts` (tests), `reviews/**` and `tasks/**` (reports, task notes).
  No renamed or deleted files. All four files present in the report; none missing.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with
  `@vitest/coverage-v8` 5.0.5; `npm run coverage` in `web/`; whole web suite. Counters extracted from
  `coverage-summary.json` / `coverage-final.json` by a scratch script (`.tmp/cov.mjs`, Git-ignored).
- Run start/completion time and exit status: 2026-10-26T10:33:21+01:00 to 10:34:25+01:00, exit 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json`
  (sha256 33587b5dc3ac..., written 10:34:24), `web/coverage/web/coverage-final.json` (sha256
  925e698be8ab..., written 10:34:24), `web/coverage/web/lcov.info` (sha256 b3193875f781...).
- Evidence outputs are fresh and inputs stayed unchanged: yes. Vitest rewrote `coverage/` at the end
  of this run and the source/test hashes did not change during it.
- Final run after latest test additions and production fixes: yes (after the fixer's commit
  `b21489944` and the last test added this round; no production changes since).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/journal/journal-page.ts` | 310/314 (98.72%) | 192/201 (95.52%) | 58/58 (100%) | 247/248 (99.59%) |
| `web/src/app/features/journal/journal.routes.ts` | 12/12 (100%) | 6/6 (100%) | 3/3 (100%) | 9/9 (100%) |
| `web/src/app/features/journal/journal-dates.ts` | 47/47 (100%) | 37/37 (100%) | 10/10 (100%) | 42/42 (100%) |
| `web/src/app/features/journal/leave-dialog.ts` | 32/32 (100%) | 15/16 (93.75%) | 2/2 (100%) | 22/22 (100%) |
| **Aggregate (all 4 files, summed counters, none missing)** | 401/405 (99.01%) | 250/260 (96.15%) | 73/73 (100%) | 320/321 (99.69%) |

Before the 4th test of this round (run 10:31:31-10:32:25, exit 0), `journal-page.ts` branches were
191/201: the false branch of `if (this.keeping?.done === done)` (line 291) was uncovered; the new
cross-day test covers it.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `journal-page.ts:291` (false branch of `if (this.keeping?.done === done)`) | CR-1 fix: an older day's "Save my version" ending late must not clear the newer day's | Covered now: `still joins "Save my version" of the next day when the old day's one ends late` |
| `journal-page.ts:282` (false branch of `if (this.discardSeq === seq)` in `useTheirs` finally) | Task code: a "Discard my edits" reload of an older day ending must not clear the flag of a newer discard. Needs a conflict on a second day while the first discard is still loading. | Not added: a rare race; the main discard flag path is covered by `ignores the entry "use-theirs" reloads ...`. Low risk. Same gap as round 1 (was line 277). |
| `journal-page.ts:172` (`?? ''` fallback) | Not task code; unreachable fallback for the type checker. | Not material. |
| `journal-page.ts:195` (`step` beyond today) | Not task code; the Next button is disabled on today. | Not material for this task. |
| `journal-page.ts:255` (flush with no draft while in `error`) | Not task code. | Out of scope. |
| `journal-page.ts:453, 457, 458, 462` (`persist` answer after another day was opened) | Not task code (pre-existing save paths). | Out of scope. |
| `journal-page.ts:525` (`sameDraft` short-circuit operands) | Not task code. | Out of scope. |
| `leave-dialog.ts:52` (one `if` branch at `export class LeaveDialog {}`) | No source branch: an Angular-compiler artifact of the component decorator. | Not material. |

## Unable to test

- Midnight behavior in a real browser (real clock, a real tab hidden over midnight): not run. The
  tests fake `Date` and `setInterval` and dispatch `visibilitychange` on `document`; AC1 does not
  name a browser/E2E run. No follow-up needed for the acceptance criteria.
- Mutation check of the fixer's change (temporarily breaking production code to see the new tests
  fail): not done, because the test-writer must not modify production code. The fixer reports that
  it did this for its two tests.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production
defects found. The fixer's "Save my version" join is covered for joining from the leave prompt and
from a second banner click, for 'stay' while it runs, for a new attempt after a finished one, and for
the cross-day race. Changed lines are fully covered (78/78); the only uncovered task branch
(`useTheirs` discard-flag race, `journal-page.ts:282`) is a justified low-risk gap. One unrelated
load-dependent timeout in `app.spec.ts` was seen in two of four full runs.
