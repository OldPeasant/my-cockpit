# Test Report

Date: 2026-10-21

Task: web-today-events - Today view: calendar events

Scope: task commit range `3ed76da7c9f369ff3f2667f10c88807cbf19691e..b3dc10fbb0d4fa3b74d6943058235bad430c7f1c` on branch `main` (HEAD = `b3dc10fbb0d4fa3b74d6943058235bad430c7f1c`), plus the uncommitted test changes in `web/src/app/features/gtd/today/today-events.spec.ts` written by this test run. Base and task commit came from the parent worker; the diff matches the task's `touches` (`web/src/app/features/gtd/today`).

Requirements:
- `tasks/web-today-events.md` acceptance criteria and the recorded assumption (events built into TodayView above the tasks, visible calendars only, browser-local day, cancelled shown struck through)
- SPEC.md §5.3 (Today shows today's events), as referenced by the task
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`
- Parent's edge-case list (from/until spans and labels, cancelled/tentative classes, visibility changes, hidden calendars, change-event refresh, calendar load failure + retry, sorting ties)

## Test files changed

- `web/src/app/features/gtd/today/today-events.spec.ts`

## Tests added or updated

Count: 20 added (the 6 existing tests unchanged; the `ChangeEventsService` stub now emits through subjects instead of `NEVER` so change notifications can be driven, which does not change what the existing tests check).

Added:
- `eventSpan`: treats an event exactly from midnight to midnight as inside today; counts an event crossing only one midnight as crossing it, even by a minute; treats a timed occurrence without start or end as all day
- `compareEvents`: orders events with the same start by title; orders all-day and day-spanning events by title; puts all-day first, then events running since yesterday, then by start
- `TodayEvents`: labels the part of an event inside today (start-end, from, until, all day); marks cancelled and tentative events; shows each event in its calendar's color and name; never shows occurrences of a hidden calendar, even when the server returns them; shows loading until the events arrive; reloads for the new calendars when a calendar is shown or hidden; does not reload when the calendars change but the visible ones stay the same; shows no events, without asking, when every calendar is hidden; keeps the newest calendars when an older answer arrives last; refreshes when an event changes or the change stream resyncs; ignores change notifications of other entity types; offers a retry when the calendars could not be loaded; keeps showing the events when a later refresh fails; links to the calendar

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: Today lists today's events above the tasks | `TodayView` "shows today's calendar events above the task sections" (existing); `TodayEvents` "asks for today's occurrences of the visible calendars" (existing) | `cd web && npx ng test --watch=false --include='src/app/features/gtd/today/*.spec.ts'` | Pass |
| AC1: time-ordered | "lists the events time-ordered, all-day first" (existing); `compareEvents` x3; "labels the part of an event inside today..." (order assertion) | same | Pass |
| AC1 edge: from/until spans and their labels | `eventSpan` x4; "labels the part of an event inside today: start-end, from, until, all day" | same | Pass |
| Assumption: cancelled shown struck through / tentative marked | "marks cancelled and tentative events" (asserts the `cancelled`/`tentative` classes; the strike-through itself is CSS and not asserted in jsdom) | same | Pass |
| Assumption: only visible calendars | "asks for today's occurrences of the visible calendars"; "never shows occurrences of a hidden calendar..."; "reloads for the new calendars when a calendar is shown or hidden"; "does not reload when ... visible ones stay the same"; "shows no events, without asking, when every calendar is hidden" | same | Pass |
| Ordering/concurrency | "keeps the newest calendars when an older answer arrives last" | same | Pass |
| Change notifications refresh | "refreshes when an event changes or the change stream resyncs"; "ignores change notifications of other entity types" | same | Pass |
| Loading, empty, error + retry states | "shows loading until the events arrive"; "says so when there are no events today" (existing); "offers a retry when the events could not be loaded" (existing); "offers a retry when the calendars could not be loaded"; "keeps showing the events when a later refresh fails" | same | Pass |
| AC2: Vitest tests cover the events section; `cd web && npm run check` passes | all of the above | `cd web && npm run check` | Pass |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/gtd/today/*.spec.ts'`

Result:

Pass (2 files, 46 tests)

## Full test execution

Command:

`cd web && npm run coverage` (full web Vitest suite with coverage) and `cd web && npm run check` (api:check, lint, e2e typecheck, test:ci, build)

Result:

Pass. Coverage run: 107 files, 2122 tests passed. `npm run check`: every step ran through to `ng build`, which wrote `dist/web`. The build printed the warning that the initial bundle exceeds its 500 kB budget (657.07 kB). It is only a warning, the build did not fail, and this task did not cause it. `ng lint --max-warnings=0` and `prettier --check src/app/features/gtd/today/`: clean.

## .taskcheck

Result:

Pass (`sh scripts/check.sh` -> tfp gate tests, server check, web check and android check, ending with "== all checks passed")

## Bugs found

None. No test exposed a production defect.

Observations that are not defects against the stated requirements (recorded for the parent; no fix requested):
- `TodayEvents` computes `todayRange()` once at construction, so a Today view left open past midnight keeps showing the previous day's events until it is reloaded. The task does not require the view to roll over at midnight.
- Events running since yesterday (`until`) tie at "start 0" and are ordered among themselves by title, not by end time. This matches the documented comparator ("all-day first, then by start time, then by title").
- A timed event exactly 00:00-24:00 local is classified `timed` and labelled "12:00 AM – 12:00 AM". That label follows from the inclusive/exclusive range rule, but it is odd.

## Coverage

Status: verified (fresh full-suite run, exit 0, inputs unchanged during the run)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured line-by-line against the diff. Every scoped file has 100% line coverage (provider counters), so each executable changed line in them is covered. Changed-branch coverage cannot be inferred from this and is not reported.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-today-events`; base `3ed76da7c9f369ff3f2667f10c88807cbf19691e`; HEAD `b3dc10fbb0d4fa3b74d6943058235bad430c7f1c`; single task commit `b3dc10fb` given by the parent, cross-checked with `git diff --stat 3ed76da7..b3dc10fb` (all paths inside the task's `touches`).
- Source/test snapshot identifier (including uncommitted inputs): HEAD `b3dc10fb` + uncommitted `today-events.spec.ts` sha256 `0ac6567b50cbfa59148937c16eceb3b32619d2a23d706f36afada85c29eaafe1`; production files at HEAD: `today-events.ts` `342103725d04aaeb...e257`, `today-events.html` `09433553a7264bdd...c4b7`, `today-view.ts` `dad0439631b46b49...9cb4` (sha256 identical before and after the run).
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/features/gtd/today/today-events.ts`, `today-events.html`, `today-view.ts`, `today-view.html`, `today-sections.ts` (comment-only change, still measured). Excluded: `today-events.scss`, `today-view.scss` (styles, not instrumented), `*.spec.ts` (tests). No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + `@vitest/coverage-v8` ^5.0.5 (v8); `npm run coverage`; cwd `web/`; full web suite (no include filter).
- Run start/completion time and exit status: started 2026-10-21 12:57:06 +02:00, reports written 10:57:41Z; exit 0 (107 files / 2122 tests passed).
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 `01c9d1e4650ff2bb2e03a4b3d73327ee4e4e9a8003e1a3149b5386324ab7f2da`; `coverage-summary.json` `e2383e64e9e2628f4f898f8ac5f53f848eecddac18dd1eb8bfa0da30b506a9c4`; `lcov.info` `abf00798751dda72c607af769867cf6bc42813bef87e31414f280dd6bd1231a9`.
- Evidence outputs are fresh and inputs stayed unchanged: report mtimes fall inside this run; source hashes re-checked after the run and unchanged.
- Final run after latest test additions and production fixes: yes (it ran after all test additions; there were no production fixes).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/gtd/today/today-events.ts` | 51/51 (100%) | 44/45 (97.78%) | 15/15 (100%) | 38/38 (100%) |
| `web/src/app/features/gtd/today/today-events.html` | 64/64 (100%) | 15/16 (93.75%) | 1/1 (100%) | 39/39 (100%) |
| `web/src/app/features/gtd/today/today-view.ts` | 45/45 (100%) | 21/23 (91.30%) | 17/17 (100%) | 36/36 (100%) |
| `web/src/app/features/gtd/today/today-view.html` | 57/57 (100%) | 4/4 (100%) | 3/3 (100%) | 32/32 (100%) |
| `web/src/app/features/gtd/today/today-sections.ts` | 3/3 (100%) | N/A (0/0) | 2/2 (100%) | 3/3 (100%) |
| **Aggregate (summed counters, 5 files; scss excluded as above)** | 220/220 (100%) | 84/88 (95.45%) | 38/38 (100%) | 148/148 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `today-events.ts:63` branch [0] | None. v8 attributes it to the `@Component` decorator metadata (`providers: [OccurrenceStore]`) and no runtime behavior belongs to it | Justified gap: compiler artifact |
| `today-events.html:57` branch [1] (`@if (event.calendarName)` false) | An event without a calendar name. It cannot happen: `events()` keeps only occurrences of visible calendars, so the calendar is always in `byId` | Justified gap: defensive, unreachable. "never shows occurrences of a hidden calendar" checks that unknown calendar ids are filtered out |
| `today-view.ts:43` branch [0], `today-view.ts:116` branch [0] | Line 43 is decorator metadata. Line 116 (`uncomplete` path of `toggleDone`) existed before this task | Outside the task's changes; no test added |

## Unable to test

- The struck-through styling of cancelled events (CSS `text-decoration` in `today-events.scss`): jsdom does not apply component styles in a way worth asserting. The tests check the `cancelled` class that the style depends on. No follow-up needed unless a visual/e2e test is wanted.
- Real browser rendering and the time zone/DST behavior across real locales: covered only through the browser-local `Date` construction in unit tests (`TZ` of the test machine). No e2e criterion is named by the task.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.
