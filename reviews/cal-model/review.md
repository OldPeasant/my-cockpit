# Code Review

Date: 2026-10-20

Task: cal-model - Calendar data model

Scope: second review, after the fixes. It covers the whole task change on `main` from the merge base `6696b237831304c577bb02bd001dd824f9d4e20f` (`git merge-base HEAD origin/main`) to HEAD `b56fdef895c20fd76b133e5367c14430b73bae00`: production code in `server/src/main/kotlin/ch/sonensei/cockpit/calendar/model/`, the migration `server/src/main/resources/db/migration/calendar/V202610201351__calendar_model.sql`, the tests in `server/src/test/kotlin/ch/sonensei/cockpit/calendar/model/`, and `reviews/cal-model/`. The parent named HEAD `93bada8` and fix commit `676a3df`. Those are the same commits before a rebase onto a heartbeat commit. `git diff 93bada8 HEAD` changes only `tasks/cal-model.md` (lease field), so the code reviewed here is identical to `93bada8`. The fix commit is now `3ed4ecf`. Only committed changes were reviewed, and the working tree was clean.

Requirements:
- tasks/cal-model.md acceptance criteria and the five recorded assumptions
- SPEC.md sections 4.3, 4.4, 6.1, 6.2
- docs/ARCHITECTURE.md and AGENTS.md project notes (migration path and naming, conventions, logging)
- Previous reports: reviews/cal-model/review.md (CR-1..CR-4), reviews/cal-model/tests.md (TW-1), reviews/cal-model/fixes.md
- Core contracts: ItemResolver/ResolvedItem, SearchIndexer, SoftDeletableEntity/Repository, EncryptedSecret

Git range:
- Branch: main
- HEAD: b56fdef895c20fd76b133e5367c14430b73bae00 (code-identical to 93bada8)
- Base/start: 6696b237831304c577bb02bd001dd824f9d4e20f
- Commits reviewed: 5 (8f510ee, dde805c, 3ed4ecf, 8af5c69, b56fdef)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Verification of previous findings

- TW-1 (cancelled override resolved): fixed. `EventItemResolver.kt:29` now resolves an override only when it is not `CANCELLED` and its master is active. This matches `CalendarEventIndexer.apply` and the recorded assumption. A cancelled single event still resolves. Covered by `a cancelled override does not resolve` and `a cancelled single event still resolves and is indexed`.
- CR-1 (range query returned live overrides of a trashed series): fixed. `CalendarRepositories.kt:37-44` returns an override only when an `exists` subquery finds its master with `deletedAt is null`. The date conditions now apply only to rows with `parentEventId is null`. The KDoc matches. Covered on the exclusion side, including `restore()` (`CalendarModelEdgeCaseTests` line 369), and on the inclusion side for cancelled and out-of-range overrides of a live series (line 450).
- CR-2 (override in a different calendar): fixed. `CalendarEvent.kt:172` has `require(calendarId == master.calendarId)`, and the KDoc states it. Covered by `an override must be in the calendar of its series`.
- CR-3 (unique external id vs. iCalendar overrides): fixed. The partial index is now limited to `parent_event_id is null` (migration lines 98-101). The convention is documented in the migration and the `CalendarEvent` KDoc. The lookup was renamed to `findByCalendarIdAndExternalIdAndParentEventIdIsNull`, and no stale callers remain. The migration was edited, but it is not on `origin/main` (`git ls-tree origin/main` of the calendar migration folder is empty), so no applied migration changed.
- CR-4 (global single default, open in the spec): resolved by a recorded assumption. tasks/cal-model.md has the 16:20 entry "At most one default calendar overall (partial unique index calendar_single_default) ...". No code change is needed.

## Notes (verified, no finding)

- Criterion 1: every listed column is present. Source type and enum CHECK values match the Kotlin enums. Config is a JSON object, and credentials are an `EncryptedSecret` (recorded assumption). The calendar table has color, visible, read_only and is_default. The event table covers timed with tz id or all-day dates (enforced by check constraints), rrule, exdates (`calendar_event_exdate`), override parent and recurrence id, attendees JSON array, reminders, status, transparency, conference URL, external_id, external_etag and sync_state. The migration path and name follow the project naming convention (module folder, timestamp version, `calendar_` prefix). `nulls not distinct` needs PostgreSQL 15+, and the project uses PostgreSQL 16.
- Criterion 2: `EventItemResolver` (ItemType.EVENT, deep link `/calendar/events/{id}`) and `CalendarEventIndexer` (`index` and `unindexCalendar`) follow the recorded assumptions. The master and its overrides share one Trash state consistently across the resolver, the indexer and the range query.
- Criterion 3: the reviewer reran `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.calendar.model.*'` (Testcontainers PostgreSQL) on this revision. Result: BUILD SUCCESSFUL. Fresh result XMLs (2026-10-20T14:40-14:41Z) show 46 tests: CalendarModelTests 11, CalendarModelEdgeCaseTests 17, CalendarItemsAndSearchTests 7, CalendarItemsAndSearchEdgeCaseTests 11. All passed, with 0 failures, 0 errors and 0 skipped. ktlint is clean.
- An element-collection delete with a null column (exdates set of `OccurrenceId`) works against Postgres. This is shown by `exdates can be added and removed on a saved series`, which reloads in a new transaction.
- No secrets, event bodies or note contents are logged. The credentials have a redacted `toString` via `EncryptedSecret`.
- Trash contribution, backup and moving a series together with its overrides belong to later calendar tasks, so they are not reported here.
- `python tasks.py verify cal-model` lists all three criteria as not yet verified. Recording that evidence is the parent worker's next step (AGENTS.md quality step 7), so it is not a finding at this point.

## Review conclusion

No material issues found.
