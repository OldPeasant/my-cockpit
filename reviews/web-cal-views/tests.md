# Test Report

Date: 2026-10-21

Task: web-cal-views - Calendar views (SPEC.md §6.3). This is the third test-writer round, run on the final code after the TW-1 fix (a8d05719).

Scope: base 793b2ae33e4f74e6bf9d60e659b9a1e677fb0409, task commits 20d90f7b..a8d05719 (HEAD a8d05719657b4a9fa333faf89ca3da67c8fdba43), `git diff 793b2ae3..HEAD -- web`, as given by the parent worker. The scope also includes this run's one uncommitted test-file change, listed below.

Requirements:
- tasks/web-cal-views.md acceptance criteria 1-4 and the assumptions recorded there
- SPEC.md §6.3 (calendar views) and §4.2 (optimistic locking with If-Match)
- reviews/web-cal-views/review.md (CR-1, CR-2), reviews/web-cal-views/fixes.md (CR-1, CR-2, TW-1)
- docs/ARCHITECTURE.md "Coverage", docs/QUALITY.md

## Test files changed

- `web/src/app/features/calendar/views/calendar-store.spec.ts`

This run changed no production files, e2e specs or configuration. It modified or removed no existing test.

## Tests added or updated

Count: 1 added. The four calendar spec files now hold 68 tests (67 before this run).

- `CalendarStore > does not bring back a calendar deleted elsewhere when its save answers after the list`: this is a regression guard next to the TW-1 fix. A calendar is deleted elsewhere after its PATCH was committed, and the list answer without it arrives before the PATCH answer. The late save answer must not add the deleted calendar back. It passes.

TW-1 fix confirmation: `CalendarStore > keeps a newer calendar from a list answer over a save answer that arrives after it` is unchanged and now passes. It failed at e8ba8421 in round 2 with `expected 2 to be 3`. The equal-version path of the new `c.version <= saved.version` guard (where the save answer replaces the calendar) is covered by `keeps a toggle shown when its own change notification is answered before the save` (list version 2, save version 2). Both arms of the guard are hit (`calendar-store.ts` branches 27/27).

## Requirement coverage

Focused command (F): `cd web && npx ng test --watch=false --include='src/app/features/calendar/**/*.spec.ts'`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 FullCalendar with week (default), day, month and list views | `shows the week view by default ...`, `opens the view and date of the URL`, `opens the day and agenda views of the URL`, `falls back to the week view for an invalid URL view and date`, `maps URL view names ...`, `lazy-loads the calendar view at /calendar` | F | Pass |
| AC1 week start and time zone from settings | `shows the week view by default in the time zone and week start of the settings`, `follows changed settings ...`, `maps the week start setting to FullCalendar firstDay`, `puts the view and the date in the settings time zone into the URL` | F | Pass |
| AC1 calendar visibility toggles with colors | `toggles a calendar without refetching its events`, `shows a calendar again ...`, `reports a toggle the server refuses ...`, `shows each calendar with its color ...`, all `CalendarStore` toggle tests | F | Pass |
| TW-1 a save answer must not overwrite a newer calendar state | `keeps a newer calendar from a list answer over a save answer that arrives after it`, `keeps a toggle shown when its own change notification is answered before the save`, `does not bring back a calendar deleted elsewhere when its save answers after the list` (new) | F | Pass |
| CR-2 list answers merged by version after a save | `keeps a saved toggle over a list answer requested before it`, `takes a calendar of a list answer when its version is newer than the saved one` | F | Pass |
| AC2 loads occurrences for the visible range, out-of-order safe | `loads the occurrences of the visible range ...`, `drops a slower answer for an earlier range (out of order)`, `shows the occurrences of the newest range when answers arrive out of order`, the `OccurrenceStore` ordering tests, `drops an older list answer ...` | F | Pass |
| AC2 live updates via SSE (incl. CR-1 CALENDAR events) | `reloads after a calendar change notification, not after other changes`, `fetches the events of a calendar created elsewhere ...`, `hides the events of a calendar hidden elsewhere without a refetch`, `refetches without a calendar deleted elsewhere and removes its toggle`, `drops a calendar deleted elsewhere after its change notification`, `refetches the range on a SSE resync ...`, `shows an event created elsewhere ...` | F | Pass |
| AC3 Vitest tests cover range loading and toggles; `npm run check` passes | all of the above | `cd web && npm run check` | Pass: "All files pass linting.", 55 test files / 968 tests passed, `ng build` succeeded |
| AC4 Playwright DST week spec against the e2e stack | `e2e/calendar-views/views.spec.ts` › `weekly event shows correctly across the DST week` | `cd web && npm run e2e -- e2e/calendar-views/views.spec.ts` (default `docker compose`, which runs podman-compose here; e2e stack built from this checkout; chromium) | Pass: 6 passed in 12.8 s. These are the 5 phase-0 demo dependency tests plus the calendar-views spec (2.4 s). The stack was healthy after 121 s. Run 2026-10-20T22:59Z-23:01Z. |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/calendar/**/*.spec.ts'`

Result:

Pass: 4 test files, 68 tests passed.

## Full test execution

Command:

`cd web && npm run check` (api:check, lint `--max-warnings=0`, e2e:typecheck, test:ci, ng build, chained with `&&`)

Result:

Pass: "All files pass linting.", 55 test files / 968 tests passed, and the build succeeded ("Output location: web/dist/web"). The build reports one non-failing warning, known from earlier rounds: the initial bundle is 634.21 kB against a 500 kB budget. The task only adds the lazy `calendar-view` chunk.

## .taskcheck

Result:

