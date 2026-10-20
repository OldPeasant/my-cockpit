# Test Report

Date: 2026-10-21

Task: web-cal-views - Calendar views (SPEC.md §6.3), second test-writer round on the final code after fixes CR-1/CR-2

Scope: base 9c86c996a0cc65997b917128ba4087b1bd35c684, task commits cc6906ec..e8ba8421 (HEAD e8ba8421bafb553ac7149a48073926e1c9b6a790), `git diff 9c86c996..HEAD -- web`, as given by the parent worker. Also included: this run's uncommitted changes to two test files (listed below).

Requirements:
- tasks/web-cal-views.md acceptance criteria 1-4 and the assumptions recorded there
- SPEC.md §6.3 (calendar views) and §4.2 (optimistic locking with If-Match)
- reviews/web-cal-views/review.md (CR-1, CR-2) and fixes.md (the intent of each fix)
- docs/ARCHITECTURE.md "Coverage", docs/QUALITY.md

## Test files changed

- `web/src/app/features/calendar/views/calendar-store.spec.ts`
- `web/src/app/features/calendar/views/calendar-view.spec.ts`

This run changed no production files, e2e specs or configuration, and it modified or removed no existing test.

## Tests added or updated

Count: 5 added. The four calendar spec files now hold 67 tests (62 before this run).

- `CalendarStore` (+3, on CR-1/CR-2):
  - `drops a calendar deleted elsewhere after its change notification`: a `CALENDAR DELETED` event reloads the list. The version merge must not keep a calendar that is missing from the answer.
  - `keeps a toggle shown when its own change notification is answered before the save`: the server's notification for our own save causes a reload, and that reload is answered before the PATCH.
  - `keeps a newer calendar from a list answer over a save answer that arrives after it`: this test **fails**. See TW-1.
- `CalendarView` (+2, on CR-1):
  - `hides the events of a calendar hidden elsewhere without a refetch`: a `CALENDAR UPDATED` event sets `visible: false`. The events are filtered out and the checkbox is unchecked, with no new occurrence request.
  - `refetches without a calendar deleted elsewhere and removes its toggle`

## Requirement coverage

Focused command (F): `cd web && npx ng test --watch=false --include='src/app/features/calendar/**/*.spec.ts'`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 FullCalendar with week (default), day, month and list views | `shows the week view by default ...`, `opens the view and date of the URL`, `opens the day and agenda views of the URL`, `falls back to the week view for an invalid URL view and date`, `maps URL view names ...`, `lazy-loads the calendar view at /calendar` | F | Pass |
| AC1 week start and time zone from settings | `shows the week view by default in the time zone and week start of the settings`, `follows changed settings ...`, `maps the week start setting to FullCalendar firstDay`, `puts the view and the date in the settings time zone into the URL` | F | Pass |
| AC1 calendar visibility toggles with colors | `toggles a calendar without refetching its events`, `shows a calendar again ...`, `reports a toggle the server refuses ...`, `shows each calendar with its color ...`, all `CalendarStore` toggle tests | F | Pass, except `keeps a newer calendar from a list answer over a save answer that arrives after it`: Fail (TW-1) |
| AC2 loads occurrences for the visible range, out-of-order safe | `loads the occurrences of the visible range ...`, `drops a slower answer for an earlier range (out of order)`, `shows the occurrences of the newest range when answers arrive out of order`, the `OccurrenceStore` ordering tests, `drops an older list answer ...` | F | Pass |
| AC2 live updates via SSE (incl. CR-1: CALENDAR events) | `reloads after a calendar change notification, not after other changes`, `fetches the events of a calendar created elsewhere ...`, `hides the events of a calendar hidden elsewhere without a refetch` (new), `refetches without a calendar deleted elsewhere and removes its toggle` (new), `drops a calendar deleted elsewhere after its change notification` (new), `refetches the range on a SSE resync ...`, `shows an event created elsewhere ...` | F | Pass |
| CR-2 list answers merged by version after a save | `keeps a saved toggle over a list answer requested before it`, `takes a calendar of a list answer when its version is newer than the saved one`, `keeps a toggle shown when its own change notification is answered before the save` (new), `keeps a newer calendar from a list answer over a save answer that arrives after it` (new) | F | Fail: the last test fails (TW-1), the others pass |
| AC3 Vitest tests cover range loading and toggles; `npm run check` passes | all of the above | `cd web && npm run check` | Fail: 1 failed / 966 passed (TW-1). api:check, lint (`--max-warnings=0`) and e2e:typecheck passed before the test step. `npx ng build` run on its own: succeeded. |
| AC4 Playwright DST week spec against the e2e stack | `e2e/calendar-views/views.spec.ts` › `weekly event shows correctly across the DST week` | `cd web && npm run e2e -- e2e/calendar-views/views.spec.ts` (podman-compose e2e stack, built from this checkout, chromium) | Pass: 6 passed (the 5 phase-0 demo dependency tests plus the calendar-views spec, which took 3.2 s), 2026-10-20T22:48:40Z-22:51:30Z |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/calendar/**/*.spec.ts'`

