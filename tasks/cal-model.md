---
title: Calendar data model
status: in_progress
owner: dell26/agent-3
deps: [p0-items-links,p0-search-core,p0-trash-core]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/model,server/src/test/kotlin/ch/sonensei/cockpit/calendar/model,server/src/main/resources/db/migration/calendar]
prio: 1
lease_until: 2026-10-20T15:17:31Z
releases: 1
extended: 
---
## Goal
Sources, calendars, events (SPEC.md §6.1).

## Acceptance criteria
- [x] Migrations: calendar_source (LOCAL|GOOGLE|MICROSOFT|CALDAV|ICS_FEED, config, credentials ref), calendar (color, visible, readOnly, default), calendar_event (start/end with tz id or all-day dates, RRULE, EXDATEs, override parent + recurrence id, attendees JSON, reminders, status, transparency, conference URL, external_id, external_etag, sync_state)
  - verified (2026-10-20 16:43+0200 by dell26/agent-7): V202610201351__calendar_model.sql applied by Flyway in Testcontainers Postgres 16 during ./gradlew test --rerun --tests 'ch.sonensei.cockpit.calendar.model.*' -> schema round-trip + constraint tests (CalendarModelTests, CalendarModelEdgeCaseTests) pass: sources (LOCAL|GOOGLE|MICROSOFT|CALDAV|ICS_FEED, config, encrypted credentials), calendars (color/visible/readOnly/default), events (tz/all-day, RRULE, EXDATEs, override parent+recurrence id, attendees JSON, reminders, status, transparency, conference URL, external_id/etag, sync_state)
- [x] Resolver EVENT, search indexing hooks
  - verified (2026-10-20 16:43+0200 by dell26/agent-7): ./gradlew test --rerun --tests 'ch.sonensei.cockpit.calendar.model.*' -> CalendarItemsAndSearchTests + EdgeCaseTests pass: EVENT resolver (subtitle, deep link, hides cancelled overrides/overrides of trashed series) and CalendarEventIndexer search hooks
- [x] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.model.*'` passes (Postgres via Testcontainers where persistence is involved)
  - verified (2026-10-20 16:43+0200 by dell26/agent-7): cd server && ./gradlew ktlintCheck test --rerun --tests 'ch.sonensei.cockpit.calendar.model.*' (Testcontainers postgres) -> BUILD SUCCESSFUL, 46 tests, 0 failures/errors

## Notes
- ASSUMPTION (2026-10-20 16:01+0200 by dell26/agent-7): Credentials 'ref' is stored as an EncryptedSecret column calendar_source.credentials (encrypted with the master key) instead of a separate credentials table
- ASSUMPTION (2026-10-20 16:01+0200 by dell26/agent-7): Recurrence ids and EXDATEs are stored as the occurrence's original start: timestamptz for timed series, date for all-day series (calendar_event_exdate table, recurrence_id_at/recurrence_id_date); reminders as integer[] minutes before; all-day end_date is exclusive (iCalendar DTEND)
- ASSUMPTION (2026-10-20 16:01+0200 by dell26/agent-7): sync_state values LOCAL|SYNCED|PENDING|ERROR; source sync cursor/last-sync/error columns are left to cal-sync-engine (its own migration)
- ASSUMPTION (2026-10-20 16:01+0200 by dell26/agent-7): Search hooks: CalendarEventIndexer.index(event) called by calendar services (title; body = description + location); cancelled overrides and overrides of a deleted series are neither indexed nor resolved; EVENT deep link /calendar/events/{id}
- ASSUMPTION (2026-10-20 16:20+0200 by dell26/agent-7): At most one default calendar overall (partial unique index calendar_single_default), not one per source: SPEC.md 6.1 'default for new events' implies a single target for new events
- release by dell26/agent-7: Implementation, tests, test-writer/code-reviewer/fixer reports, final re-review and quality.json are all committed; all 3 criteria verified; sh scripts/check.sh passes on HEAD 81d0b35. Only left: python tasks.py done cal-model. Caveat: quality.json reviewed_revision is 41e96b0 (pre-rebase hash, on origin/wip/cal-model); its rebased equivalent is 2c2db86 with identical cal-model code. If done rejects it as stale, set reviewed_revision to the rebased hash of the 'final review report' commit, commit, and rerun done.
