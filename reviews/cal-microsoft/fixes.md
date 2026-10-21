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

---

# Fix Report - round 2 (second review)

Date: 2026-10-21

Task: cal-microsoft - Microsoft Graph calendar adapter

Scope: current task; findings CR-1 (P2) and CR-2 (P3) of the second `reviews/cal-microsoft/review.md` (HEAD d6768df0 at review time), limited to `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft` and `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft`. Task base commit 0a701f27cd0e4db92d84a54985f0f5ce2ae0c04d, task commits 2100078c..321a89e2, checked at HEAD ca44d5c4.

Note: when the fixer started, commit 321a89e2 (an earlier session's work, not yet reviewed) already had the code changes and regression tests for both findings. The fixer checked them against the findings and the engine's write order (`CalendarWriteThrough`: master first, then the overrides with `ifMatch = override.externalEtag`). It also ran the formatter, lint and the focused tests. Nothing needed correcting, so the fixer made no further code changes.

## Fixed findings

### CR-1 - A series (ALL) pattern change failed with 409 when the series had overrides (fixed in 321a89e2)

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/MicrosoftCalendarAdapter.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/MicrosoftCalendarAdapterTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/MicrosoftCalendarSyncTests.kt`

Problem:
When the master's `recurrence` is patched, Exchange resets the series' exceptions, and `restoreExceptions` then re-patches them. Either way, every such instance gets a new etag. The engine's next write of the override then compared or sent the exception's old etag. With an empty diff, the adapter threw `RemoteEventChangedException`. Otherwise Graph answered 412. So the client got a false 409, even though Microsoft had already applied the change.

Fix (both options the review suggested, combined):
- An empty diff now succeeds whatever the etag. When Graph already has every field the cockpit would send, `updateEvent` (master) and `writeInstance` (occurrence) send nothing and return Graph's current version with its etag. This loses nothing at Microsoft: any field the cockpit maps that changed remotely makes the diff non-empty, and the PATCH then still goes out with the caller's `If-Match`. KDoc on the class and on `writeInstance` describes this.
- `RestoredEtags` is a new private, bounded (1000 entries), synchronized in-memory map. For each instance `restoreExceptions` patched back (or found already identical to the exception), it records the instance's new etag together with the exception's etag from before. `writeInstance` sends the instance's current etag as `If-Match` only when the caller's `ifMatch` equals that recorded old etag and Graph's instance still has the recorded new etag. In every other case it sends the caller's etag, so a real change at Microsoft still gives 412 and then 409. The entry is dropped after a successful PATCH or DELETE. The same mechanism covers the review's side note: overrides restored by a FOLLOWING split can be edited before the next sync without a false 409. If the entry is lost (restart, eviction), the behavior is the same as before: a spurious 409 until the next sync.
- `restoreExceptions` now also records instances that Graph kept as `exception` with identical fields (empty diff). Instances Graph kept as `exception` but with different fields are still left alone.

Tests:
- Sync test (engine + WireMock + Postgres): `changing the pattern of a whole series with an exception succeeds, the exception keeps Graph's new etag`. An ALL PATCH (`title` + `rrule` with UNTIL) on AAMkSeries1, which has the moved 19 October exception in the window. The API call ends in 200. The writes are exactly `PATCH .../AAMkSeries1` and `PATCH .../AAMkSeries1-20261019` (the restore), with no extra override write. The master has the new title, rule and etag. The override keeps its title and gets Graph's new etag `W/"restored"`.
- Adapter tests: `after a pattern change, an override is written against the exception Graph put back - empty diff or not` (empty diff: no PATCH and etag `restored`; renamed: PATCH with `If-Match: W/"restored"`); `after a pattern change, an override with an etag other than the exception's still is a change at Microsoft` (stale etag: sent unchanged, 412 becomes `RemoteEventChangedException`); `a changed occurrence with a stale etag is sent with it, and Graph's 412 is a change at Microsoft`. The two "unchanged ... whatever the etag" tests now expect success with Graph's etag instead of a refusal. The finding called for this behavior change, and stale etags with real changes are still refused (tests above).

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.microsoft.*' -Pkotlin.daemon.jvmargs=-Xmx3g` - Pass

### CR-2 - A series deleted at Microsoft gave 502 instead of "changed remotely" (fixed in 321a89e2)

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/MicrosoftCalendarAdapter.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/MicrosoftCalendarAdapterTests.kt`

Problem:
`instances()` passed a 404 from `/instances` (the series is gone) to `expectOk`, which made it a `CalendarProviderException` (502) on every path through `findInstance`.

Fix:
New `instancesOrNull` returns null on 404. `instances()` keeps its old behavior on top of it: it is used by `restoreExceptions` and the pre-PATCH exception read, where the master was just read successfully. `findInstance` returns null when either lookup (the weeks around the occurrence, or the whole window) gets 404, and does not query the whole window after a 404. As a result, `updateEvent` of an occurrence (including a cancelled override) throws `RemoteEventChangedException` (409 plus refetch), `deleteEvent` with a recurrence id returns null (already gone, so it succeeds), and `createEvent` of an override fails as before with "Microsoft has no such occurrence".

Tests:
- WireMock test with a 404 from `/instances`: `a series gone at Microsoft has no occurrences - delete succeeds, change is a change at Microsoft`. Delete returns null, change and cancel raise `RemoteEventChangedException`, and create raises `CalendarProviderException`. It checks exactly 4 `/instances` GETs (one per call, no whole-window retry) and no PATCH or DELETE.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.microsoft.*' -Pkotlin.daemon.jvmargs=-Xmx3g` - Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.microsoft.*'` - the first two attempts failed in `:compileTestKotlin` with "Not enough memory to run compilation" (machine under load, about 2 GB free). After `./gradlew --stop`, the run with `-Pkotlin.daemon.jvmargs=-Xmx3g` (a command-line property only, no configuration changed) gave BUILD SUCCESSFUL. JUnit results from 2026-10-21T12:22Z: GraphEventMappingTests 44, GraphRecurrenceTests 37, MicrosoftCalendarAdapterTests 59, MicrosoftCalendarSyncTests 5 (Postgres via Testcontainers), WindowsZonesTests 20. Total 165 tests, 0 failures, 0 errors, 0 skipped. The new tests for CR-1 and CR-2 named above are in the results.

Full test suite:
- Not run by the fixer (left to the parent worker's `sh scripts/check.sh`).

Lint/type-check/build:
- `cd server && ./gradlew ktlintFormat` - no files changed
- `cd server && ./gradlew ktlintCheck` - Pass
- detekt - Not applicable (the server build has no detekt task)
- Kotlin compile - Pass (the remaining compiler warnings are in files outside this task)

`.taskcheck`:
- Not run (left to the parent worker before `done`)

Remaining unresolved findings:
0

## Final status

No valid findings required code changes. CR-1 and CR-2 of the second review are fixed in 321a89e2, and the focused validation passed. The changed code has not been reviewed yet, so test-writer and code-reviewer must run again on it. The parent may want to record the behavior "an empty diff succeeds whatever the etag; restored-exception etags are tracked in memory only" with `tasks.py assume`.
