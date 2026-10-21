# Code Review

Date: 2026-10-21

Task: backup-contrib-calendar - Backup contributor: calendar

Scope: task commit a4a6d508 on top of base 5266bb33 (`git diff 5266bb33..a4a6d508 -- server`), committed changes only

Requirements:
- tasks/backup-contrib-calendar.md (acceptance criteria 1 and 2, three recorded assumptions)
- SPEC.md §10.2 (zip format, secrets, external calendar data as id refs so links survive)
- AGENTS.md project notes (module boundaries, never log secrets, test data fictitious)
- Contract: server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/BackupContributor.kt
- Peers: notes/backup/NotesBackupContributor.kt, core/backupcontrib/OAuthBackupContributor.kt, LinksBackupContributor.kt
- Calendar schema (db/migration/calendar/*.sql), calendar/sync/CalendarSyncService.kt, CalendarWriteThrough.kt, recurrence/OccurrenceExpander.kt, CalendarEventRepository

Git range:
- Branch: main
- HEAD: a4a6d50842836da937c9f0947fbc817c54904c14
- Base/start: 5266bb33e4229fa639cab94dfa98913b9682b889
- Commits reviewed: 1 (a4a6d508; 2 files, +677 lines: CalendarBackupContributor.kt, CalendarBackupFixture.kt). No uncommitted changes.

Summary: 0 P0, 1 P1, 1 P2, 2 P3

Verified as correct (no finding):
- FK order on import: sources, then calendars, then events streamed masters/single events first (`order by e.parent_event_id is not null, e.id`), exdates inserted right after their event.
- LOCAL events are exported with every column of `calendar_event` (ids, created/updated/deleted_at, version, timing, rrule, recurrence ids, attendees, reminder_minutes, status, transparency, conference URL, external id/etag, sync state) plus their EXDATEs in a deterministic order; the import writes them back with JDBC, so ids, timestamps and versions survive.
- Placeholders for external refs satisfy the table constraints (timed: start = end, zone UTC; all-day override: end = recurrence date + 1; override: rrule null and exactly one recurrence id). The first full sync after a restore (no cursors restored) matches them: `upsertSeries`/`upsertOverride` find them by external id and recurrence id, `isCurrent` is false because the etag is null, `copyFrom` fills them and keeps their ids, and `removeMissing` deletes the ones the provider no longer has.
- Placeholders are not put into the search index (only LOCAL masters and single events are re-indexed, overrides through their master).
- Credentials are decrypted and exported only when `writer.includesSecrets`, only through `writer.secret`; nothing secret is logged. Google/Microsoft configs hold only `connectionId`, CalDAV only URL and user name.
- ICS mirror: one file per LOCAL calendar, masters written before their overrides (required by `CalDavEventMapping.write`), overrides of series in the Trash left out, RECURRENCE-ID/EXDATE formatted like DTSTART with a generated VTIMEZONE.

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | Criterion 1 (ICS parsed back with ical4j, no secrets in calendar_sources.json) and the credential path have no test in the reviewed scope**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/calendar/backup/CalendarBackupFixture.kt:1`
    - Category: Tests
    - Problem: The only test code in the task commit is the round-trip fixture. `BackupRoundTripTest` compares `data/` and `files/` only, never `readable/`, so nothing parses `readable/calendar/<name>.ics` back with ical4j, as criterion 1 names explicitly. The round trip calls `backups.export(it)` without a passphrase (`BackupService.export(passphrase = null)`), so `includesSecrets` is false: the export with credentials (`CalendarBackupContributor.kt:93`, `:98`), their import and re-encryption (`:122`, `:137`), and the "no secrets in data/calendar_sources.json" part of criterion 1 are never exercised. Restoring the seeded `fictitious-caldav-password` is not checked either.
    - Why it matters: Criterion 1 cannot be recorded as verified from the reviewed code. A regression that leaks the CalDAV password into `data/` or breaks ICS importability would go unnoticed.
    - Suggested fix: Add contributor tests (the parallel test-writer's `CalendarBackupContributorTests.kt` may cover this; it was not in the reviewed commit): export with a passphrase and assert the credential appears only in the secrets and not in any `data/` file, restore it and assert the source's decrypted credential matches; parse every `readable/calendar/*.ics` with ical4j `CalendarBuilder` and check UIDs, RRULE/EXDATE, RECURRENCE-ID overrides and that Trash events are absent. Record the evidence with `tasks.py verify`.

## P2 Medium

- [ ] **CR-2 | Restored placeholders of external events are visible and writable, and a write overwrites the provider's real event unconditionally**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/backup/CalendarBackupContributor.kt:227`
    - Category: Correctness
    - Problem: `insertPlaceholder` stores refs as normal rows: `sync_state = SYNCED` (`:252`), `external_etag` null, status CONFIRMED (`:249`), title `(restored, waiting for sync)`. Masters/single events sit at 1970, but override placeholders get their real recurrence time (`:229`). `CalendarEventRepository.findCandidatesInRange` returns all overrides of a calendar while the 1970, non-recurring placeholder master is not a candidate, so `OccurrenceExpander.expand` shows these overrides as orphans at their real times in the agenda/day/today views, CONFIRMED even when the backed-up override was a cancelled occurrence. The event service accepts edits to them: `CalendarWriteThrough.write` then calls `adapter.updateEvent(..., event.toRemote(uid), event.externalEtag)` with `ifMatch = null`, which `CalendarProviderAdapter` defines as "unconditionally". So editing a placeholder (from a link, or an override from the agenda) before the first successful sync replaces the provider's real event or instance with placeholder data: placeholder title, 1970 or recurrence-id timing, no description, and for a master no RRULE. The window is not just the moment after a restore. The provider may be unreachable at the first sync. A remote event that `store` skips as invalid keeps its placeholder, since `removeMissing` counts it as present, and it stays writable until the event changes remotely.
    - Why it matters: A user action can cause data loss at the provider, which is the source of truth (SPEC §6.2). Placeholder rows also show up in views as ghost occurrences until a sync succeeds.
    - Suggested fix: Make placeholders inert until the sync fills them. Options: refuse writes to events of synced calendars that have an external id but no etag (refusing writes with a null If-Match), or store placeholders with a distinct marker (e.g. `sync_state = ERROR`/`PENDING`) that the event service refuses to edit and the expander skips. Pick whatever stays inside the calendar module's conventions. If the guard has to live outside this task's `touches` (calendar api/sync), add a follow-up task and record the limitation with `tasks.py assume`.

## P3 Low

- [ ] **CR-3 | Restore reporting of missing credentials is inaccurate**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/backup/CalendarBackupContributor.kt:126`
    - Category: Quality
    - Problem: `reconnect` counts every non-LOCAL source that has no restored credential. GOOGLE/MICROSOFT sources use an OAuth connection (`config.connectionId`, `credentials` null) and ICS_FEED sources have none, so the WARN at `:167` reports them as "must be reconnected" even when the backup was restored with its passphrase. Meanwhile `restoreWarnings` (`:170-174`) never tells the owner that CalDAV sources lose their password when the backup has no secrets or is restored without the passphrase. `OAuthBackupContributor.restoreWarnings` does this with `reader.hasSecrets`. The message also reads "the 1 external calendar sources".
    - Why it matters: The restore validation report is where SPEC §10.1/§10.2 expects "reconnect required" to show up. The log is misleading for operators.
    - Suggested fix: Count only sources that had a credential in the backup (e.g. CALDAV, or ids missing from the secret map while `reader.hasSecrets`/type needs one). Add a restore warning for CalDAV sources restored without their password, and fix the pluralisation.

- [ ] **CR-4 | A LOCAL calendar without active events produces a VCALENDAR with no component**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/backup/CalendarBackupContributor.kt:116`
    - Category: Correctness
    - Problem: `readable` writes a file for every LOCAL calendar, and `ics(byCalendar[calendar.id].orEmpty())` (`:366-375`) yields `BEGIN:VCALENDAR / VERSION / PRODID / END:VCALENDAR` for an empty calendar, or one whose events are all in the Trash. RFC 5545 §3.6 requires at least one calendar component, and ical4j's validator and some importers reject such a file.
    - Why it matters: Criterion 1 requires the mirror to be importable. An empty calendar is a normal state, such as a freshly created second calendar.
    - Suggested fix: Skip calendars without active events (and document this in the assumption), or cover the case in the ical4j parse-back test so the chosen behaviour is deliberate.

## Review conclusion

Material issues found; fix before completing the task.
