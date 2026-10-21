# Code Review

Date: 2026-10-21

Task: and-cal-agenda - Android calendar agenda and day/week

Scope: committed task changes 8e5f7fbd05229c12013c964e43f3b76d27010a23..b5b87d30f64cbecb6c3b262ed81f48b678e1b6d2 (android/app/src/{main,test}/.../calendar/agenda, strings_calendar_agenda.xml). The test-writer's uncommitted file (CalendarViewModelEdgeCasesTest.kt) was not part of the review.

Requirements:
- tasks/and-cal-agenda.md acceptance criteria (agenda list with infinite scroll by range, day/week views, calendar visibility; unit tests covering range loading and grouping by day, Robolectric/Compose UI tests for screens)
- SPEC.md §6 (calendars: name, color, visible, read-only), §9.2 item 4, §9.3 (read cache)
- docs/ARCHITECTURE.md (Android MVVM, conventions), AGENTS.md project notes
- api/openapi.yaml: GET /api/v1/calendar/calendars, PATCH /api/v1/calendar/calendars/{id}, GET /api/v1/calendar/events; server ETags.of format

Git range:
- Branch: main
- HEAD: b5b87d30f64cbecb6c3b262ed81f48b678e1b6d2
- Base/start: 8e5f7fbd05229c12013c964e43f3b76d27010a23
- Commits reviewed: 2 (85ef6a67, b5b87d30)

Summary: 0 P0, 0 P1, 2 P2, 2 P3

Verified as correct: the occurrences call matches the contract (from/to date-times, server default = visible calendars, ranges split below the 400-day limit, overlaps de-duplicated by key, cancelled occurrences dropped); PATCH sends only `visible` with `If-Match: "<version>"`, which is the server's ETag format; 412/4xx -> Failed, 5xx/IO -> Offline; switching views cancels the running load (one `loadJob`, no out-of-order overwrite); loading ends on every path; the failure banner has a retry; grouping by local day handles multi-day events, exclusive midnight ends and DST in the tested zone; overlapping layout is tested. No secrets or sensitive logging.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Overlapping visibility toggles refuse themselves and stale calendar lists overwrite optimistic state**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarViewModel.kt:158`
    - Category: Correctness
    - Problem: `setVisible` flips the chip optimistically but keeps the old `version`, and the chip is not disabled while the PATCH runs. A second tap on the same chip before the first PATCH returns sends another PATCH with the same (now stale) `If-Match`; the server answers 412 after the first succeeded, so the user's last choice is reverted and "visibility failed" is shown. The failure branch (line 175) also restores the pre-tap `calendar` snapshot, which can overwrite the newer version written by the first request's Ok branch until `refreshCalendars` returns. Independently, `load()` (line 243) and `refreshCalendars()` (line 187) write `calendars` from a list fetched before a pending PATCH, flipping the chip back to the old value while the PATCH is in flight; a tap in that window sends the wrong target value with a stale version.
    - Why it matters: overlapping writes on the same entity produce a self-inflicted conflict error and a visible state that does not match what the user chose (SPEC §4.2 optimistic locking is meant for concurrent edits by other clients, not double taps).
    - Suggested fix: track pending visibility writes per calendar id: ignore or queue taps on a calendar whose PATCH is running (or disable its chip), and when applying a fetched calendar list keep the optimistic `visible` of calendars with a pending write. On failure, revert only if no newer result for that calendar was applied. Add a ViewModel test for a double toggle.

- [ ] **CR-2 | Agenda has no offline read cache although SPEC §9.3 lists it, and the deviation is not recorded**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarRepository.kt:66`
    - Category: Architecture
    - Problem: SPEC.md §9.3 says "last-loaded lists (Inbox, Next, Today, agenda) are cached in Room and shown read-only with an 'offline' banner when the server is unreachable". The repository is documented as "no local cache, every view loads what it shows" (also `CalendarViewModel.kt:63-64`), so opening the calendar offline shows an empty agenda with the offline banner. No other task on the board covers an agenda cache (and-gtd-data covers only Inbox/Next/Today/Projects), and the task file records no assumption for this decision.
    - Why it matters: AGENTS.md requires deviations from SPEC.md to be recorded with `tasks.py assume`; without that, the missing feature is invisible to humans and no task picks it up.
    - Suggested fix: the parent worker decides scope. Either record the assumption (agenda cache deferred, not in this task's acceptance criteria) and add a follow-up task (e.g. `and-cal-cache`, touching calendar/agenda), or implement a small Room cache of the last loaded agenda range.

## P3 Low

- [ ] **CR-3 | Failure and supersession paths of the range loading are untested**
    - File: `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarViewModelTest.kt:58`
    - Category: Tests
    - Problem: the committed tests cover the happy path of `loadMore`, but not a failed `loadMore` (range must stay at the old `to`, `loadingMore` must end, message set), nor a slow load that is superseded by a view switch (its late result must not overwrite the new range), nor `refresh()` after the day changed resetting the agenda to the new today.
    - Why it matters: these are the regression-prone paths of "infinite scroll by range"; the current cancellation logic is correct but unguarded.
    - Suggested fix: add ViewModel tests with a suspending fake (`coAnswers { awaitCancellation() }` or a `CompletableDeferred`) for supersession, an Offline `loadMore`, and a clock moved past midnight before `refresh()`. (The test-writer may already be adding some of these in an uncommitted file.)

- [ ] **CR-4 | The "now" line in the day/week grid never moves**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt:711`
    - Category: Quality
    - Problem: `NowLine` reads `LocalTime.now(zone)` once during composition; nothing triggers recomposition as time passes, so with the screen open the line stays at the time the grid was first composed.
    - Why it matters: a stale current-time marker is misleading in the day view.
    - Suggested fix: hold the time in state updated by a `LaunchedEffect` loop (e.g. once a minute) and offset the line from it.

## Review conclusion

Material issues found; fix before completing the task.

The acceptance criteria are implemented (agenda with range-based infinite scroll, day/week views, server-stored calendar visibility) and the tests cover range loading and grouping by day plus Compose screen tests. The findings are P2/P3: CR-1 should be fixed or explicitly accepted; CR-2 needs at least a recorded assumption and a follow-up task.
