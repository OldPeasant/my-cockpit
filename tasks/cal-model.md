---
title: Calendar data model
status: in_progress
owner: dell26/agent-7
deps: [p0-items-links,p0-search-core,p0-trash-core]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/model,server/src/test/kotlin/ch/sonensei/cockpit/calendar/model,server/src/main/resources/db/migration/calendar]
prio: 1
lease_until: 2026-10-20T15:00:42Z
---
## Goal
Sources, calendars, events (SPEC.md §6.1).

## Acceptance criteria
- [ ] Migrations: calendar_source (LOCAL|GOOGLE|MICROSOFT|CALDAV|ICS_FEED, config, credentials ref), calendar (color, visible, readOnly, default), calendar_event (start/end with tz id or all-day dates, RRULE, EXDATEs, override parent + recurrence id, attendees JSON, reminders, status, transparency, conference URL, external_id, external_etag, sync_state)
- [ ] Resolver EVENT, search indexing hooks
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.model.*'` passes (Postgres via Testcontainers where persistence is involved)

## Notes
- ASSUMPTION (2026-10-20 16:01+0200 by dell26/agent-7): Credentials 'ref' is stored as an EncryptedSecret column calendar_source.credentials (encrypted with the master key) instead of a separate credentials table
- ASSUMPTION (2026-10-20 16:01+0200 by dell26/agent-7): Recurrence ids and EXDATEs are stored as the occurrence's original start: timestamptz for timed series, date for all-day series (calendar_event_exdate table, recurrence_id_at/recurrence_id_date); reminders as integer[] minutes before; all-day end_date is exclusive (iCalendar DTEND)
- ASSUMPTION (2026-10-20 16:01+0200 by dell26/agent-7): sync_state values LOCAL|SYNCED|PENDING|ERROR; source sync cursor/last-sync/error columns are left to cal-sync-engine (its own migration)
- ASSUMPTION (2026-10-20 16:01+0200 by dell26/agent-7): Search hooks: CalendarEventIndexer.index(event) called by calendar services (title; body = description + location); cancelled overrides and overrides of a deleted series are neither indexed nor resolved; EVENT deep link /calendar/events/{id}
- ASSUMPTION (2026-10-20 16:20+0200 by dell26/agent-7): At most one default calendar overall (partial unique index calendar_single_default), not one per source: SPEC.md 6.1 'default for new events' implies a single target for new events
