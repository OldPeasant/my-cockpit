---
title: Backup contributor: calendar
status: in_progress
owner: dell26/agent-3
deps: [backup-contrib-core,cal-local-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/backup,server/src/test/kotlin/ch/sonensei/cockpit/calendar/backup]
prio: 2
lease_until: 2026-10-21T18:12:18Z
extended: dell26/agent-3
---
## Goal
Calendar data in backups (SPEC.md §10.2).

## Acceptance criteria
- [x] data/calendar_sources.json (no secrets), calendars.json, events.jsonl (LOCAL fully, external as id refs); readable/calendar/<name>.ics importable (validated by parsing it back with ical4j)
  - verified (2026-10-21 19:55+0200 by dell26/agent-3): cd server && ./gradlew test --tests '*CalendarBackupContributorTests' -> 21/21 pass (data/calendar_sources.json/calendars.json/events.jsonl asserted; CalDAV password absent from all data/ files, only in secrets.enc.json; readable/calendar/*.ics parsed with ical4j CalendarBuilder strict + validate() no errors)
- [ ] Round-trip test (`./gradlew test --tests '*BackupRoundTripTest'`) passes with this module's `BackupFixture` seeding local recurring events with overrides and an external source

## Notes
- ASSUMPTION (2026-10-21 18:43+0200 by dell26/agent-3): External (non-LOCAL source) events are exported as id refs (id, calendarId, externalId, parent, recurrence id) and restored as placeholder rows titled '(restored, waiting for sync)' at 1970-01-01 (SYNCED, no etag), so the first full sync matches them by external id, fills them in and keeps their ids, hence links survive (SPEC 10.2); unsynced local changes (PENDING) of external events are lost
- ASSUMPTION (2026-10-21 18:43+0200 by dell26/agent-3): Calendar source credentials go only to the backup secrets (calendar_source_credentials, id -> plaintext); config JSON is exported as is (it holds non-secret settings by contract). Sync status and sync cursors are not backed up: a restored source does a full sync
- ASSUMPTION (2026-10-21 18:43+0200 by dell26/agent-3): readable/calendar/<name>.ics: one file per calendar of a LOCAL source (characters not allowed in file names replaced by _, ' (2)' on clashes), events not in the Trash, UID = event id, written with the existing CalDAV ICS writer
- extended by dell26/agent-3: Implementation + fixes for review CR-2 (inert placeholders: CANCELLED + placeholder etag), CR-3 (credential warnings) and empty-ICS committed (6c324754); scripts/check.sh passes. Left: check CR-1 is covered by CalendarBackupContributorTests (ICS parsed with ical4j, secrets only in backup secrets) and CR-4; re-run test-writer (reviews/.../tests.md missing) and code-reviewer on final code; run ./gradlew test --tests '*BackupRoundTripTest'; tasks.py verify 1,2; write quality.json (fixer not used: fixes made by owner, record it); done.
