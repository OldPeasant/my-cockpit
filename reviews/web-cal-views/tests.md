# Test Report

Date: 2026-10-21

Task: web-cal-views - Calendar views (SPEC.md §6.3)

Scope: task commits c9628772, e203dea5, db91b54c on top of base 63d16ee20ba13bf7317a41ae736998279a5d4177; measured HEAD db91b54c72b6df630f1a055b743d4bf27b9d6864 (`git diff 63d16ee2..HEAD -- web`), plus the uncommitted test-file changes made by this test-writer run (listed below).

Requirements:
- tasks/web-cal-views.md acceptance criteria 1-4 and its recorded assumptions (server-persisted visibility flag with client-side filtering; global FullCalendar CSS; view/date in the URL, agenda = listWeek)
- SPEC.md §6.3 (calendar views), §4.2 (If-Match optimistic locking)
- docs/ARCHITECTURE.md "Coverage"; docs/QUALITY.md
- Parent instructions (edge cases: out-of-order answers, SSE refresh, toggle rollback/serialization, settings-driven options, URL parsing)

## Test files changed

- `web/src/app/features/calendar/views/calendar-store.spec.ts`
- `web/src/app/features/calendar/views/occurrence-store.spec.ts`
- `web/src/app/features/calendar/views/calendar-views.spec.ts`
- `web/src/app/features/calendar/views/calendar-view.spec.ts`

No production file, e2e spec or configuration was changed. The existing 35 tests were kept unchanged; in `calendar-view.spec.ts` only the `ChangeEventsService` fake of the `render()` helper was changed to use shared `resyncs`/`changes` subjects, so tests can emit SSE events.

## Tests added or updated

Count: 24 added (35 -> 59 tests in the four calendar spec files), 0 weakened or removed.

- `CalendarStore` (+7): older failed list request ignored after a newer success; a later toggle stays shown when an earlier save of the same calendar fails (and the second save still sends the list version); rollback to the saved state when the later of two toggles fails; toggles of different calendars saved one after the other with their own versions; an unsaved toggle stays shown over a list reloaded by a resync; a queued toggle of a calendar dropped by a resync saves with its own version and rolls back on 404.
- `OccurrenceStore` (+4): stays loading while a newer request is pending when an older one answers; a pending answer is dropped when the calendars become empty; the answer of an SSE refetch started while a load was pending wins; a failed refetch keeps the shown occurrences and sets the error.
- `calendar views` helpers (+3, 1 extended): leap day / out-of-range / decorated / empty dates rejected; empty, prototype (`__proto__`, `constructor`) and upper-case view names fall back to week; `dateIn` across the DST change; `firstDay` for Wednesday and an unknown value (falls back to 0).
- `calendar routes` (+1): `/calendar` lazy-loads `CalendarView` with the title "Calendar".
- `CalendarView` (+10): invalid URL view/date fall back to week/no date; day and agenda views from the URL; changed settings update `timeZone`/`firstDay`; URL date is computed in the settings time zone (New York) with `merge` + `replaceUrl`; out-of-order range answers at component level show the newest range; a SSE resync reloads calendars and refetches occurrences, a new calendar from the resync is included in the refetch and the stale answer is dropped; an `EVENT` change notification refetches the same range and shows the new event; checking a hidden calendar shows its events without a refetch and saves `visible: true`; calendar colors in the side panel (with theme fallback) and occurrences of unknown calendars hidden; a failed reload after a resync keeps the calendar list.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 FullCalendar with week (default), day, month, list views | `shows the week view by default ...`, `opens the view and date of the URL`, `opens the day and agenda views of the URL`, `falls back to the week view for an invalid URL view and date`, `maps URL view names to FullCalendar views and back, week by default`, `ignores empty and prototype view names from the URL`, `lazy-loads the calendar view at /calendar` | `cd web && npx ng test --watch=false --include='src/app/features/calendar/**/*.spec.ts'` | Pass |
| AC1 week start + time zone from settings | `shows the week view by default in the time zone and week start of the settings`, `follows changed settings (time zone and week start)`, `maps the week start setting to FullCalendar firstDay`, `puts the view and the date in the settings time zone into the URL`, `formats the date of an instant ...` (2 tests), `loads the settings first ...`, `offers a retry when the settings cannot be loaded` | same | Pass |
| AC1 calendar visibility toggles with colors | `toggles a calendar without refetching its events`, `shows a calendar again and its events when it is checked`, `reports a toggle the server refuses and shows the calendar again`, `shows each calendar with its color and hides occurrences of unknown calendars`, all `CalendarStore` toggle tests (If-Match, rollback, serialization, resync overlay, dropped calendar), `falls back to the theme color ...` | same | Pass |
| AC2 loads occurrences for the visible range | `loads the occurrences of the visible range for all calendars and puts it in the URL`, `waits for the calendars before loading occurrences`, `loads the occurrences of the range for the given calendars`, `asks nothing without calendars`, `keeps the previous occurrences while the next range loads` | same | Pass |
| AC2 out-of-order safe | `drops a slower answer for an earlier range (out of order)`, `stays loading while a newer request is pending ...`, `drops a pending answer when the calendars become empty`, `keeps the error of the newest request only`, `shows the occurrences of the newest range when answers arrive out of order` (component), `drops an older list answer ...`, `ignores the error of an older list request ...`, `ignores a list answer requested before a saved toggle` | same | Pass |
| AC2 live updates via SSE | `refetches the range on an event change and on a resync`, `shows the answer of a refetch that started while a load was pending`, `keeps the shown occurrences when a refetch fails`, `does not fetch on events before the first load`, `reloads after a SSE resync`, `refetches the range on a SSE resync and with the ids of new calendars`, `shows an event created elsewhere after its change notification`, `keeps the calendar list when a reload after a resync fails` | same | Pass |
| AC3 Vitest tests cover range loading and toggles; `npm run check` passes | all of the above | `cd web && npm run check` | Pass (exit 0; 55 files / 959 tests; lint clean; build OK). One earlier run failed on an unrelated flaky test, see "Full test execution". |
| AC4 Playwright DST week spec against the e2e stack | `e2e/calendar-views/views.spec.ts` › `weekly event shows correctly across the DST week` (unchanged by this run) | `cd web && npm run e2e -- --no-deps e2e/calendar-views/views.spec.ts` | Not run by this test-writer: I did not change the spec, and the parent reports it passed once against the podman e2e stack. The parent records its own e2e run as evidence. `npm run e2e:typecheck` (part of `npm run check`) passes. |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/calendar/**/*.spec.ts'`

