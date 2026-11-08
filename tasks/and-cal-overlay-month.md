---
title: Android calendar: task overlay and month view
status: in_progress
owner: rudi/agent-1
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda,android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda,android/app/src/main/res/values/strings_calendar_agenda.xml,android/app/src/main/res/values-de/strings_calendar_agenda.xml]
prio: 2
lease_until: 2026-11-08T17:23:54Z
extended: rudi/agent-1
---
## Goal
SPEC.md §6.3 and the phase 4 demo want the task/tickler overlay on Android too; web also has a month view.

## Acceptance criteria
- [ ] Day/week/agenda show due and scheduled tasks and ticklers as an overlay (toggle), tap opens the task
- [ ] A month view (dots or compact titles per day; tap a day opens the day view)
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 17:26+0100 by rudi/agent-1): Android overlay: all task marks (incl. due-with-time) are shown in the all-day strip of day/week (time in the label of the day view), not placed in the hour grid like the web; keeps the grid layout code untouched
- ASSUMPTION (2026-11-08 17:27+0100 by rudi/agent-1): Scheduled tasks come from GET /tasks?list=SCHEDULED filtered client-side by scheduledDate, because the server's from/to filter only matches due and tickler dates
- ASSUMPTION (2026-11-08 17:27+0100 by rudi/agent-1): Overlay toggle is remembered per device in SharedPreferences (web remembers it per browser), off by default; the overlay is also shown in the agenda and month view
- ASSUMPTION (2026-11-08 17:27+0100 by rudi/agent-1): Month view shows up to 3 compact titles per day (events, then overlay tasks) plus +n; tapping a day opens the day view; offline the month view is shown only if cached (same rule as day/week)
- extended by rudi/agent-1: Implemented and committed: overlay toggle in day/week/agenda, month view, EN/DE strings, tests (82d62eb9, aedd2700). code-reviewer report reviews/and-cal-overlay-month/review.md has 3 open findings: CR-1 (P2, a task scheduled for today is missing because the server tickler releases it to NEXT; mark releasedOn==today as SCHEDULED or record an assumption), CR-2 (P2, toolbar too narrow on 360dp with 4 segments + 2 toggles), CR-3 (P3, call loadTasks(append) after the state update in loadMore). Left: fixer for CR-1..3, test-writer (tests.md missing), re-run code-reviewer, tasks.py verify #1-4, quality.json, done. Note: sh scripts/check.sh currently fails in server tests with 'Could not find a valid Docker environment' (Docker unavailable on this host, not caused by this task).
