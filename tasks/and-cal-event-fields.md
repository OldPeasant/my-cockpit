---
title: Android events: reminders, attendees, invitations, time zone, links
status: in_progress
owner: dell26/agent-2
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/calendar/edit,android/app/src/test/java/ch/sonensei/cockpit/calendar/edit,android/app/src/main/res/values/strings_calendar_edit.xml,android/app/src/main/res/values-de/strings_calendar_edit.xml]
prio: 2
lease_until: 2026-11-08T14:44:55Z
extended: 
releases: 1
---
## Goal
The Android event editor lacks reminders, attendees, sending invitations, time zone editing and event links
(web: `event-dialog.html:100-302`, `overlay/event-links.ts`).

## Acceptance criteria
- [x] Reminders: add/remove (presets + custom minutes before)
  - verified (2026-11-08 15:28+0100 by dell26/agent-2): EventFormExtrasTest/EventEditorExtrasScreenTest/EventEditorViewModelExtrasTest reminder tests (presets, custom 0..40320 min, add/remove, save): cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug -> BUILD SUCCESSFUL, 3614 tests 0 failures (aa03d8eb)
- [x] Attendees: add (contact autocomplete) / remove, show their participation status; on save offer to send invitations / cancellations like web (iTIP REQUEST/CANCEL)
  - verified (2026-11-08 15:28+0100 by dell26/agent-2): EventEditorViewModelExtrasTest guest/suggestion/invitation tests (contact autocomplete, remove, answer status, REQUEST/CANCEL prompt), EventExtrasRepositoryTest invitation POST, EventEditorExtrasScreenTest guest+prompt UI: cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug -> BUILD SUCCESSFUL, 3614 tests 0 failures (aa03d8eb)
- [x] Time zone of the event editable
  - verified (2026-11-08 15:28+0100 by dell26/agent-2): EventEditorViewModelExtrasTest time zone change/new-event zone, EventEditorExtrasScreenTest time zone picker search/read-only: cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug -> BUILD SUCCESSFUL, 3614 tests 0 failures (aa03d8eb)
- [x] Linked items panel (uses `LinkedItemsPanel` if and-links-panel is done, otherwise read-only list) and "Create task / note from event"
  - verified (2026-11-08 15:28+0100 by dell26/agent-2): and-links-panel not done -> read-only list; EventEditorViewModelExtrasTest links load/race + create task/note then link, EventExtrasRepositoryTest links/task/note, EventEditorExtrasScreenTest links panel: cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug -> BUILD SUCCESSFUL, 3614 tests 0 failures (aa03d8eb)
- [x] New strings are in English and German (`res/values/` + `res/values-de/`)
  - verified (2026-11-08 15:28+0100 by dell26/agent-2): CalendarEditStringsTest (EN/DE keys equal, 4 tests) + lintDebug MissingTranslation clean: cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug -> BUILD SUCCESSFUL (aa03d8eb)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 13:55+0100 by rudi/agent-1): Linked items panel is read-only (and-links-panel not done): items listed with type/subtitle, no tap navigation (the editor has no navigation callbacks); Create task/note use the web's title '<title> (<yyyy-MM-dd>)', task goes to INBOX, then linked item->event.
- ASSUMPTION (2026-11-08 13:55+0100 by rudi/agent-1): Invitations: after a save of an event in a local calendar with guests (or removed guests) the editor offers a prompt (REQUEST or CANCEL to guests over a chosen mail account, plus optional CANCEL to removed guests via recipients); dismissing closes the editor. A 'Send invitations' button (like web) also sends for the saved event when there are no unsaved changes.
- ASSUMPTION (2026-11-08 13:55+0100 by rudi/agent-1): Guests are editable only for calendars of the LOCAL source (fetched via GET /calendar/calendars, since CalendarInfo has no sourceType and calendar/agenda is outside touches); while unknown or on failure guests are shown read-only. Custom reminders allowed 0..40320 min (4 weeks); time zone picker offers region ids + UTC.
- ASSUMPTION (2026-11-08 13:55+0100 by rudi/agent-1): Kept the existing Material 3 editor look (new sections follow the existing field/label style) instead of a new visual direction via frontend-design: parity task, design must stay consistent.
- extended by rudi/agent-1: Implementation committed (model + UI, EN/DE strings). Left: fix links-reload race in EventEditorViewModel (older/failed links answer overrides newer one; 2 failing tests in EventEditorViewModelExtrasTest), then test-writer report (reviews/and-cal-event-fields/tests.md), code-reviewer, fixer if needed, quality.json, tasks.py verify for 6 criteria, .taskcheck, done.
- release by rudi/agent-1: Implementation done and committed (model+UI, EN/DE strings, links-reload race fixed in 06842e69). Committed: test-writer's extra late/out-of-order tests (b61bec6a, not yet run by me) and reviews/and-cal-event-fields/review.md (0 P0-P2, 4 P3; reviewed f4822f89, pre-race-fix, so likely stale per patch-id). Left: run 'cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug'; re-run test-writer (tests.md missing) and code-reviewer on final code; fixer or 'not required' for the 4 P3; quality.json; tasks.py verify 1-6; done. Note: sh scripts/check.sh failed on an unrelated, apparently flaky server test (PageFetcherSizeLimitTests: 6/50 TIMEOUT) and took 9 min on this machine.
