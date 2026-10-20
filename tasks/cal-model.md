---
title: Calendar data model
status: todo
owner:
deps: [p0-items-links,p0-search-core,p0-trash-core]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/model,server/src/test/kotlin/ch/sonensei/cockpit/calendar/model,server/src/main/resources/db/migration/calendar]
prio: 1
---
## Goal
Sources, calendars, events (SPEC.md §6.1).

## Acceptance criteria
- [ ] Migrations: calendar_source (LOCAL|GOOGLE|MICROSOFT|CALDAV|ICS_FEED, config, credentials ref), calendar (color, visible, readOnly, default), calendar_event (start/end with tz id or all-day dates, RRULE, EXDATEs, override parent + recurrence id, attendees JSON, reminders, status, transparency, conference URL, external_id, external_etag, sync_state)
- [ ] Resolver EVENT, search indexing hooks
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.model.*'` passes (Postgres via Testcontainers where persistence is involved)

## Notes
