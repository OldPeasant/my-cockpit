# Fix Report

Date: 2026-10-20

Task: cal-local-api - Calendar API for local calendars

Scope: Current task. Findings CR-1, CR-2 and CR-3 from `reviews/cal-local-api/review.md`, plus the production bug found by the test-writer's tests ("an override of a trashed series is not found"), as passed on by the parent worker. Base commit c2a28608; task commits 14503754, dad39f09, 8e91e319, d3f1d7ea.

Before the fixes, `./gradlew test --tests 'ch.sonensei.cockpit.calendar.api.CalendarSeriesEdgeCaseWebTests'` failed 3 of 29 tests: the two "through a moved override puts that occurrence at the requested time" tests (expected 2026-03-24T09:00:00Z, got 08:00:00Z) and "an override of a trashed series is not found" (GET of the override returned 200 instead of 404).

## Fixed findings

### CR-1 - FOLLOWING / ALL through an occurrence with a moved override do not put that occurrence at the requested times

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarEventService.kt`

Problem:
`updateSeries` (ALL) and `splitSeries` (FOLLOWING) shifted the series by the offset between the override's current (moved) times and the requested times. `carryExceptions` then recreated or kept the addressed override with its old moved times, because it is not "unmoved". So the occurrence the client dragged never got the times it sent.

Fix:
Both methods now keep the resolved requested times (`Timing.resolve(shown, input, zone)`) and pass them to `carryExceptions` as `addressed` (the override's original recurrence id plus the requested timing; helper `addressedTiming`). `carryExceptions` gives the addressed override these times, both when the override is kept in place and when it is recreated at a new recurrence id. Other overrides keep the existing rule (unmoved ones follow the series, moved ones keep their times). A content-only change (no time fields) is unchanged, so "PATCH FOLLOWING through a moved override with content only keeps its moved time" still passes.

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.calendar.api.*'` - Pass (the two CR-1 tests from the test-writer now pass)

### CR-2 - Concurrent `scope=THIS` edits of the same generated occurrence end in a 500

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarEventService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/api/CalendarSeriesEdgeCaseWebTests.kt` (new test)

Problem:
If-Match is checked against the master's version, and creating an override does not change the master. A second concurrent insert of an override for the same occurrence violated the unique index `calendar_event_override_occurrence`. The unmapped `DataIntegrityViolationException` became a 500.

Fix:
In `updateOccurrence`, when a new override is created (`existing == null`), a `DataIntegrityViolationException` from `saveAndFlush` becomes `ConflictException("The occurrence was changed concurrently")`, which is 409 Problem Details. This is the reviewer's second option. I did not bump the master version, because that would change the master's ETag on every THIS edit. Violations while updating an existing override are rethrown unchanged.

Regression test `a THIS change racing another one for the same occurrence is a conflict` sets up the race deterministically through the web stack. The first THIS PATCH creates the override. That row is then marked `deleted_at` through JDBC, so the service's lookup (which only sees overrides that are not deleted) misses it, but the unique index still covers it. The second THIS PATCH now gets 409 with `application/problem+json`, and no row is added.

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.calendar.api.*'` - Pass

### CR-3 - `listEventOccurrences` documents its 200 body as `application/problem+json`

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarEventController.kt`
- `api/openapi.yaml` (regenerated)
- `web/src/app/api/generated/fn/calendars/list-event-occurrences.ts` (regenerated)

Problem:
The 200 `@ApiResponse` had no `content`, so springdoc listed `OccurrenceListResponse` under both produced media types. The generated client then sent `accept: 'application/problem+json'`.

Fix:
Added `content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = OccurrenceListResponse::class))]` to the 200 response, the same way `getEvent` and `getCalendar` do it. Then I regenerated the contract and the client. In `openapi.yaml`, the only change is that the `application/problem+json` entry of that 200 response is gone. The generated client now sends `accept: 'application/json'`. The pre-existing `ChecklistController` case was left alone (outside this task's touches). `ng-openapi-gen` still prints its "Overwriting variant ... application/problem+json" warning for that endpoint.

Validation:
- `UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'` - Pass (contract regenerated)
- `npm run api:gen` (in `web/`) - Pass
- `./gradlew check` (includes `OpenApiContractTest` without the flag) - Pass

### TW-1 - An override of a trashed series is still reachable (test "an override of a trashed series is not found")

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarEventService.kt`

Problem:
After DELETE of a series master (to the Trash), `GET /calendar/events/{overrideId}` still returned 200. `load` only checked the override's own `deleted_at`, while overrides share their master's Trash state (repository query, indexer and `EventItemResolver` all treat them as gone). For PATCH and DELETE, `resolve` already looked up the master as active, so they returned 404 here. The test's first failing assertion was the GET.

Fix:
`load` now also treats an override whose series master is not active as not found (404). `get`, `update` and `delete` all go through `load`, so they behave the same way. After a restore, the override is reachable again (`a trashed series with overrides is listed once and restored with its overrides` still passes).

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.calendar.api.*'` - Pass

## Other changes

