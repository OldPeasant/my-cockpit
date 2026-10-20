# Code Review

Date: 2026-10-20

Task: cal-model - Calendar data model

Scope: task commit 4255c5b on top of base a661293 (`git diff a661293..4255c5b`), production code under `server/src/main/kotlin/ch/sonensei/cockpit/calendar/model/` and `server/src/main/resources/db/migration/calendar/V202610201351__calendar_model.sql`. Tests in `server/src/test/kotlin/ch/sonensei/cockpit/calendar/model/` at 4255c5b were read for coverage context only, since a test-writer is changing them in parallel.

Requirements:
- tasks/cal-model.md acceptance criteria and recorded assumptions
- SPEC.md sections 4.2, 4.3, 4.4, 6.1, 6.2
- docs/ARCHITECTURE.md conventions (migrations, Postgres 16, PostgresTestBase)
- AGENTS.md project notes (migration naming, assumptions, logging of secrets)
- Existing core contracts: BaseEntity, SoftDeletableEntity/Repository, ItemResolver, SearchIndexer, TrashContributor, EncryptedSecret/EncryptedSecretConverter; gtd module resolvers as the convention reference

Git range:
- Branch: main
- HEAD: 4255c5b
- Base/start: a661293219643f84f49b2709692db50452122eea
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 1 P2, 3 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Range query returns live overrides of a trashed series, unlike the resolver and indexer**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/model/CalendarRepositories.kt:36`
    - Category: Correctness
    - Problem: `findCandidatesInRange` returns every override with `e.parentEventId is not null and e.deletedAt is null`. It does not check whether the series master is deleted. Soft-deleting a master does not soft-delete its overrides: the indexer treats them as sharing the master's Trash state, but leaves their `deleted_at` null. So the query returns overrides of a series that is in the Trash. `EventItemResolver` (line 26) and `CalendarEventIndexer.index` hide exactly these rows, which is the recorded assumption ("overrides of a deleted series are neither indexed nor resolved"). The candidate query, which the view and expansion code will use, does not hide them. The range test (`CalendarModelTests` "range candidates ...") has no case with a deleted master.
    - Why it matters: Unless every later consumer (cal-recurrence, cal-local-api) re-checks the master, a moved occurrence of a deleted series still appears in the calendar views. The rule "an override shares its master's Trash state" would then live in several places.
    - Suggested fix: Exclude overrides whose master is deleted inside the query, e.g. `e.parentEventId is not null and exists (select 1 from CalendarEvent m where m.id = e.parentEventId and m.deletedAt is null)`. Optionally limit it to masters that are themselves candidates. Then add a range-test case with a soft-deleted master and a live override.

## P3 Low

- [ ] **CR-2 | An override can be placed in a different calendar than its series**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/model/CalendarEvent.kt:166`
    - Category: Correctness
    - Problem: `overrideOf` checks that the master is a recurring non-override, but not that `calendarId == master.calendarId`. `calendarId` is a mutable `var`, and the schema has no constraint tying `calendar_event.calendar_id` of an override to its parent's. `findCandidatesInRange` filters by calendar, so an override in another calendar is returned without its master, or the master without its override.
    - Why it matters: Later sync and move code (moving a series to another calendar, provider imports) can silently split a series across calendars, and expansion then gives wrong occurrences.
    - Suggested fix: Add `require(calendarId == master.calendarId)` in `overrideOf`. Document, or enforce in the later service, that moving a master moves its overrides.

- [ ] **CR-3 | Unique (calendar_id, external_id) forces a non-obvious external-id encoding for CalDAV/ICS overrides**
    - File: `server/src/main/resources/db/migration/calendar/V202610201351__calendar_model.sql:98`
    - Category: Integration
    - Problem: In iCalendar (CalDAV, ICS feeds), a series master and its RECURRENCE-ID overrides share one UID. The partial unique index on `(calendar_id, external_id)` rejects overrides that store the same UID as their master. `findByCalendarIdAndExternalId` also assumes one row per external id. Google and Microsoft give instances their own ids, so they are not affected.
    - Why it matters: cal-caldav and cal-ics must either leave `external_id` null on overrides or invent a compound id. Neither the migration comment nor the KDoc says which, so the two adapters may choose differently.
    - Suggested fix: Document the intended convention in the migration comment and the `CalendarEvent` KDoc, e.g. "overrides of iCalendar sources keep `external_id` null and are found by parent + recurrence id". Alternatively, scope the index to `parent_event_id is null`.

- [ ] **CR-4 | "One default calendar overall" is an open-spec decision without a recorded assumption**
    - File: `server/src/main/resources/db/migration/calendar/V202610201351__calendar_model.sql:38`
    - Category: Quality
    - Problem: `calendar_single_default` allows at most one default calendar across all sources. SPEC.md 6.1 lists "default for new events" as a per-calendar flag without saying whether the scope is global or per source. The four recorded assumptions do not cover this choice. AGENTS.md asks for `tasks.py assume` on decisions the spec leaves open.
    - Why it matters: A human reviewing assumptions cannot see or revisit this choice, and a per-source interpretation later would need a migration.
    - Suggested fix: Record the assumption with `python tasks.py assume cal-model "..."` (parent worker). No code change needed if global is intended.

## Notes (verified, no finding)

- All criterion-1 columns are present with matching check constraints. Enum CHECK values match the Kotlin enums. `nulls not distinct` needs PostgreSQL 15+, and the project uses postgres:16-alpine. The migration path and name follow the `<module>/V<yyyyMMddHHmm>__<module>_<desc>.sql` convention.
- Credentials use `EncryptedSecret`, which has an auto-applied converter, a redacted `toString` and redacted JSON. No secrets or contents are logged.
- `setTimed` takes a `ZoneId`, so `time_zone` always holds a valid id and `ZoneId.of` in the resolver cannot fail on rows written through the entity.
- The resolver follows the gtd resolver convention (read-only transaction, `findActiveById`).
- Trash contribution and backup belong to cal-local-api and backup-contrib-calendar, so they are not reported here.
- The reviewer could not run the Testcontainers suite (command execution for Gradle was denied in this session). Criterion 3 needs evidence from the parent worker or test-writer run.

## Review conclusion

Material issues found; fix before completing the task. CR-1 should be fixed or explicitly dispositioned. CR-2 to CR-4 are low-priority cleanups and documentation.
