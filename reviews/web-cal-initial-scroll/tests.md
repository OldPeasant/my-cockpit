# Test Report

Date: 2026-11-22

Task: web-cal-initial-scroll - Web calendar: day/week grid opens scrolled to the earliest entry

Scope: `git diff ca2b75431396501e11b43e4d7c262a20cf2bdb3e..02d35c337287a1d6e4da801a49d25806981b57af` (task commits
b6e721bd9 "calendar day/week grid opens scrolled to the earliest entry" and 02d35c337 "unit tests for
initialScrollTime"; the other commits in the range only touch `tasks/`). Branch `main`, no uncommitted production
changes; uncommitted changes are the two spec files below (added by this test run).

Requirements:
- `tasks/web-cal-initial-scroll.md` acceptance criteria and its two recorded assumptions (timed overlay tasks count;
  only visible calendars count)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`
- Parent instructions (view-level tests with a spy on FullCalendar `scrollToTime`, `range` tests in
  `occurrence-store.spec.ts`)

## Test files changed

- `web/src/app/features/calendar/views/calendar-view.spec.ts` (new `describe('initial scroll position')`)
- `web/src/app/features/calendar/views/occurrence-store.spec.ts` (new `describe('range')`)

## Tests added or updated

Count: 16 (10 in `calendar-view.spec.ts`, 6 in `occurrence-store.spec.ts`; no existing test changed)

## Requirement coverage

Focused command (F): `cd web && npx ng test --watch=false --include 'src/app/features/calendar/views/*.spec.ts'`
-> 7 files, 162 tests passed. Also passed in the full web suite (`npm --prefix web run coverage`, 4982 tests) and in
`sh scripts/check.sh`.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: top = full hour at/before the earliest timed start in the shown period (14:20 -> 14:00), clamped | `initial-scroll.spec.ts` (pure fn, existing, includes clamping); view: `scrolls the week to the full hour before its earliest entry once the occurrences are loaded`, `scrolls again on a period change...` (11:30 CEST -> 11:00) | F | Pass |
| AC2: continuing entries, all-day entries, untimed overlay tasks ignored | `initial-scroll.spec.ts` (existing); view: `scrolls to 07:00 for a past week without timed entries, ignoring all-day and continuing ones`; assumption: `ignores occurrences of hidden calendars` | F | Pass |
| AC3: no timed entry -> current hour if today shown, else 07:00 | `initial-scroll.spec.ts` (existing, both branches); view: 07:00 case in the test above and in `does not scroll for an older period...` | F | Pass |
| AC4: applied on open and every period change once loaded; refresh of the same period does not move it | `scrolls the week ... once the occurrences are loaded` (no scroll before the answer), `does not move the position on a refresh of the same period` (SSE resync + event change), `scrolls again on a period change, once its occurrences are loaded`, `scrolls with the loaded entries when the same period is shown again (view switch, Today)` (week -> day), `does not scroll for an older period whose answer arrives after a newer period was shown`, `does not scroll the month view`, `does not scroll the agenda view`, `does not scroll after it was destroyed` | F | Pass |
| AC4 support: `OccurrenceStore.range` set together with the items | `range` describe: undefined before first answer / set with items; kept while next range loads; not set by a slower older answer; set without calendars; kept on failed load; unchanged after refresh | F | Pass |
| AC5: pure function with unit tests | `initial-scroll.spec.ts` (written by the implementer; 100% covered, see Coverage) | F | Pass |
| AC6: unit tests; `npm run check` and `scripts/check.sh` pass | all of the above | `sh scripts/check.sh` (runs web `npm run check` incl. api:check, lint, e2e typecheck, tests, build) | Pass |
| Real scroll in a browser (FullCalendar actually moves the grid) | none | - | Not run: unit tests spy on `scrollToTime`; no Playwright run was requested for this task and the criteria name only unit tests |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include 'src/app/features/calendar/views/*.spec.ts'`

Result:

Pass (7 files, 162 tests)

## Full test execution

Command:

`npm --prefix web run coverage` (whole web unit suite with coverage), run twice (before and after Prettier formatting of
the new spec); plus `npm --prefix web run lint` and `npx prettier --check` on the changed specs.

Result:

Pass (199 files, 4982 tests, both runs); lint clean; Prettier clean after `prettier --write` on
`calendar-view.spec.ts` (formatting of the new test block only).

## .taskcheck

Result:

Pass (`sh scripts/check.sh` -> "== all checks passed": tfp gate tests, server, web, android). The web build prints the
pre-existing bundle budget warnings (initial bundle, `calendar-view.scss`); not failures and not from this test change.

## Bugs found

None.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately; every uncovered location in the three files is outside the task's
added/modified lines (see table below), and `initial-scroll.ts` and `occurrence-store.ts` are fully covered.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-cal-initial-scroll`; base
  `ca2b75431396501e11b43e4d7c262a20cf2bdb3e` (given by the parent); HEAD `02d35c337287a1d6e4da801a49d25806981b57af`;
  task commits b6e721bd9, 02d35c337 (parent-supplied, confirmed with `git log`/`git diff --stat`)
- Source/test snapshot identifier (including uncommitted inputs): HEAD 02d35c337 plus uncommitted spec files, sha256
  `calendar-view.spec.ts` 1006300deaa5dfbe5532f768750257d7ac3022e3ebe030c6bdf4b7ec98721bb0,
  `occurrence-store.spec.ts` 9b3524edd9e79bb08d63e65b9af28e82f304b9a4424fa47f6a61b1f8eaf6f291; production
  `calendar-view.ts` fbfb838a50980a2a9110ae79ecca244f6ebc778b05d0cceb4b201f8b4b5eb87c (unchanged before/after)
- Exact included files: `web/src/app/features/calendar/views/calendar-view.ts`, `.../occurrence-store.ts`,
  `.../initial-scroll.ts`. Excluded: `initial-scroll.spec.ts` (test), `tasks/web-cal-initial-scroll.md` (task metadata).
  No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest v5.0.5 + `@vitest/coverage-v8`;
  `npm --prefix web run coverage` (= `ng test --watch=false --coverage ...`), cwd `web/`, whole web unit suite
- Run start/completion time and exit status: final run started 2026-11-22T09:44:05Z (build) / 10:44:05 local, duration
  57.9 s, exit 0, 4982 tests passed
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` (sha256
  05a58f46...901a), `web/coverage/web/coverage-final.json` (sha256 fd0fb193...1a30, differs from the pre-format run
  db9b7f21...42b7, so rewritten by this run); per-file counters read with `.tmp/cov.mjs`
- Evidence outputs are fresh and inputs stayed unchanged: yes; the production file hash is the same before and after,
  the spec hashes above were taken after the final run
- Final run after latest test additions and production fixes: yes (no production fixes in this round)

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/calendar/views/calendar-view.ts` | 214/219 (97.71%) | 107/113 (94.69%) | 61/62 (98.38%) | 185/185 (100%) |
| `web/src/app/features/calendar/views/occurrence-store.ts` | 42/42 (100%) | 15/15 (100%) | 5/5 (100%) | 36/36 (100%) |
| `web/src/app/features/calendar/views/initial-scroll.ts` | 15/15 (100%) | 14/14 (100%) | 2/2 (100%) | 13/13 (100%) |
| **Total (summed counters, all 3 scoped files measured)** | 271/276 (98.19%) | 136/142 (95.77%) | 68/69 (98.55%) | 234/234 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `calendar-view.ts`: statements 87, 336, 360, 381, 394; branches 71[0], 194[1], 336[0], 360[0], 381[0], 394[0]; function at 87 | Pre-existing code (toolbar/editing/defensive guards, `ResizeObserver` missing in the environment), not changed by this task | Out of task scope; no test added |

## Unable to test

- The actual on-screen scroll of FullCalendar's time grid (real layout) cannot be observed in jsdom; the tests verify
  the time passed to `getApi().scrollToTime`. A browser (Playwright) check was not run; follow-up only if the parent
  wants E2E evidence.
- "Jump to a date/event" is exercised indirectly: it reaches the component as a FullCalendar `datesSet` with a new
  range, which the period-change tests cover; the linked-event `gotoDate` path was not combined with a scroll assertion.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.
