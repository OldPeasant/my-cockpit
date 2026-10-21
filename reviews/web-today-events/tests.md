# Test Report

Date: 2026-10-21

Task: web-today-events - Today view: calendar events

Scope: task commit range `3ed76da7c9f369ff3f2667f10c88807cbf19691e..dab3687050b9ff917319e4ec9f2d86297222a61e` on branch `main`, plus the uncommitted test change in `web/src/app/features/gtd/today/today-events.spec.ts` written by this run. The parent asked for a re-run on the final code at `80efa0cb` (fixes for CR-1, CR-2 and the whole-day span, see `reviews/web-today-events/fixes.md`). During this run the parent committed `dab36870`. It only rewraps a doc comment in `today-sections.ts` (plus `review.md`). The final coverage run and `npm run check` ran on `dab36870`. Production files at `dab36870` are identical to `80efa0cb` apart from that comment. All task paths are inside the task's `touches` (`web/src/app/features/gtd/today`). The other files in the range (`tasks/*.md`, `reviews/`) are not production code.

Requirements:
- `tasks/web-today-events.md` acceptance criteria and the recorded assumption (events built into TodayView above the tasks, visible calendars only, browser-local day, cancelled shown struck through)
- SPEC.md §5.3 (Today shows today's events)
- `reviews/web-today-events/fixes.md` (behavior the parent approved: hidden status text, `isOnDate` filtering of all-day events, whole-day timed events shown as "All day")
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`

## Test files changed

- `web/src/app/features/gtd/today/today-events.spec.ts`

## Tests added or updated

Count: 1 added (this run)

The fixer's new tests already cover the fixed behavior well:
- CR-1: "tells screen readers that an event is cancelled or tentative". It checks the `.visually-hidden` text per status, and that a confirmed event has none.
- CR-2: `isOnDate` x3 (exclusive end date, missing end date counts as one day, timed or dateless occurrences pass). Also the component test "leaves out all-day events of yesterday and tomorrow the server returns for another zone".
- Whole-day span: `eventSpan` "treats a timed event covering the whole day exactly as all day" (exact, starts at midnight, ends at midnight) and "counts an event starting at midnight but ending before the next one as inside today" (boundaries stay `timed`). Also the component test "labels a timed event covering the whole day as all day".

One gap remained. All component tests ran at 09:30 local time, and at that hour the local date and the UTC date are the same. So nothing proved that the component filters all-day events by the browser's local date (`localDate(now)`) rather than the UTC date. Added:
- `TodayEvents` "picks all-day events by the browser's local date, also just after and before midnight". It renders at 00:30 and again at 23:30 local time on 2026-10-20, with all-day events for yesterday, today, a multi-day span over today, today without an end date, and tomorrow. Both times it expects exactly `Birthday, Holiday, Vacation`. The test machine runs at UTC+02:00, so at 00:30 the UTC date is still 2026-10-19. A UTC-based filter would fail this test. The 23:30 case covers the same risk for negative offsets.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: Today lists today's events above the tasks | `TodayView` "shows today's calendar events above the task sections"; `TodayEvents` "asks for today's occurrences of the visible calendars" | `cd web && npx ng test --watch=false --include='src/app/features/gtd/today/*.spec.ts'` | Pass |
| AC1: time-ordered | "lists the events time-ordered, all-day first"; `compareEvents` x3; "labels the part of an event inside today..." (order assertion) | same | Pass |
| AC1: only today's events: all-day events by the local date (CR-2) | `isOnDate` x3; "leaves out all-day events of yesterday and tomorrow the server returns for another zone"; "picks all-day events by the browser's local date, also just after and before midnight" (new) | same | Pass |
| AC1: from/until spans and labels; whole-day timed event shown as all day | `eventSpan` x5 (incl. "treats a timed event covering the whole day exactly as all day", "counts an event starting at midnight but ending before the next one as inside today"); "labels the part of an event inside today: start-end, from, until, all day"; "labels a timed event covering the whole day as all day" | same | Pass |
| Assumption: cancelled struck through / tentative marked, also for screen readers (CR-1) | "marks cancelled and tentative events" (classes); "tells screen readers that an event is cancelled or tentative" (hidden text) | same | Pass |
| Assumption: only visible calendars | "asks for today's occurrences of the visible calendars"; "never shows occurrences of a hidden calendar..."; "reloads for the new calendars when a calendar is shown or hidden"; "does not reload when ... visible ones stay the same"; "shows no events, without asking, when every calendar is hidden" | same | Pass |
| Ordering/concurrency | "keeps the newest calendars when an older answer arrives last" | same | Pass |
| Change notifications refresh | "refreshes when an event changes or the change stream resyncs"; "ignores change notifications of other entity types" | same | Pass |
| Loading, empty, error + retry states | "shows loading until the events arrive"; "says so when there are no events today"; "offers a retry when the events could not be loaded"; "offers a retry when the calendars could not be loaded"; "keeps showing the events when a later refresh fails" | same | Pass |
| AC2: Vitest tests cover the events section; `cd web && npm run check` passes | all of the above | `cd web && npm run check` | Pass |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/gtd/today/*.spec.ts'`, plus `npx prettier --check src/app/features/gtd/today` and `npx eslint src/app/features/gtd/today`

Result:

Pass (2 files, 54 tests). Prettier and ESLint clean.

## Full test execution

Command:

`cd web && npm run coverage` (full web Vitest suite with coverage) and `cd web && npm run check` (api:check, ng lint --max-warnings=0, e2e typecheck, test:ci, ng build)

Result:

Pass. Both commands ran on the stable snapshot (HEAD `dab36870` plus the new spec). Coverage: 107 files, 2130 tests passed. `npm run check`: generated API up to date, "All files pass linting.", 107 files / 2130 tests passed, and `ng build` wrote `dist/web`. The build printed the existing warning that the initial bundle exceeds its budget (657.07 kB > 500 kB). It is only a warning, and this task did not cause it. An earlier pair of runs passed on `80efa0cb` too. They were repeated because `dab36870` landed in the middle of that `npm run check`.

## .taskcheck

Result:

Not run in this pass. The parent asked for `cd web && npm run check`, which is the web part of `.taskcheck` and passed. The fixer ran `sh scripts/check.sh` on the fix revision and it passed. The parent runs `.taskcheck` before `done`.

## Bugs found

None. No test exposed a production defect.

Observations that are not defects against the stated requirements (no fix requested):
- `TodayEvents` computes `now`, `range` and `today` once at construction. A Today view left open past midnight keeps showing the previous day's events until it is reloaded. The task does not require the view to roll over at midnight.
- Events running since yesterday (`until`) tie at "start 0" and sort among themselves by title, not by end time. This matches the documented comparator.

## Coverage

Status: verified (fresh full-suite run, exit 0, inputs unchanged during the run, final revision)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured line by line against the diff. Every scoped file has 100% line coverage (provider counters), so every executable changed line is covered. Changed-branch coverage cannot be inferred from that and is not reported.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-today-events`; base `3ed76da7c9f369ff3f2667f10c88807cbf19691e` (from the parent); measured HEAD `dab3687050b9ff917319e4ec9f2d86297222a61e`; task commits `5c72edf0`, `6c7efdba`, `80efa0cb`, `dab36870`. The file scope comes from `git diff --stat 3ed76da7..HEAD`, filtered to `web/src` production files.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `dab36870` plus uncommitted `today-events.spec.ts` sha256 `4abe70eb7acb4f6e543cdc657717faf8d51e27bfdef45ffb75a247e027c141d2`. Production sha256: `today-events.ts` `4b467367...e5a7bf`, `today-events.html` `ee927b22...53c7d7`, `today-view.ts` `dad04396...e991c9`, `today-view.html` `004b8377...f7b638`, `today-sections.ts` `a0d770cf...b093f6`. Identical before and after the final run.
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/features/gtd/today/today-events.ts`, `today-events.html`, `today-view.ts`, `today-view.html`, and `today-sections.ts` (comment-only changes, still measured). Excluded: `today-events.scss` and `today-view.scss` (styles, not instrumented), and `*.spec.ts` (tests). No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + `@vitest/coverage-v8` ^5.0.5 (v8); `npm run coverage`; cwd `web/`; full web suite, no include filter.
- Run start/completion time and exit status: started 2026-10-21 13:17:05 +02:00, reports written 13:17:47 +02:00; exit 0 (107 files / 2130 tests passed).
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 `11c315ea943389087047cf8ba9d73b7a30476ea75468c86eb9b16b2e8d5c4f00`; `coverage-summary.json` `5285faf085518cfdbd6ab11d419a808df7b14f7a654acd39c19871b941f14c82`; `lcov.info` `c7977e09379fa7e3cbde02868eacf5ec5aa419029cd77de9b4afc99024549cf6`.
- Evidence outputs are fresh and inputs stayed unchanged: the report mtimes fall inside this run. Source hashes and HEAD were re-checked after the run and had not changed. The earlier run at 13:15 on `80efa0cb` is superseded. `dab36870` landed after it, so its numbers are not used here.
- Final run after latest test additions and production fixes: yes. It ran after the fixer's changes, after `dab36870`, and after this run's test addition.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/gtd/today/today-events.ts` | 59/59 (100%) | 54/55 (98.18%) | 16/16 (100%) | 45/45 (100%) |
| `web/src/app/features/gtd/today/today-events.html` | 72/72 (100%) | 17/18 (94.44%) | 1/1 (100%) | 42/42 (100%) |
| `web/src/app/features/gtd/today/today-view.ts` | 45/45 (100%) | 21/23 (91.30%) | 17/17 (100%) | 36/36 (100%) |
| `web/src/app/features/gtd/today/today-view.html` | 57/57 (100%) | 4/4 (100%) | 3/3 (100%) | 32/32 (100%) |
| `web/src/app/features/gtd/today/today-sections.ts` | 3/3 (100%) | N/A (0/0) | 2/2 (100%) | 3/3 (100%) |
| **Aggregate (summed counters, 5 files; scss and specs excluded as above, no unmeasured files)** | 236/236 (100%) | 96/100 (96.00%) | 39/39 (100%) | 158/158 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `today-events.ts:80` branch [0] | None. v8 attributes it to the `@Component` decorator metadata (`providers: [OccurrenceStore]`). No runtime behavior belongs to it | Justified gap: compiler artifact |
| `today-events.html:62` branch [1] (`@if (event.calendarName)` false) | An event without a calendar name. This cannot happen: `events()` keeps only occurrences of visible calendars, so the calendar is always in `byId` | Justified gap: defensive and unreachable. "never shows occurrences of a hidden calendar" checks that unknown calendar ids are filtered out |
| `today-view.ts:43` branch [0], `today-view.ts:116` branch [0] | Line 43 is decorator metadata. Line 116 (the `uncomplete` path of `toggleDone`) existed before this task | Outside the task's changes; no test added |

## Unable to test

- The visual styling: strike-through for cancelled events and the `.visually-hidden` CSS rule in `today-events.scss`. jsdom does not apply component styles in a way worth asserting. The tests check the `cancelled`/`tentative` classes and the hidden status text that the styles rely on. Whether a real screen reader announces the text is not verified. No follow-up needed unless a visual or e2e test is wanted.
- Real-browser time zone/DST behavior across locales. It is covered only through browser-local `Date` construction in unit tests (the test machine runs at UTC+02:00). The task names no e2e criterion.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.