`./gradlew ktlintFormat` reformatted two test-writer test files: `CalendarEventControllerRaceTest.kt` and `EventCommandsTest.kt`. Only line wrapping changed; no assertion changed. Without this, the lint step of `./gradlew check` would fail.

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat` - Pass
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.api.*'` - Pass. 115 tests, 0 failures: CalendarSeriesEdgeCaseWebTests 30 (including the new CR-2 test), CalendarEventEdgeCaseWebTests 14, CalendarEventWebTests 17, CalendarSeriesWebTests 15, CalendarWebTests 6, CalendarEventControllerRaceTest 4, EventCommandsTest 29.
- `cd server && UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'` - Pass
- `cd web && npm run api:gen` - Pass

Full test suite:
- `cd server && ./gradlew check`, first run - Fail. 1695 tests, 1 failed: `ClockConfigurationTests > the application clock is the system clock in UTC`, with `2026-10-20T16:56:21.308320Z should be >= 2026-10-20T16:56:21.308320028Z`. This is a timing flake in `core/persistence`, which this fix does not touch: the application clock has microsecond precision, `Instant.now()` has nanosecond precision. It is unrelated to this task and was not changed.
- `cd server && ./gradlew check`, second run - Pass (BUILD SUCCESSFUL)

Lint/type-check/build:
- Covered by `./gradlew check` (ktlint for the server) and by `.taskcheck` (web lint, tests and build; android) - Pass

`.taskcheck`:
- `sh scripts/check.sh` - Pass ("all checks passed"; the web build prints the existing initial-bundle budget warning)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed. `ClockConfigurationTests` is flaky outside this task's scope; it failed once and passed on the re-run.

# Fix Report - second cycle (CR-4, CR-5)

Date: 2026-10-20

Task: cal-local-api - Local calendar API (SPEC.md §6.4)

Scope: findings CR-4 and CR-5 of the re-review in `reviews/cal-local-api/review.md` (HEAD 9c6ba28)

## Fixed findings

### CR-4 - A time change with scope ALL or FOLLOWING through an all-day override of a timed series makes the whole series all-day and drops its exceptions

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarEventService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/api/CalendarSeriesEdgeCaseWebTests.kt`

Problem:
`updateSeries` and `splitSeries` resolved the request's time fields against the addressed override. When the override was all-day and the series timed, `Timing.resolve` defaulted `allDay` to the override's kind. A PATCH with only `startDate` therefore turned the series all-day, and the timed-to-all-day change dropped every EXDATE and override.

Fix:
A new helper `seriesBase` chooses the times the request applies to. It takes the addressed override's times only when `allDay` is sent or the override has the same kind as the series. Otherwise it takes the series' own original occurrence. The series changes kind only when `allDay` is sent. Time fields of the other kind (for example `startDate` for a timed series) are now rejected with 400 Problem Details instead of being converted silently. `Timing.shifted` and `Timing.resolve` are unchanged.

Validation:
- New test `PATCH ALL or FOLLOWING with dates through an all-day override of a timed series is rejected and changes nothing`: 400 with field `startDate` for ALL and for FOLLOWING. The series stays timed and keeps its start and EXDATE, the override is unchanged, and no rows are added.
- New test `PATCH ALL with a start through an all-day override moves the timed series and keeps its exceptions`: the series moves by one day, stays timed, and its EXDATE moves with it.
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.api.*' --tests '*OpenApiContractTest'` - Pass

### CR-5 - Changing the status of the series with scope ALL brings back occurrences deleted with `DELETE scope=THIS`

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/EventCommands.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/api/CalendarSeriesEdgeCaseWebTests.kt`

Problem:
`EventContent.propagate` copied a new master status onto every override that still had the old status. An override that was CANCELLED because its occurrence had been deleted became CONFIRMED again when the series was un-cancelled, so the deleted occurrence reappeared.

Fix:
`propagate` no longer changes the status of a CANCELLED override (a removed occurrence). This applies to ALL and FOLLOWING, which share `propagate`.
Trade-off for the parent to note: the model cannot tell a deleted occurrence apart from an override that was cancelled together with its series. After `PATCH ALL {"status":"CANCELLED"}` followed by `{"status":"CONFIRMED"}`, the series' other (non-deleted) overrides stay CANCELLED, which means removed. Before this fix they came back. This follows the reviewer's and the parent's chosen fix. The alternative, recording a THIS delete of an override as an EXDATE and deleting the override row, would avoid the trade-off but changes the DELETE THIS behaviour that existing tests assert. Consider recording this with `tasks.py assume`.

Validation:
- New test `cancelling and un-cancelling the series with ALL does not bring back a deleted occurrence`: the deleted override stays CANCELLED, 6 of 7 occurrences are listed and all are CONFIRMED, and the override is not in the search index.
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.api.*' --tests '*OpenApiContractTest'` - Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat` - Pass
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.api.*' --tests '*OpenApiContractTest'` (Testcontainers Postgres on Docker) - Pass: 135 tests, 0 failures, 0 skipped. That is the earlier 132 plus 3 new tests in CalendarSeriesEdgeCaseWebTests.

Full test suite:
- `cd server && ./gradlew check` - Pass (BUILD SUCCESSFUL)

Lint/type-check/build:
- ktlint and compilation are covered by `./gradlew check` - Pass. No API annotations changed, so `api/openapi.yaml` and the generated web client do not need to be regenerated (OpenApiContractTest passes).

`.taskcheck`:
- `sh scripts/check.sh` - Pass ("all checks passed"; android SKIPPED with COCKPIT_NO_ANDROID=1)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
