---
title: Birthdays calendar
status: in_progress
owner: dell26/agent-2
deps: [contacts-api,cal-local-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/contacts/birthdays,server/src/test/kotlin/ch/sonensei/cockpit/contacts/birthdays]
prio: 2
lease_until: 2026-10-22T02:51:27Z
---
## Goal
Virtual read-only birthdays calendar (SPEC.md §8.3).

## Acceptance criteria
- [ ] Birthdays appear as yearly all-day occurrences in `GET /calendar/events` from a virtual calendar; Feb 29 handled
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.birthdays.*'` passes (Postgres via Testcontainers where persistence is involved)

## Notes
- ASSUMPTION (2026-10-22 04:35+0200 by dell26/agent-2): Virtual calendars: the calendar module got a small SPI (calendar.api.VirtualCalendarProvider + CalendarService.ensureVirtualCalendar); the birthdays calendar is a stored read-only LOCAL calendar (externalId 'virtual:birthdays', so name/color/visibility/backup work as for any calendar) whose occurrences are computed from contacts, not stored. Touches calendar/api and OccurrenceExpander outside the task's touches.
- ASSUMPTION (2026-10-22 04:35+0200 by dell26/agent-2): The Birthdays calendar is created lazily once a contact has a birthday (after a contact change commits, and at startup), so users without birthdays get no empty calendar; it is never removed automatically.
