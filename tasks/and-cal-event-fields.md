---
title: Android events: reminders, attendees, invitations, time zone, links
status: in_progress
owner: rudi/agent-1
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/calendar/edit,android/app/src/test/java/ch/sonensei/cockpit/calendar/edit,android/app/src/main/res/values/strings_calendar_edit.xml,android/app/src/main/res/values-de/strings_calendar_edit.xml]
prio: 2
lease_until: 2026-11-08T13:11:42Z
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
- ASSUMPTION (2026-11-08 13:55+0100 by rudi/agent-1): Linked items panel is read-only (and-links-panel not done): items listed with type/subtitle, no tap navigation (the editor has no navigation callbacks); Create task/note use the web's title '<title> (<yyyy-MM-dd>)', task goes to INBOX, then linked item->event.
- ASSUMPTION (2026-11-08 13:55+0100 by rudi/agent-1): Invitations: after a save of an event in a local calendar with guests (or removed guests) the editor offers a prompt (REQUEST or CANCEL to guests over a chosen mail account, plus optional CANCEL to removed guests via recipients); dismissing closes the editor. A 'Send invitations' button (like web) also sends for the saved event when there are no unsaved changes.