Result:

Pass (4 files, 59 tests, final run after the last test changes).

## Full test execution

Command:

`cd web && npm run check` (api:check, lint `--max-warnings=0`, e2e:typecheck, `ng test --watch=false`, `ng build`)

Result:

Pass on the final snapshot: exit 0, "All files pass linting.", 55 test files / 959 tests passed, build succeeded. The build prints a non-failing warning, "bundle initial exceeded maximum budget ... 634.21 kB" (500 kB budget). The task's assumption on the global FullCalendar CSS accounts for about 21 kB of it, and I did not check whether the rest was over budget before this task.

One intermediate run of the same command (same snapshot) failed in an unrelated spec: `src/app/shared/links/linked-items.spec.ts > LinkedItems > changing the item > closes the open search without moving the focus` ("Expected one matching request ... found none"). It passed in the immediately following `npm run coverage` run and in a repeated `npm run check` run (exit 0). It is outside this task's scope and looks flaky (timing-dependent). It may need a separate follow-up.

## .taskcheck

Result:

Not run by the test-writer (`sh scripts/check.sh` covers server and android as well). The web part of it (`npm run check`) passed as above. The parent runs `.taskcheck` before `done`.

## Bugs found

None. No production defect was found. All new tests pass against the implementation.

Observation (not a defect against the criteria, no fix requested): in `CalendarStore.save` (`calendar-store.ts:88`), every saved toggle increments `requestSeq`. A list request that was started by a resync before the save finished is then discarded, even if it carries other changes (e.g. a newly added calendar). The next resync or reload shows them. This is a deliberate trade-off documented in a code comment.

## Coverage

