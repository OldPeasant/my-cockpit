---
title: Backup contributor: calendar
status: todo
owner:
deps: [backup-contrib-core,cal-local-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/backup,server/src/test/kotlin/ch/sonensei/cockpit/calendar/backup]
prio: 2
---
## Goal
Calendar data in backups (SPEC.md §10.2).

## Acceptance criteria
- [ ] data/calendar_sources.json (no secrets), calendars.json, events.jsonl (LOCAL fully, external as id refs); readable/calendar/<name>.ics importable (validated by parsing it back with ical4j)
- [ ] Round-trip test (`./gradlew test --tests '*BackupRoundTripTest'`) passes with this module's `BackupFixture` seeding local recurring events with overrides and an external source

## Notes
