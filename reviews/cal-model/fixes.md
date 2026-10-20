# Fix Report

Date: 2026-10-20

Task: cal-model - Calendar data model

Scope: current task. Findings TW-1 (`reviews/cal-model/tests.md`) and CR-1 to CR-4 (`reviews/cal-model/review.md`). The code fixes were made in an earlier fixer session and committed by the parent worker as `676a3df` ("cal-model: WIP fixes for TW-1, CR-1..CR-3"). This session re-checked each one against its finding, found nothing missing and changed no code. It then ran the validation below.

## Fixed findings

### TW-1 - Cancelled override of an active series still resolves as an item

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/model/EventItemResolver.kt`

Problem:
`resolve(id)` only checked that the event, and the master of an override, were active. So a `CANCELLED` override resolved to a live item. This went against the recorded assumption that cancelled overrides are neither indexed nor resolved, and against `CalendarEventIndexer`.

Fix:
An override now resolves only when its status is not `CANCELLED` and its master is active. A cancelled single event (not an override) still resolves, as before. The KDoc names both rules. The test-writer's test `CalendarItemsAndSearchEdgeCaseTests.a cancelled override does not resolve` is unchanged and now passes. `a cancelled single event still resolves and is indexed` still passes.

Validation:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.calendar.model.*'` - Pass

### CR-1 - Range query returns live overrides of a trashed series

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/model/CalendarRepositories.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/model/CalendarModelEdgeCaseTests.kt`

Problem:
`findCandidatesInRange` returned every non-deleted override, including overrides whose series master is in the Trash. The resolver and indexer hide those overrides.

Fix:
The query now returns an override only if `exists (select 1 from CalendarEvent m where m.id = e.parentEventId and m.deletedAt is null)`. The single-event and master conditions are limited to `parentEventId is null`, so their meaning does not change. Cancelled overrides of a live master are still returned on purpose, because expansion needs them to remove occurrences. The KDoc was updated. The range edge-case test now covers two cases: a live override of a soft-deleted master is excluded, and after `restore()` the master and its override come back.

Validation:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.calendar.model.*'` - Pass

### CR-2 - An override can be placed in a different calendar than its series

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/model/CalendarEvent.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/model/CalendarModelEdgeCaseTests.kt`

Problem:
`overrideOf` did not require the override to be in its master's calendar.

Fix:
Added `require(calendarId == master.calendarId)` and documented it in the KDoc. The new test `an override must be in the calendar of its series` checks that this throws `IllegalArgumentException` and leaves the event unchanged (`isOverride` is false). Moving a series together with its overrides is left to the later service tasks, as the reviewer suggested.

Validation:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.calendar.model.*'` - Pass

### CR-3 - Unique (calendar_id, external_id) blocks iCalendar overrides that share their master's UID

Files changed:
- `server/src/main/resources/db/migration/calendar/V202610201351__calendar_model.sql`
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/model/CalendarEvent.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/model/CalendarRepositories.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/model/CalendarModelEdgeCaseTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/model/CalendarModelTests.kt`

Problem:
The partial unique index on `(calendar_id, external_id)` rejected iCalendar overrides that keep their master's UID. The lookup by external id assumed one row per id.

Fix:
The reviewer's alternative was applied: the index is now `where external_id is not null and parent_event_id is null`. A migration comment and the `CalendarEvent` KDoc say that overrides are identified by parent and recurrence id. The repository method was renamed to `findByCalendarIdAndExternalIdAndParentEventIdIsNull` and documented, and its callers (tests only) were updated. No other references are left in `server/src`.

This migration was created in this task and is not on `origin/main` (`git ls-tree origin/main server/src/main/resources/db/migration/calendar/` is empty), so editing it does not break the rule against editing applied migrations.

The new test `iCalendar overrides may share their master's external id, and the lookup by external id returns the master` covers this. Two overrides share the master's UID, the lookup returns the master, and a second master with the same UID is still rejected.

Validation:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.calendar.model.*'` - Pass

## Not fixed

### CR-4 - "One default calendar overall" open-spec decision without a recorded assumption

Status:
Invalid (no code change needed)

Reason:
The parent worker resolved this by recording a `tasks.py assume` entry for the global single-default rule. The reviewer said no code change is needed if the global scope is intended. This fixer changed nothing for it.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.calendar.model.*'` (Testcontainers, PostgreSQL 16) - Pass: BUILD SUCCESSFUL. 45 tests, 0 failed, 0 skipped (counted from `build/test-results/test/TEST-ch.sonensei.cockpit.calendar.model.*.xml`).

Full test suite:
- Ran as part of `sh scripts/check.sh`: server 1106 tests, 0 failed, 0 skipped (all `build/test-results/test/TEST-*.xml`). Web 22 test files, 321 tests passed. Android ktlint, unit tests and lint passed.

Lint/type-check/build:
- `ktlintCheck` (main and test source sets) - Pass. The compiler warning in `core/oauth/domain/OAuthTokenService.kt:84` predates this task and was not changed.

`.taskcheck`:
- Pass: `sh scripts/check.sh` exited with 0 and printed "== all checks passed". It ran on HEAD `676a3dfcbbfa106b464f5f3fdff2a9bb79ba6b9f` with a clean working tree.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