Result:

Fail: 4 files, 66 passed / 1 failed. The failing test is `CalendarStore > keeps a newer calendar from a list answer over a save answer that arrives after it` (TW-1).

## Full test execution

Command:

`cd web && npm run check`

Result:

Fail: 55 files, 966 passed / 1 failed (the same TW-1 test). The steps before the tests passed. `ng build` did not run inside `check` because the test step failed, so I ran `npx ng build` on its own: it succeeded. It shows the known non-failing warning that the initial bundle exceeds its budget (634.21 kB against 500 kB).

## .taskcheck

Result:

Not run. `sh scripts/check.sh` includes the web `npm run check`, which fails on TW-1 until production code is fixed. The parent worker runs `.taskcheck` before `done`.

## Bugs found

### A save answer overwrites a newer calendar state that a list answer already brought

Finding ID:

`TW-1`

Test:

`CalendarStore > keeps a newer calendar from a list answer over a save answer that arrives after it` (`web/src/app/features/calendar/views/calendar-store.spec.ts`)

Production source:

`web/src/app/features/calendar/views/calendar-store.ts:93` (`this.server.update((all) => all.map((c) => (c.id === saved.id ? saved : c)))`)

Requirement:

AC1 visibility toggles, plus the intent of the CR-2 fix. Known calendar state is merged by `version`, so an older answer must not replace a newer state. SPEC.md §4.2 requires If-Match with the current version.

Expected behavior:

Steps to reproduce:

1. The calendar is at version 1. A toggle sends a PATCH, which the server saves as version 2.
2. The calendar is then changed elsewhere (version 3). The `CALENDAR` notification triggers a list reload.
3. The list answer (version 3) arrives before the PATCH answer (version 2).

The store should keep version 3: its color, its visibility, and `If-Match: "3"` on the next toggle. The save answer should be merged by version, like list answers are in `newest()`.

Actual behavior:

The PATCH answer replaces the calendar unconditionally. The store goes back to version 2 and the older color. The next toggle sends `If-Match: "2"`, which the server rejects with 412, so the toggle is rolled back and the user sees an error. The test fails with `expected 2 to be 3`. The store recovers on the next reload.

Severity:

Low. It needs a change elsewhere to land between the commit and the PATCH answer, and that answer must arrive after the list answer. The effect is a stale display and one refused toggle, and the next reload repairs it.

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Coverage

