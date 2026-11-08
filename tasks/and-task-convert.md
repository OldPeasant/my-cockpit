---
title: Android: convert tasks to notes and events
status: in_progress
owner: felix/agent-1
deps: [and-task-detail-dates,and-inbox-rows]
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/task,android/app/src/test/java/ch/sonensei/cockpit/gtd/task,android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox,android/app/src/test/java/ch/sonensei/cockpit/gtd/inbox,android/app/src/main/res/values/strings_task_detail.xml,android/app/src/main/res/values-de/strings_task_detail.xml,android/app/src/main/res/values/strings_inbox.xml,android/app/src/main/res/values-de/strings_inbox.xml]
prio: 2
lease_until: 2026-11-08T17:09:46Z
extended: 
releases: 1
---
## Goal
Web converts a task to a note (reference) or an event from task detail and inbox processing (`gtd-convert-note`,
`gtd-convert-event` server endpoints). Android has neither (`InboxActions.kt:15`).

## Acceptance criteria
- [ ] Inbox processing has "To reference" (converts to a note, opens it or shows a link) and "To calendar" (dialog: calendar, start, duration/all-day)
- [ ] Task detail has the same two actions in its menu
- [ ] After converting, the user sees where the item went with an "Open" action
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 16:13+0100 by dell26/agent-3): Edited ui/CockpitShell.kt (outside touches, 10 lines): it provides LocalConvertedOpener so the Inbox can open a converted note (search item route, as global search does) or the Calendar tab; no other way for the Inbox to navigate.
- ASSUMPTION (2026-11-08 16:13+0100 by dell26/agent-3): 'Open' for an event opens the Calendar tab (no per-event screen/route on Android); the snackbar names the event's day. Notes open in the note editor.
- ASSUMPTION (2026-11-08 16:13+0100 by dell26/agent-3): To calendar dialog: timed events in the device zone (ZoneId of the system clock), default start = next full hour, duration chips 15/30/60/90/120/180 min (default 60), all-day = one day; calendars = writable ones, default first (web parity).
- ASSUMPTION (2026-11-08 16:13+0100 by dell26/agent-3): Task detail: after converting, a Long snackbar 'Moved to ... / Open'; Open closes the detail and opens the item, dismiss closes the detail (like delete). Convert actions only for open (not completed) tasks. Inbox: To calendar under 'Defer it', To reference under 'Drop it'; inline clarification is not saved before converting (like Trash).
- extended by dell26/agent-3: Impl (4ebb4f268) + tests (a2668a93b, f0492d419) + reviews/and-task-convert/review.md committed. Left: run fixer for CR-1 (use synced settings.timeZone ?: clock.zone like EventEditorViewModel), CR-2 (cancel previous loadCalendars job; 2 failing regression tests InboxConvertViewModelTest/TaskDetailConvertViewModelTest 'calendars of an earlier ... answering last' cover it), CR-3 (confirm To reference or assume), CR-4 optional; then test-writer -> tests.md with coverage, re-run code-reviewer, verify #1-#5, quality.json, done. check.sh takes ~5 min.
- release by dell26/agent-3: Impl (9ba2576b5), tests (aa1bb9148, ca1381434, 509d011a2 TaskDetailConvertRouteTest), review.md + fixer for CR-1..CR-4 (9e8160904, fixes.md) committed; sh scripts/check.sh passes at 509d011a2. Left: run test-writer on final code -> reviews/and-task-convert/tests.md with coverage (fold in/validate TaskDetailConvertRouteTest); re-run code-reviewer (review.md is stale after fixes); tasks.py verify 1-5; write quality.json (docs/QUALITY.md); done. check.sh takes ~5 min.
