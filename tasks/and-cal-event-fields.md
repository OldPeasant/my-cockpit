---
title: Android events: reminders, attendees, invitations, time zone, links
status: in_progress
owner: rudi/agent-1
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/calendar/edit,android/app/src/test/java/ch/sonensei/cockpit/calendar/edit,android/app/src/main/res/values/strings_calendar_edit.xml,android/app/src/main/res/values-de/strings_calendar_edit.xml]
prio: 2
lease_until: 2026-11-08T13:06:41Z
---
## Goal
The Android event editor lacks reminders, attendees, sending invitations, time zone editing and event links
(web: `event-dialog.html:100-302`, `overlay/event-links.ts`).

## Acceptance criteria
- [ ] Reminders: add/remove (presets + custom minutes before)
- [ ] Attendees: add (contact autocomplete) / remove, show their participation status; on save offer to send invitations / cancellations like web (iTIP REQUEST/CANCEL)
- [ ] Time zone of the event editable
- [ ] Linked items panel (uses `LinkedItemsPanel` if and-links-panel is done, otherwise read-only list) and "Create task / note from event"
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