Status: partial-failed. The project's coverage command (`npm run coverage`) exits with 1 failed test and writes no report. The numbers below come from a diagnostic run that leaves out only the TW-1 test. They are not a verified measurement of the final validation.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured with a diff intersection. Every in-scope file except `calendar.routes.ts` is new in this task, so its whole-file coverage equals its changed-line coverage. `calendar.routes.ts` has 2/2 lines covered.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-cal-views`; base 9c86c996a0cc65997b917128ba4087b1bd35c684; HEAD e8ba8421bafb553ac7149a48073926e1c9b6a790; task commits cc6906ec, c91bf1f0, 1c5f36f7, c8bf7042, e8ba8421, as given by the parent and checked with `git log`/`git diff --stat 9c86c996..HEAD` (18 files, all under `web/` and `reviews/web-cal-views/`).
- Source/test snapshot identifier: HEAD e8ba8421 plus the uncommitted test files `calendar-store.spec.ts` (sha256 prefix bdd8ef1fab7a6ab9) and `calendar-view.spec.ts` (c47510b1b32eafe8). The production files match HEAD: `calendar-store.ts` 7850da03c3b7d734, `occurrence-store.ts` f4c4516be83cd67e, `calendar-views.ts` 0959a0def21777ef, `calendar-view.ts` 7e7a2686f76e426b, `calendar-view.html` 45c942da992f6e31, `calendar.routes.ts` ce0f53fe669c96e8. No input changed between the runs.
- Exact included files: `web/src/app/features/calendar/views/{calendar-store.ts, occurrence-store.ts, calendar-views.ts, calendar-view.ts, calendar-view.html}` and `web/src/app/features/calendar/calendar.routes.ts`. Excluded: `views/testing.ts` (a test fixture, reported for information only), `calendar-view.scss` (styles), `web/angular.json` (build config, checked by `ng build` and the e2e run), the spec files and `web/e2e/calendar-views/views.spec.ts`. No files were renamed or deleted.
- Provider/version, command, working directory, and test selection:
  - Official run: `npm run coverage` in `web/`, with Vitest 5.0.5 and `@vitest/coverage-v8`. It ran 2026-10-20T22:47:44Z-22:48:12Z and exited 2 (1 failed test). It generated no `coverage/web/` report.
  - Diagnostic run: `npx ng test --watch=false --coverage --coverage-reporters=text-summary --coverage-reporters=json-summary --coverage-reporters=json --include='src/app/features/calendar/**/*.spec.ts' --filter='^(?!.*keeps a newer calendar from a list answer over a save answer that arrives after it)'` in `web/`. It ran only the 4 calendar spec files: 66 passed, 1 left out by the filter. It ran 2026-10-20T22:48:24Z-22:48:30Z and exited 0.
- Generated report paths and hashes: `web/coverage/web/coverage-summary.json` (sha256 f93bd0770228d78f...) and `web/coverage/web/coverage-final.json` (sha256 1646315dc8c21cb4...). The diagnostic run created both; neither existed after the failed official run.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report files are dated 00:48:30 local time, from the diagnostic run.
- Final run after latest test additions and production fixes: yes for the test additions. TW-1 still needs a production fix, after which coverage must be measured again.

Values are covered/total (percentage), from the diagnostic run.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/calendar/views/calendar-store.ts` | 67/67 (100%) | 25/25 (100%) | 24/24 (100%) | 51/51 (100%) |
| `web/src/app/features/calendar/views/occurrence-store.ts` | 39/39 (100%) | 15/15 (100%) | 5/5 (100%) | 33/33 (100%) |
| `web/src/app/features/calendar/views/calendar-views.ts` | 22/22 (100%) | 24/24 (100%) | 7/7 (100%) | 19/19 (100%) |
| `web/src/app/features/calendar/views/calendar-view.ts` | 53/53 (100%) | 20/21 (95.24%) | 19/19 (100%) | 44/44 (100%) |
| `web/src/app/features/calendar/views/calendar-view.html` | 66/66 (100%) | 6/6 (100%) | 4/4 (100%) | 35/35 (100%) |
| `web/src/app/features/calendar/calendar.routes.ts` | 2/2 (100%) | 0/0 (N/A) | 1/1 (100%) | 2/2 (100%) |
| Aggregate (summed counters, the 6 files above) | 249/249 (100%) | 90/91 (98.90%) | 60/60 (100%) | 184/184 (100%) |

The aggregate excludes `testing.ts` (2/2 on every metric), `calendar-view.scss` and `angular.json`. Every scoped file appears in the report.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `web/src/app/features/calendar/views/calendar-view.ts:44:30` (1 `if` branch, mapped to the `@Component` metadata) | None. The Angular compiler emits this branch; it is not task logic. | Left uncovered as non-material |
| `web/src/app/features/calendar/views/calendar-store.ts:93` (covered, but its behavior is wrong) | Merging a save answer after a newer list answer | TW-1 |

## Unable to test

- FullCalendar's real rendering, and live SSE over a real connection, cannot be tested with unit tests. The e2e spec covers both, and it passed in this run (AC4).
- None needs follow-up apart from TW-1.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion. I added 5 tests for CR-1 and CR-2. Four of them pass. One exposes TW-1 (Low severity): in `calendar-store.ts:93`, the answer of a save overwrites a newer calendar state without comparing versions. Until it is fixed, the focused suite and `npm run check` fail. Every other check passed:
- lint and typecheck;
- the build;
- the Playwright DST spec against the e2e stack.

Coverage is partial: the numbers come from a diagnostic run that leaves out the TW-1 test, and they must be measured again after the fix.
