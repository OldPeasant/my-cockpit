# Test Report

Date: 2026-11-23

Task: web-gtd-tickler-view - Web: Tickler list view (what comes back when)

Scope: task base 00b75d3f86c2b4e557ee9d730d94ec67a0dded5b, implementation commit 4559980120e13a0f5e86022de9167191b9acd61a (`git diff 00b75d3f8..455998012 -- web`). Measured at HEAD 08de6ae649024f8094db4585243b61d95776a8dc on branch main; `git diff 455998012 08de6ae64 -- web` is empty, so the web code measured is the implementation commit. Uncommitted inputs: only the two test files listed below.

Requirements:
- tasks/web-gtd-tickler-view.md acceptance criteria and recorded assumption (10 yearly from/to windows starting tomorrow, client-side filter ticklerDate after today, "Back on EEE, d MMM" with year outside the current year, "Back tomorrow"; failed load shows error with retry, no separate offline state)
- docs/ARCHITECTURE.md "Coverage" and i18n conventions
- existing list page tests (lists/list-pages.spec.ts) and locale spec conventions

## Test files changed

- `web/src/app/features/gtd/lists/tickler-page.spec.ts` (extended; setup gained controllable TASK change events, resync, cursor paging, a failing range and a failing completion)
- `web/src/locale/messages-de.spec.ts` (one test added)

## Tests added or updated

Count: 10 added (9 in tickler-page.spec.ts, 1 in messages-de.spec.ts); the 13 existing tickler tests are unchanged in their assertions.

Added:
- `brings a task back when completing it fails`
- `follows nextCursor to read every page of a range`
- `lists tasks on the edges of the ranges and none beyond the horizon`
- `shows the error, not a partial list, when a later range fails`
- `reloads on a task change event and shows the new state`
- `drops a deleted task on its change event without reloading`
- `reloads after a resync`
- `with a fixed date > shows "tomorrow", the date this year and the date with its year after it` (Date faked to 2027-03-10; checks first window 2027-03-11..2028-03-10 and the exact labels "Back on Fri, 12 Mar", "Back on Fri, 31 Dec", "Back on Wed, 5 Jan 2028", and that a tickler date of today is hidden)
- `German translations > has the Tickler view messages in English and German (web-gtd-tickler-view)`

## Requirement coverage

