---
title: Android: convert tasks to notes and events
status: in_progress
owner: Peter Gürtler
deps: [and-task-detail-dates,and-inbox-rows]
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/task,android/app/src/test/java/ch/sonensei/cockpit/gtd/task,android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox,android/app/src/test/java/ch/sonensei/cockpit/gtd/inbox,android/app/src/main/res/values/strings_task_detail.xml,android/app/src/main/res/values-de/strings_task_detail.xml,android/app/src/main/res/values/strings_inbox.xml,android/app/src/main/res/values-de/strings_inbox.xml]
prio: 2
lease_until: 
extended: 
releases: 0
hold: 
---
## Goal
Web converts a task to a note (reference) or an event from task detail and inbox processing (`gtd-convert-note`,
`gtd-convert-event` server endpoints). Android has neither (`InboxActions.kt:15`).

## Acceptance criteria
- [x] Inbox processing has "To reference" (converts to a note, opens it or shows a link) and "To calendar" (dialog: calendar, start, duration/all-day)
  - verified (2026-11-08 19:03+0100 by felix/agent-1): cd android && ./gradlew testDebugUnitTest --tests '*Convert*' --tests '*Conversion*' --tests '*EventPlan*' --tests '*TaskDetailStringsTest' --tests '*InboxRepositoryTest' --tests '*TaskDetailRepositoryTest' at c24989938 -> 183 passed, 0 failed; InboxConvertViewModelTest (18) + InboxConversionRepositoryTest cover To reference (note, Open) and To calendar dialog (calendar, start, duration/all-day, synced zone)
- [x] Task detail has the same two actions in its menu
  - verified (2026-11-08 19:03+0100 by felix/agent-1): cd android && ./gradlew testDebugUnitTest --tests '*Convert*' --tests '*Conversion*' --tests '*EventPlan*' --tests '*TaskDetailStringsTest' --tests '*InboxRepositoryTest' --tests '*TaskDetailRepositoryTest' at c24989938 -> 183 passed, 0 failed; TaskDetailConvertRouteTest (Compose) + TaskConversionRepositoryTest cover both menu actions incl. To reference confirmation dialog
- [x] After converting, the user sees where the item went with an "Open" action
  - verified (2026-11-08 19:03+0100 by felix/agent-1): cd android && ./gradlew testDebugUnitTest --tests '*Convert*' --tests '*Conversion*' --tests '*EventPlan*' --tests '*TaskDetailStringsTest' --tests '*InboxRepositoryTest' --tests '*TaskDetailRepositoryTest' at c24989938 -> 183 passed, 0 failed; snackbar with Open in task detail and Inbox (LocalConvertedOpener), incl. ordering tests that only the latest action is shown
- [x] New strings are in English and German (`res/values/` + `res/values-de/`)
  - verified (2026-11-08 19:03+0100 by felix/agent-1): cd android && ./gradlew testDebugUnitTest --tests '*Convert*' --tests '*Conversion*' --tests '*EventPlan*' --tests '*TaskDetailStringsTest' --tests '*InboxRepositoryTest' --tests '*TaskDetailRepositoryTest' at c24989938 -> 183 passed, 0 failed; TaskDetailStringsTest checks new keys exist in res/values and res/values-de; lintDebug (MissingTranslation) clean
- [x] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass
  - skipped (2026-11-08 20:03+0100 by rudi/agent-2): On rudi host at 1d401070: cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug -> pass; sh scripts/check.sh tfp android -> all checks passed. Full sh scripts/check.sh cannot pass on this host: server Testcontainers tests need Docker (socket permission denied) and web needs Node >= 22.22.3 (host older); both unrelated to this Android-only change. done's .taskcheck run on a capable host is the remaining gate.
  - verified (2026-11-08 20:20+0100 by Peter Gürtler): cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug -> pass; sh scripts/check.sh tfp android -> pass (rudi/agent-2 at 1d401070); sh scripts/check.sh server web on dell26 (Docker/Podman, Node v24.21.0) at ddda78209 -> all checks passed (812s); done re-runs the full check

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 16:13+0100 by dell26/agent-3): Edited ui/CockpitShell.kt (outside touches, 10 lines): it provides LocalConvertedOpener so the Inbox can open a converted note (search item route, as global search does) or the Calendar tab; no other way for the Inbox to navigate.
- ASSUMPTION (2026-11-08 16:13+0100 by dell26/agent-3): 'Open' for an event opens the Calendar tab (no per-event screen/route on Android); the snackbar names the event's day. Notes open in the note editor.
- ASSUMPTION (2026-11-08 16:13+0100 by dell26/agent-3): To calendar dialog: timed events in the device zone (ZoneId of the system clock), default start = next full hour, duration chips 15/30/60/90/120/180 min (default 60), all-day = one day; calendars = writable ones, default first (web parity).
- ASSUMPTION (2026-11-08 16:13+0100 by dell26/agent-3): Task detail: after converting, a Long snackbar 'Moved to ... / Open'; Open closes the detail and opens the item, dismiss closes the detail (like delete). Convert actions only for open (not completed) tasks. Inbox: To calendar under 'Defer it', To reference under 'Drop it'; inline clarification is not saved before converting (like Trash).
- extended by dell26/agent-3: Impl (4ebb4f268) + tests (a2668a93b, f0492d419) + reviews/and-task-convert/review.md committed. Left: run fixer for CR-1 (use synced settings.timeZone ?: clock.zone like EventEditorViewModel), CR-2 (cancel previous loadCalendars job; 2 failing regression tests InboxConvertViewModelTest/TaskDetailConvertViewModelTest 'calendars of an earlier ... answering last' cover it), CR-3 (confirm To reference or assume), CR-4 optional; then test-writer -> tests.md with coverage, re-run code-reviewer, verify #1-#5, quality.json, done. check.sh takes ~5 min.
- release by dell26/agent-3: Impl (9ba2576b5), tests (aa1bb9148, ca1381434, 509d011a2 TaskDetailConvertRouteTest), review.md + fixer for CR-1..CR-4 (9e8160904, fixes.md) committed; sh scripts/check.sh passes at 509d011a2. Left: run test-writer on final code -> reviews/and-task-convert/tests.md with coverage (fold in/validate TaskDetailConvertRouteTest); re-run code-reviewer (review.md is stale after fixes); tasks.py verify 1-5; write quality.json (docs/QUALITY.md); done. check.sh takes ~5 min.
- release by felix/agent-1: over budget after 2 sessions (~50 min) and a review that decided nothing; still resumable: continue from wip/and-task-convert (see git log)
- ASSUMPTION (2026-11-08 20:03+0100 by rudi/agent-2): Criterion 5: full check.sh was not run on the rudi host (no Docker access, Node too old). Only the Android and tfp parts were run, and both passed. The Android-only change can't affect server or web.
- release by rudi/agent-2: Complete except done: all 5 criteria verified/skipped, reviews + quality.json committed (reviewed_revision 55879cb7, code unchanged since review). Android ktlint/test/lint and check.sh tfp+android pass. done fails on rudi host only because server Testcontainers need Docker (socket permission denied) and web needs Node >= 22.22.3. Next owner on a host with Docker + Node 22: merge wip, set reviewed_revision to HEAD if needed, run python tasks.py done.
