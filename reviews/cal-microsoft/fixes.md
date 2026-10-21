# Fix Report

Date: 2026-10-21

Task: cal-microsoft - Microsoft Graph calendar adapter

Scope: current task; findings CR-1 to CR-6 of `reviews/cal-microsoft/review.md`, limited to `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft` and `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft` (plus their test resources). Base commit 56df48eaf8823eb85b5572575b56d75ea0cb0806, HEAD c1e66634.

Note: when the fixer started, the parent worker's commit c1e66634 already contained the code changes and regression tests for all six findings, including CR-3, CR-4 and CR-6. The fixer checked each one against the current code and against the finding, then re-ran the formatter, the focused tests and lint. Nothing needed to change, so the fixer made no code changes.

## Fixed findings

### CR-1 - "This and following" dropped earlier exceptions of the old series (fixed in c1e66634)

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/MicrosoftCalendarAdapter.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/MicrosoftCalendarAdapterTests.kt`

Problem:
Patching a series' `recurrence` makes Exchange drop its exceptions, and nothing put them back.

Fix:
When a series master's `recurrence` changes, `updateEvent` now reads the exceptions in the window before the PATCH. Afterwards, `restoreExceptions` re-patches every occurrence that Graph turned back into a plain occurrence, using the fields the exception had. Exceptions whose occurrence the new pattern no longer has stay gone. Tests: `a changed pattern puts back the exceptions Graph dropped, with the fields they had` and `exceptions Graph kept after a pattern change, and changes without a new pattern, are not patched again`.

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.microsoft.*'` - Pass

### CR-2 - Events addressed via `/me/events/{id}`, so shared calendars failed (fixed in c1e66634)

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/MicrosoftCalendarAdapter.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/MicrosoftCalendarAdapterTests.kt`, `MicrosoftCalendarSyncTests.kt`

Problem:
Series reads, instances, PATCH and DELETE used `/me/events/{id}`. That path does not reach calendars from other mailboxes that are shared with the user, so a 404 was taken to mean "gone".

Fix:
`EVENT` is now `/me/calendars/{calendar}/events/{event}`. `calendarExternalId` is passed through `readSeries`, `instances`, `findInstance`, `patch`, `delete`, `deleteOccurrences` and `currentSeries` (via `eventPath`). Test: `events are read, changed and deleted through their calendar`.

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.microsoft.*'` - Pass

### CR-5 - `listCalendars` followed `@odata.nextLink` without a bound (fixed in c1e66634)

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/MicrosoftCalendarAdapter.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/MicrosoftCalendarAdapterTests.kt`

Problem:
A repeated or cyclic next link would loop forever.

Fix:
`listCalendars` now uses the same `MAX_PAGES` guard as `instances` and throws a `CalendarProviderException` when the limit is passed. Test: `calendars beyond the page limit are refused`, which checks for exactly 100 requests.

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.microsoft.*'` - Pass

### CR-3 - An exception moved more than 35 days from its slot could not be found

Files changed (in c1e66634, checked by the fixer):
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/MicrosoftCalendarAdapter.kt` (`findInstance`, `instanceAt`)
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/MicrosoftCalendarAdapterTests.kt`

Problem:
`findInstance` only listed instances within ±35 days of the original start. For an override moved further away, delete silently succeeded and edit failed every time.

Fix:
`findInstance` first searches the weeks around the original start. When that finds nothing, it searches the whole sync window (`window(today)`) and returns null only when neither finds an instance whose original start lies within half a day. `updateEvent`, `createEvent` (override) and `deleteEvent` all use this path. WireMock test: `an exception moved further than the weeks around its occurrence is found in the whole window`. In that test the narrow `/instances` query (from 2026-10-12) returns nothing, and the window query (from 2025-10-21) returns the exception moved from 16 Nov 2026 to 1 Feb 2027. The test checks that the DELETE goes to that instance with `If-Match`.

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.microsoft.*'` - Pass

### CR-4 - Attendee changes rewrote every attendee as `required`

Files changed (in c1e66634, checked by the fixer):
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/GraphEventMapping.kt` (`keepAttendeeTypes`)
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/MicrosoftCalendarAdapter.kt` (called in `updateEvent`, `writeInstance`, `restoreExceptions`)
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/MicrosoftCalendarAdapterTests.kt`

Problem:
`toBody` wrote `type: required` for every attendee, so a PATCH that changed attendees turned optional and resource attendees into required ones.

Fix:
After the diff is built, `keepAttendeeTypes` gives each attendee in the PATCH body the `type` it has in Graph's current copy. Attendees are matched by email, ignoring case. Attendees Graph does not have yet stay `required`. Test: `changed attendees keep the type Graph has for them, new ones are required`. It sends an optional and a resource attendee plus a new one and checks the PATCH body: `optional`, `resource`, `required`.

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.microsoft.*'` - Pass

### CR-6 - Formatter-mangled conditionals

Files changed (in c1e66634, checked by the fixer):
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/MicrosoftCalendarAdapter.kt` (cursor of the run, now around lines 117-120)
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/GraphEventMapping.kt` (recurrence in `toBody`, now lines 152-155)

Problem:
ktlint had wrapped long conditions into unreadable forms such as `if (state.mode ==\n NEXT\n)` and `if (rule ==\n null\n)`.

Fix:
The adapter now uses the locals `continuing = state.mode == NEXT` and `seen`, and builds the `Cursor` on one line. The mapping now uses `event.rrule?.let { ... }` and `body.set("recurrence", pattern ?: json.nullNode())`. After `./gradlew ktlintFormat` both stay on one line each: the formatter changed no files, and `ktlintCheck` passes without suppressions.

Validation:
- `./gradlew ktlintFormat` - no changes
- `./gradlew ktlintCheck` - Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.microsoft.*'` - BUILD SUCCESSFUL. The test task ran (results from 2026-10-21T11:26Z): GraphEventMappingTests 41, GraphRecurrenceTests 37, MicrosoftCalendarAdapterTests 46, MicrosoftCalendarSyncTests 4 (Postgres via Testcontainers), WindowsZonesTests 20. Total 148 tests, 0 failures, 0 errors, 0 skipped.

Full test suite:
- Not run by the fixer. The fixer changed no code, so the parent's run of `sh scripts/check.sh` on c1e66634 still applies.

Lint/type-check/build:
- `cd server && ./gradlew ktlintFormat` - no files changed
- `cd server && ./gradlew ktlintCheck` - Pass
- detekt - Not applicable (the server build has no detekt task)

`.taskcheck`:
- Not run (left to the parent worker, which runs it before `done`)

Remaining unresolved findings:
0

## Final status

No valid findings required code changes. All six findings (CR-1 to CR-6) are already fixed in c1e66634, and validation passed.