All rows ran with `cd web && npx ng test --watch=false --include src/app/features/gtd/lists/tickler-page.spec.ts --include src/locale/messages-de.spec.ts` (34 passed) and again in `cd web && npm run coverage` (5023 passed).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: nav entry right after Scheduled, route /tickler | `is in the navigation right after Scheduled, at /tickler` | focused + coverage run | Pass |
| AC1: lists open tasks with tickler date after today, sorted by date then list order, with return date | `lists the tasks in the tickler by date, then list order, with their return date`; `hides tasks whose tickler date has come and tasks only due in the range`; `lists a task found in two ranges once`; `lists tasks on the edges of the ranges and none beyond the horizon`; fixed-date label test; `keeps open tasks with a tickler date after today` | focused + coverage run | Pass |
| AC2: opening a row opens task detail | `opens the task detail from a row` | focused + coverage run | Pass |
| AC2: view reflects a change afterwards | `shows the changed date after coming back from the task detail`; `reloads on a task change event and shows the new state`; `drops a deleted task on its change event without reloading`; `reloads after a resync` | focused + coverage run | Pass |
| AC2: complete from a row | `completes a task from its checkbox`; `brings a task back when completing it fails` | focused + coverage run | Pass |
| AC3: loading, empty, error/offline with retry | `shows a loading state until the list arrives`; `shows an empty state`; `shows a load error (e.g. offline) with a retry`; `shows the error, not a partial list, when a later range fails` | focused + coverage run | Pass |
| AC4: data from GET /api/v1/tasks?from=&to= (yearly windows, paging) | `reads the next years a year at a time through the task list date range`; `follows nextCursor to read every page of a range`; `splits the horizon into consecutive ranges of at most a year`; fixed-date test (first window bounds) | focused + coverage run | Pass |
| AC5: strings in messages.xlf and messages.de.xlf | `has the Tickler view messages in English and German (web-gtd-tickler-view)` plus the generic completeness/placeholder tests in messages-de.spec.ts | focused + coverage run | Pass |
| AC5: `npm run check` passes | none (parent runs it) | not run here | Not run: parent worker runs `npm run check` / `.taskcheck` as instructed |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include src/app/features/gtd/lists/tickler-page.spec.ts --include src/locale/messages-de.spec.ts`

Result:

Pass (2 files, 34 tests). Baseline before changes: tickler-page.spec.ts 13 passed. `npx prettier --check` and `npx eslint` on both changed spec files: clean.

## Full test execution

Command:

`cd web && npm run coverage` (the whole web Vitest suite with coverage)

Result:

Pass: 200 test files, 5023 tests, 0 failed. Server and Android suites not run (no Gradle, per instructions; the task changes only web/).

## .taskcheck

Result:

Not run (parent worker runs `sh scripts/check.sh` as instructed).

## Bugs found

None. No production defect was found by the new tests.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (the files that carry behavior are new in this task, so whole-file equals changed-line for tickler-store.ts, tickler-page.ts, tickler-page.html and tickler.routes.ts; nav-sections.ts and gtd.routes.ts each gained one line, both executed).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-gtd-tickler-view; base 00b75d3f86c2b4e557ee9d730d94ec67a0dded5b; implementation 4559980120e13a0f5e86022de9167191b9acd61a (given by the parent); HEAD 08de6ae649024f8094db4585243b61d95776a8dc with identical web/ content; file list from `git diff --stat 00b75d3f8..455998012`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 08de6ae64 plus sha256 tickler-page.spec.ts 474a761ec61f8ac415ce29cdbb5a0203e25db6f960517172d43df0bfc8e5ae8b and messages-de.spec.ts becb79758f7d45e45021d273f7643ff04824bafea09e63d120f9b46264f4e76b (identical before and after the run).
- Exact included files: web/src/app/features/gtd/gtd.routes.ts, web/src/app/features/gtd/lists/tickler-page.ts, web/src/app/features/gtd/lists/tickler-page.html, web/src/app/features/gtd/lists/tickler-store.ts, web/src/app/features/gtd/lists/tickler.routes.ts, web/src/app/shell/nav-sections.ts. Excluded: lists.scss (styles, no executable behavior), messages.xlf / messages.de.xlf (translation data, validated by messages-de.spec.ts), tickler-page.spec.ts (test), tasks/web-gtd-tickler-view.md (task file). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with @vitest/coverage-v8 (v8), `npm run coverage`, working directory web/, whole web suite.
- Run start/completion time and exit status: started 2026-11-23 19:24:47 +0100, reports written 19:25:36 +0100; all tests passed, coverage summary printed (exit 0).
- Generated report paths and report hashes/run identifier: web/coverage/web/coverage-summary.json (sha256 5194337be45f3787bab88a921b1037e2fec55ad383ece89b8d181af2a6df02be), web/coverage/web/coverage-final.json, web/coverage/web/lcov.info.
- Evidence outputs are fresh and inputs stayed unchanged: yes, report files timestamped within this run; test file hashes unchanged after the run; no production file modified.
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage); zero-denominator metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/gtd/lists/tickler-store.ts` | 24/24 (100%) | 14/16 (87.5%) | 7/7 (100%) | 19/19 (100%) |
| `web/src/app/features/gtd/lists/tickler-page.ts` | 8/8 (100%) | 10/11 (90.9%) | 3/3 (100%) | 5/5 (100%) |
| `web/src/app/features/gtd/lists/tickler-page.html` | 46/46 (100%) | 4/4 (100%) | 3/3 (100%) | 24/24 (100%) |
| `web/src/app/features/gtd/lists/tickler.routes.ts` | 1/1 (100%) | N/A (0/0) | N/A (0/0) | 1/1 (100%) |
| `web/src/app/features/gtd/gtd.routes.ts` | 11/11 (100%) | N/A (0/0) | 10/10 (100%) | 11/11 (100%) |
| `web/src/app/shell/nav-sections.ts` | 3/3 (100%) | N/A (0/0) | 1/1 (100%) | 2/2 (100%) |
| Aggregate (summed counters, 6 files; excluded: lists.scss, xlf files) | 93/93 (100%) | 28/31 (90.3%) | 24/24 (100%) | 62/62 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `tickler-store.ts:30` (the `?? ''` fallbacks of `a.ticklerDate` / `b.ticklerDate` in `compare`) | none: every listed item passed `isInTickler`, so it always has a ticklerDate | Not reachable through the view; defensive only, no test added |
| `tickler-page.ts:17` (branch 2,0, the `providers` / decorator metadata line) | none: compiler-generated branch of the component decorator | Non-material, no test added |

## Unable to test

- `cd web && npm run check` (AC5) and `.taskcheck` were not run here by instruction; the parent worker runs them.
- The real server filter (`GET /api/v1/tasks?from=&to=` matching ticklerDate) is simulated by the spec's Api fake; no live server or browser test was run (none is named by the criteria).
- The German rendering of the date ("Zurück am" with a German weekday/month) is only checked at the translation-unit level; the unit tests run the English build. A `npm run build:de` check belongs to the parent's `npm run check` / build.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production defects found.
