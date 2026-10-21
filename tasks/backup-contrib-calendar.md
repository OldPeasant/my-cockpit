---
title: Backup contributor: calendar
status: in_progress
owner: dell26/agent-3
deps: [backup-contrib-core,cal-local-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/backup,server/src/test/kotlin/ch/sonensei/cockpit/calendar/backup]
prio: 2
lease_until: 2026-10-21T17:01:42Z
---
## Goal
Calendar data in backups (SPEC.md §10.2).

## Acceptance criteria
- [ ] data/calendar_sources.json (no secrets), calendars.json, events.jsonl (LOCAL fully, external as id refs); readable/calendar/<name>.ics importable (validated by parsing it back with ical4j)
- [ ] Round-trip test (`./gradlew test --tests '*BackupRoundTripTest'`) passes with this module's `BackupFixture` seeding local recurring events with overrides and an external source

## Notes
- ASSUMPTION (2026-10-21 18:43+0200 by dell26/agent-3): External (non-LOCAL source) events are exported as id refs (id, calendarId, externalId, parent, recurrence id) and restored as placeholder rows titled '(restored, waiting for sync)' at 1970-01-01 (SYNCED, no etag), so the first full sync matches them by external id, fills them in and keeps their ids, hence links survive (SPEC 10.2); unsynced local changes (PENDING) of external events are lost