Pass. `sh scripts/check.sh` printed "== all checks passed" (server, web and android). It ran on HEAD a8d05719 plus the uncommitted spec change.

## Bugs found

None in this round. TW-1 from round 2 is fixed in a8d05719 (`calendar-store.ts:93-96`) and confirmed by its original failing test, which now passes.

## Coverage

Status: verified.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured by diff intersection. Every in-scope file except `calendar.routes.ts` is new in this task, so for those files whole-file coverage equals changed-line coverage. `calendar.routes.ts` has 2/2 lines covered. The TW-1 fix lines (`calendar-store.ts:93-96`) are covered, including both arms of the new version guard.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-cal-views`; base 793b2ae33e4f74e6bf9d60e659b9a1e677fb0409; HEAD a8d05719657b4a9fa333faf89ca3da67c8fdba43; task commits 20d90f7b, 8f2c3049, e4a62bfe, 1610e60f, c190fc31, b5ce92ad, a8d05719. The parent supplied these, and I checked them with `git log --oneline 793b2ae3..HEAD` and `git diff --stat 793b2ae3..HEAD` (18 files, all under `web/` and `reviews/web-cal-views/`).
- Source/test snapshot identifier (sha256 prefixes): HEAD a8d05719 plus the uncommitted `calendar-store.spec.ts` (210a2215a32ca019). The production files match HEAD: `calendar-store.ts` 6a7f41d1c23116c5, `occurrence-store.ts` f4c4516be83cd67e, `calendar-views.ts` 0959a0def21777ef, `calendar-view.ts` 7e7a2686f76e426b, `calendar-view.html` 45c942da992f6e31, `calendar.routes.ts` ce0f53fe669c96e8. Other specs: `calendar-view.spec.ts` c47510b1b32eafe8, `calendar-views.spec.ts` e4aac336829dee04, `occurrence-store.spec.ts` fc8be7a98a36a041, `testing.ts` e400ccd8f1e4cde5.
- Exact included files: `web/src/app/features/calendar/views/{calendar-store.ts, occurrence-store.ts, calendar-views.ts, calendar-view.ts, calendar-view.html}` and `web/src/app/features/calendar/calendar.routes.ts`. Excluded: `views/testing.ts` (a test fixture, numbers shown for information only), `calendar-view.scss` (styles), `web/angular.json` (build config, exercised by `ng build` and the e2e run), the spec files and `web/e2e/calendar-views/views.spec.ts`. No files were renamed or deleted.
- Provider/version, command, working directory, and test selection: `npm run coverage` in `web/` (`ng test --watch=false --coverage` with json-summary, json, lcov and text-summary reporters), Vitest 5.0.5 with `@vitest/coverage-v8`, the full web suite (55 files, 968 tests passed).
- Run start/completion time and exit status: 2026-10-20T22:58:08Z-22:58:29Z. All tests passed and the report was written. The text summary printed repository-wide totals, which are not used here.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` (sha256 cdb815a1f36062550ea0b04d...) and `web/coverage/web/coverage-final.json` (sha256 d2a64938041f4175b52d9815...), plus `lcov.info` and `lcov-report/`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. Before the run, `coverage/web/` held only the round-2 files from 00:48 local time. After the run, all report files are dated 00:58:29 local time and `lcov.info` is newly present. The source hashes taken before the runs matched those taken after them.
- Final run after latest test additions and production fixes: yes. The run covers HEAD a8d05719 (after the TW-1 fix) plus this round's new test.

Values are covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/calendar/views/calendar-store.ts` | 67/67 (100%) | 27/27 (100%) | 24/24 (100%) | 52/52 (100%) |
| `web/src/app/features/calendar/views/occurrence-store.ts` | 39/39 (100%) | 15/15 (100%) | 5/5 (100%) | 33/33 (100%) |
| `web/src/app/features/calendar/views/calendar-views.ts` | 22/22 (100%) | 24/24 (100%) | 7/7 (100%) | 19/19 (100%) |
| `web/src/app/features/calendar/views/calendar-view.ts` | 53/53 (100%) | 20/21 (95.24%) | 19/19 (100%) | 44/44 (100%) |
| `web/src/app/features/calendar/views/calendar-view.html` | 66/66 (100%) | 6/6 (100%) | 4/4 (100%) | 35/35 (100%) |
| `web/src/app/features/calendar/calendar.routes.ts` | 2/2 (100%) | 0/0 (N/A) | 1/1 (100%) | 2/2 (100%) |
| Aggregate (summed counters, the 6 files above) | 249/249 (100%) | 92/93 (98.92%) | 60/60 (100%) | 185/185 (100%) |

The aggregate excludes `testing.ts` (2/2 on every metric), `calendar-view.scss` and `angular.json`. Every scoped file appears in the report; none is unmeasured.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `web/src/app/features/calendar/views/calendar-view.ts:44:30` (1 `if` branch, mapped to the `@Component` metadata) | None. The Angular compiler emits this branch; it is not task logic. | Left uncovered as non-material |

## Unable to test

- Unit tests cannot cover FullCalendar's real rendering or live SSE over a real connection. The e2e spec covers both, and it passed in this run (AC4).
- None of this needs follow-up.

## Summary

Tests added and all validation passed. The TW-1 fix is confirmed by its original failing test, which now passes. I added one regression test for a calendar deleted while its save is in flight. These all pass on HEAD a8d05719 plus the new test:
- the focused suite (68 tests);
- `npm run check` (968 tests);
- `.taskcheck`;
- the Playwright DST spec against the e2e stack.

Task-scoped coverage is 100% for statements, functions and lines, and 92/93 branches. The one uncovered branch is generated by the Angular compiler. No production defects were found.
