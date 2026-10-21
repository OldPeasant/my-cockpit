# Test Report

Date: 2026-10-21

Task: p4-smoke - Phase 4 demo script

Scope: `git diff df0186eccdd19be6f8b20a043ea39587b6617a18..1e0068034` (final reviewed revision 1e0068034). Task commits a0c1c6726, 07efd18dc, 75142a88a (implementation) and 1e0068034 (the test-writer's spec edit, committed by the parent), supplied by the parent and matching the task's `touches`. Changed files: `server/src/test/kotlin/ch/sonensei/cockpit/calendar/smoke/Phase4CalDavDemoTests.kt`, `server/src/test/kotlin/ch/sonensei/cockpit/calendar/smoke/Phase4OAuthProvidersDemoTests.kt`, `web/e2e/phase4/demo.spec.ts`.

Requirements:
- `tasks/p4-smoke.md` acceptance criterion and its two recorded assumptions (step 1 interpretation; step 4 provider split in server tests)
- `SPEC.md` §13.2 "Phase 4 — Calendar", steps 1-5
- `AGENTS.md` (Evidence rules), parent instructions

## Test files changed

- `web/e2e/phase4/demo.spec.ts` (test-writer edit, committed by the parent as 1e0068034)

## Tests added or updated

Count: 1 (test "1. a local calendar with a weekly event and one moved occurrence, correct across DST" strengthened)

What changed, in the day-view part of step 1:

- Added a day view of Monday 2026-03-23 (CET, before the DST change) asserting `9:00 – 10:00`. Before, the day view was only checked after the change (6 April), so "day view correct across a DST change" was checked on one side only.
- Added a day view of Tuesday 2026-03-31 asserting the moved occurrence at `2:00 – 3:00`.
- The absence check on the day view of 2026-03-30 could pass vacuously: the page shows no occurrence-loading indicator (`calendar-view.html` has none for occurrences), so `toHaveCount(0)` right after `page.goto` succeeded on the empty grid before any events arrived. It now waits for the `GET /api/v1/calendar/events` response of that page (registered before `goto`, asserted `ok`) and for the `data-date="2026-03-30"` column to be visible before checking the absence.

## Requirement coverage

All results were run by the parent; the test-writer had no shell access to the toolchain or e2e stack in this session.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Step 1: local calendar, weekly event, one moved occurrence; day/week/month correct across DST | `demo.spec.ts` "1. a local calendar ..." (UI: settings add Local calendar, create weekly series, move 30 Mar to 31 Mar 14:00 with "Only this event"; week view both sides of 29 Mar at the same height; day view 23 Mar, 6 Apr, 31 Mar, absence on 30 Mar after its occurrences loaded; month view; API occurrences 08:00Z before / 07:00Z after) | `cd web && npm run e2e -- --project=phase-demos` against the podman compose e2e stack, at 1e0068034 | Pass |
| Step 2: CalDAV (Radicale) cockpit -> server, server change -> cockpit after sync | `Phase4CalDavDemoTests` "step 2 - ..." | `cd server && ./gradlew ktlintFormat integrationTest --tests 'ch.sonensei.cockpit.calendar.smoke.*'` | Pass (server files unchanged since this run) |
| Step 3: Google/Microsoft against WireMock recordings: incremental sync token/delta, 412 -> refetch | `Phase4OAuthProvidersDemoTests` "step 3 - Google ...", "step 3 - Microsoft ..." | same Gradle command | Pass. The (H) part with real accounts: Not testable here (human step per SPEC) |
| Step 4: "this and following" correct split on all providers | local: `demo.spec.ts` "4. ..."; CalDAV: `Phase4CalDavDemoTests` "step 4 - ..."; Google/Microsoft: existing `GoogleCalendarSyncTests` (line ~231) and `MicrosoftCalendarSyncTests` (line ~248) `scope=FOLLOWING` tests, which run in `./gradlew check` | e2e command above; Gradle smoke command above | Pass (e2e, CalDAV). Google/Microsoft split relies on pre-existing tests, confirmed present by inspection; their run is part of `./gradlew check`/`.taskcheck` (parent) |
| Step 5: task overlay shows due tasks in week view; "Create note for event" links both ways | `demo.spec.ts` "5. ..." | e2e command above | Pass |
| Earlier phase specs pass | `web/e2e/phase0/`, `phase1/`, `phase2/` in `phase-demos`; phase 3 Android demo `Phase3DemoRobolectricTest` | `cd web && npm run e2e -- --project=phase-demos` (phases 0,1,2,4: 24 passed, 1.1m); e2e stack on 127.0.0.1:18390 + `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.smoke.*' -Pe2eBaseUrl=http://127.0.0.1:18390/` | Pass (24 passed; Phase3DemoRobolectricTest 1 passed) |

## Robustness review (no change needed)

- `eventsOn` uses `hasText: /Swim practice(?! \()/`, so "Swim practice" never matches "Swim practice (new pool)"; correct.
- Other absence checks (week view 30 Mar, month view 30 Mar) follow positive checks on the same rendered page, so they are not vacuous.
- The week-view bounding-box comparison is done within one page across two "Next week" clicks with fixed `scrollTime`; it passed in both runs. It is the most layout-sensitive assertion; if it ever flakes, the API assertion and the `9:00 – 10:00` text still cover the DST requirement.
- The API assertions (`occurrences`, `series` with `UNTIL=20260413T` / no UNTIL) independently confirm the UI results, so a UI false positive would not hide a wrong server state.
- Step 5 asserts the overlay mark positively (count 1 and class `app-task-due`) and both link directions, then reopens the event and finds the note link: not vacuous.
- `Phase4CalDavDemoTests`: reads the `.ics` from Radicale directly (independent of the cockpit), checks the TZID wall-clock times, the stale title before sync and the new title/start after, and that the stored ETag equals the server ETag. Step 4 checks UNTIL replaces COUNT on the old series, the new series' COUNT=4 and DTSTART, and that re-sync keeps exactly six occurrences.
- `Phase4OAuthProvidersDemoTests`: first sync, incremental via stored cursor, and 412 -> 409 with refetched provider version; `writes()` asserts that exactly one PATCH went out, so a silent retry/overwrite would fail. Fixed `MutableClock` makes the Microsoft window deterministic. `@DirtiesContext` closes the WireMock-bound context.
- All data is fictitious (`kim.example`, `alice`/`fictitious-secret`, `alex.example@example.test`).

## Focused test execution

Command:

`cd web && npx tsc -p e2e/tsconfig.json && npx eslint e2e/phase4 && npx prettier --check e2e/phase4`; `cd server && ./gradlew ktlintFormat integrationTest --tests 'ch.sonensei.cockpit.calendar.smoke.*'`

Result:

Pass (type check, lint and format clean at 1e0068034; server smoke: Phase4CalDavDemoTests 2 passed, Phase4OAuthProvidersDemoTests 2 passed, 0 failures; run by the parent).

## Full test execution

Command:

`cd web && npm run e2e -- --project=phase-demos` (podman e2e stack); `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.smoke.*' -Pe2eBaseUrl=http://127.0.0.1:18390/`; `cd server && ./gradlew integrationTest`

Result:

Pass for phase-demos (24 passed, 1.1m) and the phase 3 Android demo (1 passed). Full `./gradlew integrationTest`: Not run to completion at the time of this report (still running in the parent's session); the parent records its result.

## .taskcheck

Result:

Not run by the test-writer (no shell access); the parent runs it. The changed files are not part of `.taskcheck` (e2e and `@ExternalTest` suites are excluded from it).

## Bugs found

None.

## Coverage

Status: not applicable. The task changes only test code (two `@ExternalTest` Kotlin classes and one Playwright spec); there are no task-changed production files, so the task coverage scope is empty. Coverage of production code by end-to-end/external demo tests is not meaningful for this task, and an empty scope is not reported as 100%.

Metric: whole-file coverage of task-changed production files: N/A (no production files in scope).
Changed-line coverage: not measured (no production lines changed).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: p4-smoke; base df0186eccdd19be6f8b20a043ea39587b6617a18; final revision 1e0068034; commits a0c1c6726, 07efd18dc, 75142a88a, 1e0068034 from the parent, consistent with the 3 test files in the diff.
- Source/test snapshot identifier (including uncommitted inputs): commit 1e0068034 (the spec edit is committed; no uncommitted test inputs).
- Exact included files; renamed/deleted/excluded files and reasons: included production files: none. Excluded: the three test files (tests, not production).
- Provider/version, command, working directory, and test selection: none run (not applicable).
- Run start/completion time and exit status: N/A.
- Generated report paths and report hashes/run identifier: N/A.
- Evidence outputs are fresh and inputs stayed unchanged: N/A.
- Final run after latest test additions and production fixes: yes for the e2e and Android demo runs (at 1e0068034); server smoke run predates 1e0068034 but the server files did not change in it.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| (no production files in scope) | N/A | N/A | N/A | N/A |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| N/A | N/A | No production code in scope |

## Unable to test

- The test-writer could not run commands itself in this session (shell denied); every result above comes from the parent's runs, as stated per row.
- Full `./gradlew integrationTest`: still running when this report was written; the parent records the result.
- Step 3 (H) and real-provider parts of step 4: need real Google/Microsoft accounts; a human step per SPEC §13.2.
- Month view times across DST: the month view is checked for presence on the right days, not for the displayed time (the dayGrid time format was not verified); DST correctness is covered by the week view, the day views on both sides and the API instants. Not a blocking gap.

## Summary

Tests added and all validation passed (excluding the full `./gradlew integrationTest`, still running, which the parent records). One existing demo test was strengthened (day view on both sides of DST, non-vacuous absence check on 30 March); no production defects found; coverage not applicable because the task is test-only.