Status: verified (fresh run on the final snapshot, exit 0).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately with a diff intersection. All in-scope files except `calendar.routes.ts` are new in this task, so for them whole-file coverage equals changed-line coverage. In `calendar.routes.ts` all 2/2 lines are covered. Every executable line in scope is covered (179/179), so changed-line coverage is necessarily 100% as well. Changed-branch coverage is not inferred from this.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-cal-views`; base 63d16ee20ba13bf7317a41ae736998279a5d4177; HEAD db91b54c72b6df630f1a055b743d4bf27b9d6864; task commits c9628772, e203dea5, db91b54c as given by the parent worker, checked with `git diff --stat 63d16ee2..HEAD` (15 files, all under `web/` and matching the task's touches and recorded assumptions).
- Source/test snapshot identifier (including uncommitted inputs): HEAD db91b54c plus uncommitted test changes (sha256 prefixes): `calendar-store.spec.ts` 0fa5eb81dcea3e4d, `calendar-view.spec.ts` 86898b31ebbb9045, `calendar-views.spec.ts` e4aac336829dee04, `occurrence-store.spec.ts` fc8be7a98a36a041. Production files are unchanged from HEAD: `calendar-store.ts` 02073eaef1b01a6e, `occurrence-store.ts` f4c4516be83cd67e, `calendar-views.ts` 0959a0def21777ef, `calendar-view.ts` 7e7a2686f76e426b, `calendar-view.html` 45c942da992f6e31, `testing.ts` e400ccd8f1e4cde5, `calendar.routes.ts` ce0f53fe669c96e8. All hashes were the same before and after the coverage run.
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/features/calendar/views/{calendar-store.ts, occurrence-store.ts, calendar-views.ts, calendar-view.ts, calendar-view.html}` and `web/src/app/features/calendar/calendar.routes.ts`. `views/testing.ts` (a test-fixture helper, not production) is reported for information only and is not in the aggregate. Excluded: `calendar-view.scss` (styles, no executable code in the V8 report), `web/angular.json` (build config; its effect is covered by `ng build` and the e2e spec), `web/src/app/app.spec.ts`, the four `*.spec.ts` files and `web/e2e/calendar-views/views.spec.ts` (tests). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + `@vitest/coverage-v8` ^5.0.5 (Node v24.21.0), `npm run coverage` (`ng test --watch=false --coverage` with text-summary, json-summary, json, lcov reporters), working directory `web/`, full web suite (55 files, 959 tests).
- Run start/completion time and exit status: 2026-10-20T22:38:19Z to 2026-10-20T22:38:45Z, exit 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` (sha256 8057a6e17696fd07...), `web/coverage/web/coverage-final.json` (sha256 f9701edfe41215dc...), `web/coverage/web/lcov.info`; log in `.tmp/web-cal-views-coverage.log`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. Vitest rewrote `coverage/` during this run (file mtimes 00:38 local). The numbers differ from the previous run of this session (before the last 2 tests were added: calendar-store branches 20/21, calendar.routes 1/2), which shows the report was regenerated. Source hashes were identical before and after.
- Final run after latest test additions and production fixes: yes (no production fixes were made, and the run came after the last test change).

Values below use covered/total (percentage) from the provider's per-file counters in `coverage-summary.json`; uncovered locations from `coverage-final.json`.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/calendar/views/calendar-store.ts` | 60/60 (100%) | 21/21 (100%) | 20/20 (100%) | 46/46 (100%) |
| `web/src/app/features/calendar/views/occurrence-store.ts` | 39/39 (100%) | 15/15 (100%) | 5/5 (100%) | 33/33 (100%) |
| `web/src/app/features/calendar/views/calendar-views.ts` | 22/22 (100%) | 24/24 (100%) | 7/7 (100%) | 19/19 (100%) |
| `web/src/app/features/calendar/views/calendar-view.ts` | 53/53 (100%) | 20/21 (95.23%) | 19/19 (100%) | 44/44 (100%) |
| `web/src/app/features/calendar/views/calendar-view.html` | 66/66 (100%) | 6/6 (100%) | 4/4 (100%) | 35/35 (100%) |
| `web/src/app/features/calendar/calendar.routes.ts` | 2/2 (100%) | 0/0 (N/A) | 1/1 (100%) | 2/2 (100%) |
| Aggregate (summed counters, 6 files above) | 242/242 (100%) | 86/87 (98.85%) | 56/56 (100%) | 179/179 (100%) |
| (info only, test helper) `web/src/app/features/calendar/views/testing.ts` | 2/2 (100%) | 2/2 (100%) | 2/2 (100%) | 2/2 (100%) |

Aggregate excludes `testing.ts` (test helper), `calendar-view.scss` and `angular.json` (non-executable/config). No scoped production file is missing from the report.

Coverage before this run's test additions (fresh run at 2026-10-20T22:36:40Z on the same production code): `calendar-store.ts` branches 20/21 (`calendar-store.ts:80`, the `?? calendar` fallback was not hit) and `calendar.routes.ts` statements 1/2 and functions 0/1 (the lazy `loadComponent` was not called). Two added tests now cover both. All other files were already at the values above.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `web/src/app/features/calendar/views/calendar-view.ts:44:30` (1 `if` branch, source-mapped to the `@Component` metadata) | None: an Angular compiler-emitted branch (component metadata/dev-mode guard), not task logic. | Left uncovered as non-material; no meaningful test exists for it. |
| `web/src/app/features/calendar/views/calendar-store.ts:80` (`?? calendar` fallback) | A queued toggle for a calendar that a resync removed from the list | Covered now by `saves a queued toggle with its own version when a resync dropped the calendar` |
| `web/src/app/features/calendar/calendar.routes.ts:7` (lazy `loadComponent`) | `/calendar` must load the calendar view (AC1) | Covered now by `lazy-loads the calendar view at /calendar` (also exercised end to end by the e2e spec) |

## Unable to test

- Real FullCalendar rendering (event placement across the DST change, view switching buttons, `datesSet` ranges FullCalendar actually reports for a named time zone): unit tests mock `datesSet`. The e2e spec `e2e/calendar-views/views.spec.ts` covers this, and the parent reports it passed against the e2e stack. This test-writer did not re-run it because the spec was not changed. Follow-up: the parent records its e2e run for AC4.
- Live SSE over a real connection: unit tests drive `ChangeEventsService` fakes. The real path is covered by the "Live update" step of the e2e spec.
- Global FullCalendar CSS in `angular.json`: only verified by a successful `ng build` (and visually by the e2e run). No unit test applies.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. I added 24 tests (59 calendar tests in total), the focused suite and `cd web && npm run check` pass, and the task's production files have 100% statement, function and line coverage and 98.85% branch coverage (the one uncovered branch is compiler-generated). AC4 (Playwright against the e2e stack) was not re-run by this helper and remains for the parent's own evidence. There is one unrelated flaky test in `shared/links/linked-items.spec.ts`.
