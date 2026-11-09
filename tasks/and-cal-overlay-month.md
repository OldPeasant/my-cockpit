---
title: Android calendar: task overlay and month view
status: in_progress
owner: dell26/agent-2
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda,android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda,android/app/src/main/res/values/strings_calendar_agenda.xml,android/app/src/main/res/values-de/strings_calendar_agenda.xml]
prio: 2
lease_until: 2026-11-09T02:32:56Z
extended: 
releases: 0
hold: 
---
## Goal
SPEC.md §6.3 and the phase 4 demo want the task/tickler overlay on Android too; web also has a month view.

## Acceptance criteria
- [x] Day/week/agenda show due and scheduled tasks and ticklers as an overlay (toggle), tap opens the task
  - verified (2026-11-08 20:55+0100 by rudi/agent-2): sh scripts/check.sh tfp android (ktlint + testDebugUnitTest + lintDebug) at final HEAD -> all checks passed; CalendarViewModelOverlayTest (26), CalendarOverlayMonthScreensTest (28) and overlay repository/marks tests cover day/week/agenda marks, overflow-menu toggle persisted off by default, tap opens task via LocalTaskOpener -> 0 failures
- [x] A month view (dots or compact titles per day; tap a day opens the day view)
  - verified (2026-11-08 20:55+0100 by rudi/agent-2): same sh scripts/check.sh tfp android run: month-view tests in CalendarOverlayMonthScreensTest and monthGrid tests (whole weeks 4-6, up to 3 titles + '+n', tap day -> DAY view, plusMonths paging, 360dp layout) -> pass
- [x] New strings are in English and German (`res/values/` + `res/values-de/`)
  - verified (2026-11-08 20:55+0100 by rudi/agent-2): python3 compare of name= keys in res/values/strings_calendar_agenda.xml vs res/values-de/ -> 34 == 34, identical sets; Compose tests render EN and DE -> pass
- [~] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass
  - verified (2026-11-08 20:55+0100 by rudi/agent-2): cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug (via sh scripts/check.sh tfp android) -> exit 0, all checks passed; test-writer run: 4584 tests 0 failures, changed-file coverage 99.2% lines / 92.0% branches. Full sh scripts/check.sh not runnable here: server step needs Docker (socket permission denied), web step needs Node >=22 (host v20.9.0); task touches only android/
  - skipped (2026-11-08 20:55+0100 by rudi/agent-2): Android part verified: cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug (via sh scripts/check.sh tfp android) -> exit 0, 4584 tests 0 failures. Full sh scripts/check.sh cannot run on this host: server step needs Docker (socket permission denied), web step needs Node >=22 (host v20.9.0); task touches only android/

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 17:26+0100 by rudi/agent-1): Android overlay: all task marks (incl. due-with-time) are shown in the all-day strip of day/week (time in the label of the day view), not placed in the hour grid like the web; keeps the grid layout code untouched
- ASSUMPTION (2026-11-08 17:27+0100 by rudi/agent-1): Scheduled tasks come from GET /tasks?list=SCHEDULED filtered client-side by scheduledDate, because the server's from/to filter only matches due and tickler dates
- ASSUMPTION (2026-11-08 17:27+0100 by rudi/agent-1): Overlay toggle is remembered per device in SharedPreferences (web remembers it per browser), off by default; the overlay is also shown in the agenda and month view
- ASSUMPTION (2026-11-08 17:27+0100 by rudi/agent-1): Month view shows up to 3 compact titles per day (events, then overlay tasks) plus +n; tapping a day opens the day view; offline the month view is shown only if cached (same rule as day/week)
- extended by rudi/agent-1: Implemented and committed: overlay toggle in day/week/agenda, month view, EN/DE strings, tests (82d62eb9, aedd2700). code-reviewer report reviews/and-cal-overlay-month/review.md has 3 open findings: CR-1 (P2, a task scheduled for today is missing because the server tickler releases it to NEXT; mark releasedOn==today as SCHEDULED or record an assumption), CR-2 (P2, toolbar too narrow on 360dp with 4 segments + 2 toggles), CR-3 (P3, call loadTasks(append) after the state update in loadMore). Left: fixer for CR-1..3, test-writer (tests.md missing), re-run code-reviewer, tasks.py verify #1-4, quality.json, done. Note: sh scripts/check.sh currently fails in server tests with 'Could not find a valid Docker environment' (Docker unavailable on this host, not caused by this task).
- ASSUMPTION (2026-11-08 18:24+0100 by rudi/agent-1): Overlay and calendars toggles moved into a toolbar overflow menu and Today became an icon button, so 4 view segments fit on 360dp phones (review CR-2); tasks released by the tickler today (GET /tasks?scheduled=today, releasedOn==today) are shown as scheduled marks on today (CR-1)
- release by rudi/agent-1: Implementation complete and committed (overlay toggle in day/week/agenda via toolbar overflow menu, month view, EN/DE strings, tests; review fixes CR-1..3 done, fixes.md committed). Re-review reviews/and-cal-overlay-month/review.md: 0 findings, but its commit hashes are pre-rebase and extra tests were committed after it (8272f99a). Left: 1) run test-writer -> reviews/and-cal-overlay-month/tests.md (missing, needs coverage numbers); 2) re-run code-reviewer on final code if test-writer changes code (tests count); 3) tasks.py verify #1-4 (cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug; sh scripts/check.sh - previously failed in server tests with 'Could not find a valid Docker environment', unrelated to this task); 4) write quality.json (fixer was run, fixes.md); 5) done.
- release by rudi/agent-2: Implementation, review rounds 1-2 fixes (fixes.md) and extra tests committed (HEAD 99d2a71a); cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug passes at 99d2a71a. Latest review.md (round 3, scope 5566785...392b2b72): 0 P0-P2, 1 open P3 CR-1 (add state.today to the remember keys of taskMarks in CalendarOverlayMonth.kt:224 and CalendarScreen.kt Agenda/TimeGrid). Left: 1) fixer for P3 CR-1 (or record accepted disposition); 2) re-run test-writer (tests.md must cover final code incl. commit 99d2a71a tests, with coverage numbers) and code-reviewer on final code; 3) tasks.py verify #1-4; 4) sh scripts/check.sh (earlier failed only in server Testcontainers tests: no Docker on host, unrelated); 5) write+commit quality.json; 6) done.
- ASSUMPTION (2026-11-08 20:55+0100 by rudi/agent-2): done runs with TASK_CHECK='sh scripts/check.sh tfp android': this host has no Docker access (server Testcontainers fail) and Node v20.9 (Angular needs >=22); the task changes only android/, server and web parts are checked by CI and other hosts
- hold by rudi/agent-2: All work, review evidence (quality.json, reviewed_revision 6816f515) and verify #1-3 committed; #4 skipped for full check. Only 'done' is left. It fails on this host (rudi): sh scripts/check.sh server step has 424 Testcontainers failures (Docker socket permission denied) and the web step needs Node >=22 (host v20.9.0). 'sh scripts/check.sh tfp android' passes. A human must either run 'python tasks.py done and-cal-overlay-month' on a host with Docker + Node 22, or give this host Docker access/Node 22 (or allow TASK_CHECK='sh scripts/check.sh tfp android').
