# Code Review

Date: 2026-10-21

Task: backup-contrib-calendar - Backup contributor: calendar

Scope: final revision of the task, `git diff 1ec99d7d..8c22a1d3 -- server` (task commits eb8ad547, ae8178e3, 6bd32b4c, 8c22a1d3), committed changes only. This is a re-review: it checks the earlier findings CR-1..CR-4 (first review, on an earlier revision) and looks for new issues.

Requirements:
- tasks/backup-contrib-calendar.md (acceptance criteria 1 and 2, three recorded assumptions, extension note)
- SPEC.md §10.2 (backup zip format, secrets, external calendar data as id refs so links survive)
- AGENTS.md project notes (module boundaries, never log secrets, test data fictitious)
- Contract: server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/BackupContributor.kt; peer: core/backupcontrib/OAuthBackupContributor.kt
- Calendar code the contributor relies on: calendar/sync/CalendarSyncService.kt (upsertSeries/upsertOverride/isCurrent/removeMissing), calendar/sync/CalendarWriteThrough.kt, calendar/recurrence/OccurrenceExpander.kt, calendar/model/EventItemResolver.kt, CalendarEventIndexer.kt, the Google/Microsoft/CalDAV adapters (If-Match handling)
- Previous review and test report: reviews/backup-contrib-calendar/review.md (earlier revision), reviews/backup-contrib-calendar/tests.md

Git range:
- Branch: main
- HEAD: 8c22a1d301d91e3f1a1c27d8b7888cabc4789077
- Base/start: 1ec99d7dc5d5915e84b1d8797c542c32ca7a80a2
- Commits reviewed: 4 (eb8ad547, ae8178e3, 6bd32b4c, 8c22a1d3). Server files: CalendarBackupContributor.kt (new, 545 lines), CalendarBackupContributorTests.kt (new, 21 tests), CalendarBackupFixture.kt (new). No uncommitted changes.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Status of the earlier findings:
- CR-1 (no test for criterion 1: ICS parse-back, secrets path): resolved. `CalendarBackupContributorTests` parses every `readable/calendar/*.ics` with ical4j in strict mode, asserts that relaxed parsing is off and that `validate()` reports no errors, and checks UID, RRULE, EXDATE, RECURRENCE-ID, VTIMEZONE, escaping, and that Trash is absent. With a passphrase, it asserts that the CalDAV password, plain or as stored, appears in no zip entry and only in `data/secrets.enc.json`. The credential is restored and decrypts to the original value with the passphrase, and stays null without it.
- CR-2 (placeholders visible and writable, unconditional overwrite at the provider): resolved. Placeholders are now `CANCELLED` with `PLACEHOLDER_ETAG` (`CalendarBackupContributor.kt:267`, `:270`). `OccurrenceExpander` drops cancelled orphan overrides (`:100`) and cancelled overrides of series (`:130`). A placeholder master sits at 1970 and has no RRULE. `EventItemResolver` hides cancelled overrides. `CalendarWriteThrough` now passes the placeholder etag as If-Match, and every adapter forwards it: Google `patch`/`delete`, Microsoft `patch`/`delete`/`writeInstance`, and CalDAV `write`/`put`/DELETE (`ifMatch ?: found.etag`). Placeholder data always differs from the provider's version, so Microsoft's "no changes, return current" shortcut does not apply. No provider version matches the placeholder etag, so a write ends as `RemoteEventChangedException` -> 409 plus a refetch, not an overwrite. The first full sync still fills the placeholders, because `isCurrent` fails on the mismatched etag and `copyFrom` sets status and content. Masters are merged before overrides, so `upsertOverride` finds the master recurring again. The test `until a sync fills them in, placeholders are not shown and edits or deletes never overwrite the provider's events` covers this, with the fetch failing. The provider-side behaviour is verified only against `FakeCalendarAdapter`, which honours If-Match. This limit is stated in tests.md.
- CR-3 (inaccurate credential warnings and log): resolved. Only `CALDAV` sources count (`WITH_CREDENTIALS`, `:445`). `restoreWarnings` follows the OAuth contributor's `reader.hasSecrets` pattern (`:176-192`), plurals are correct (`count`, `:453`), and the log contains no secret. Tests cover a mix of OAuth, feed and CalDAV sources, and backups with and without secrets.
- CR-4 (empty VCALENDAR for a calendar without active events): resolved. `ics()` returns null when no master is active (`:393`), and `readable` writes no file for such a calendar (`:119`). A file name is taken only after it is known that the calendar gets a file, so an empty calendar never takes another calendar's name. The class KDoc documents this behaviour, and a test validates every mirrored file with ical4j, including calendars that are empty or hold only Trash.

Also verified on the final code (no finding):
- Criterion 1: `data/calendar_sources.json` has no `credentials` field, and `calendars.json` contains every calendar. `events.jsonl` contains LOCAL events with every column (Trash, EXDATEs, overrides after masters) and external events as id refs only. Without a passphrase no secrets entry is written.
- Criterion 2: `CalendarBackupFixture` seeds local timed and all-day recurring series with changed, cancelled and excluded occurrences, plus an external CalDAV source with a credential and synced events. `BackupRoundTripTest` finds the fixture by classpath scanning.
- Import order and FK safety: sources, then calendars, then events with masters first, then EXDATEs after their event. Only LOCAL masters and single events are indexed, so placeholders, Trash and cancelled overrides stay out of search (tested).
- The file hashes listed in tests.md (contributor 60aa942e..., tests fcd2105a..., fixture 8536a3c3...) match the committed files at HEAD 8c22a1d3. The reported focused run, coverage (335/336 lines, 102/102 branches) and `.taskcheck` therefore apply to the final code.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.
