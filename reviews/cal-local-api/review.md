# Code Review

Date: 2026-10-20

Task: cal-local-api - Calendar API for local calendars

Scope: `git diff 9e2d0743e12cb9df3e5e16f10f113cdb9fbceec2 de56a2f` restricted to the task's files: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/`, `server/src/test/kotlin/ch/sonensei/cockpit/calendar/api/`, `api/openapi.yaml`, `web/src/app/api/generated/` (checked for consistency with the contract only) and `reviews/cal-local-api/`. Committed changes only. This is a re-review. The earlier review had findings CR-1 to CR-3, and they were fixed in addf13a.

Reviewed revision: de56a2f

Requirements:
- `tasks/cal-local-api.md`: the 5 acceptance criteria and the recorded ASSUMPTION notes (scope defaults, occurrence format, time-field semantics, DELETE THIS/FOLLOWING behaviour, the CALENDAR change-event type)
- SPEC.md §4.2 (UUIDv7, ETag/If-Match, Problem Details, Trash), §6 (calendar module, §6.4 endpoint list), §14 (conventions)
- docs/ARCHITECTURE.md, docs/QUALITY.md, AGENTS.md project notes
- Previous review `reviews/cal-local-api/review.md` (old revision), `reviews/cal-local-api/fixes.md`, `reviews/cal-local-api/tests.md`

Git range:
- Branch: main
- HEAD: de56a2f
- Base/start: 9e2d0743e12cb9df3e5e16f10f113cdb9fbceec2
- Commits reviewed: 7 task commits (a8cae2c, cc50c8f, f939ad0, 7ac3610, addf13a, e64ad1c, de56a2f). The range also contains claim, heartbeat and board commits from other agents. They do not touch the reviewed files.
- Uncommitted changes: none (`git status --short` is empty)

Files read in full: `CalendarEventService.kt`, `EventCommands.kt`, `CalendarEventController.kt`, `CalendarDtos.kt`, `CalendarController.kt`, `CalendarService.kt`, `EventTrashContributor.kt`. I also read the `listEventOccurrences` section of `api/openapi.yaml` and the `accept` headers of the generated `fn/calendars/*.ts`. I listed the test names in all test classes (131 test functions plus `OpenApiContractTest`, which matches the 132 in tests.md) and read the relevant parts of `CalendarSeriesEdgeCaseWebTests.kt`.

I could not run Gradle in this review session, so the test results are the ones reported in `tests.md`.

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Status of the earlier findings

- **CR-1 (FOLLOWING/ALL through a moved override): resolved.** `carryExceptions` now takes the addressed override's recurrence id and requested times (`CalendarEventService.kt:366`, `:380-382`, `:395-397`). `updateSeries` and `splitSeries` pass them in through `addressedTiming` (`:279`, `:331`, `:408-411`). Regression tests: `CalendarSeriesEdgeCaseWebTests.kt:237`, `:251`, `:263`, `:290`. The content-only case stays covered (`:221`, `:317`).
- **CR-2 (racing THIS creates end in a 500): resolved.** `updateOccurrence` turns a `DataIntegrityViolationException` from inserting a new override into `ConflictException` (409) (`CalendarEventService.kt:223-230`). An update of an existing override rethrows the exception unchanged. Deterministic regression test: `CalendarSeriesEdgeCaseWebTests.kt:562`.
- **CR-3 (200 of `listEventOccurrences` documented as problem+json): resolved.** The 200 response now declares `application/json` (`CalendarEventController.kt:59-63`). In `api/openapi.yaml:461-472` the 200 response has only `application/json`, and `application/problem+json` appears only on the 400. The generated `list-event-occurrences.ts:39` sends `accept: 'application/json'`.
- **TW-1 (an override of a trashed series was reachable): resolved.** `load` treats an override whose master is not active as not found (`CalendarEventService.kt:590-594`). Tested at `CalendarSeriesEdgeCaseWebTests.kt:602` and `:613`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-4 | A time change with scope ALL or FOLLOWING through an all-day override of a timed series makes the whole series all-day and drops all its exceptions**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarEventService.kt:265-266` (ALL) and `:307-308` (FOLLOWING); `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/EventCommands.kt:98-106` (`Timing.shifted`) and `:149` (`Timing.resolve`)
    - Category: Correctness
    - Problem: `PATCH THIS {"allDay":true}` can make one occurrence of a timed series all-day (tested at `CalendarSeriesEdgeCaseWebTests.kt:186`). If the client then sends only `startDate` for that override with `scope=ALL` or `FOLLOWING`, the request does not send `allDay`. Even so, `Timing.resolve(shown, input, zone)` returns an `AllDay`, because `allDay = input.allDay ?: (current is AllDay)` and `current` is the all-day override. `oldTiming.shifted(shown, requested)` then switches on the kind of `to`, so the timed master gets an `AllDay` timing. `moved` is true, and `carryExceptions` calls `from.moveOccurrenceId(id, to)`, which returns `null` for the change from timed to all-day. As a result, every EXDATE and every override of the series is dropped. That includes the addressed override and occurrences the user had deleted earlier, which come back.
    - Why it matters: Moving one all-day exception by a day for "all events" silently turns the whole series into all-day events and discards the user's other exceptions. The client never asked for a kind change. The task assumption says the series "moves by the same wall-clock offset", and the documented drop of exceptions only covers a requested change between timed and all-day (`PATCH ALL turns a timed series all-day...`, `:159`). The precondition is rare, so this is P3.
    - Suggested fix: Change the series kind only when the request sends `allDay`. Otherwise, shift the master by the date offset and keep its own kind (for a timed master, move by whole days in its zone). Alternatively, reject the request with 400 when the addressed occurrence's kind differs from the series and `allDay` is not sent. Add a web test for either behaviour, or record the current behaviour with `tasks.py assume` if it is intended.

- [ ] **CR-5 | Changing the status of the series with scope ALL brings back occurrences deleted with `DELETE scope=THIS`**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/EventCommands.kt:237` (`EventContent.propagate`), used from `CalendarEventService.kt:271-274` and `:317-320`; the deletion happens at `CalendarEventService.kt:239-241`
    - Category: Correctness
    - Problem: `DELETE ?scope=THIS` on an occurrence that has an override deletes it by setting the override's `status = CANCELLED`. A later status change with ALL (or FOLLOWING) copies the new status to every override whose status equals the master's old status: `if (event.status == before.status) event.status = status`. Example sequence:
        1. Delete one moved occurrence with THIS. Its override is now CANCELLED.
        2. Cancel the whole series with `PATCH ALL {"status":"CANCELLED"}`.
        3. Un-cancel it with `PATCH ALL {"status":"CONFIRMED"}`.

      In step 3 `before.status` is CANCELLED, so the override deleted in step 1 becomes CONFIRMED and shows up again in `GET /calendar/events`. The propagation cannot tell "deleted with THIS" apart from "cancelled with the series".
    - Why it matters: An occurrence the user deleted reappears without a request for it. The sequence is uncommon, so this is P3.
    - Suggested fix: Exclude overrides that are CANCELLED from status propagation, so that a deleted occurrence never inherits a non-cancelled status from the master. Alternatively, record a THIS delete of an overridden occurrence as an EXDATE and delete the override row. Add a web test for the cancel and un-cancel sequence.

## Checked without findings

- AC1: `GET/POST/PATCH /api/v1/calendar/calendars` and `GET /api/v1/calendar/events?from=&to=&calendarIds=` exist. The range check is in place (to > from, at most 400 days, 400 Problem Details). By default only visible calendars are listed. A new default calendar flushes the old one first, which respects the partial unique index.
- AC2: scope defaults and occurrence parsing follow the recorded assumptions. For FOLLOWING, COUNT becomes UNTIL on the old series, and the remaining COUNT counts EXDATEs. UNTIL is `occurrence - 1s` in UTC (timed) or the day before (all-day). Overrides are recreated with delete and flush before insert, because `parent_event_id` and `recurrence_id_*` cannot change. DELETE THIS, FOLLOWING and ALL behave as documented. If-Match uses the addressed event's version, and the If-Match check runs before any write.
- AC3: every change path calls `changed()` (re-index and change event). Overrides that are removed publish `ItemDeletedEvent` and DELETED. Trash: overrides are hidden in the list, restore re-indexes, and purge removes the overrides with their links. Tests: `CalendarEventWebTests.kt:241`, `:266`, `:328`; `CalendarSeriesEdgeCaseWebTests.kt:483-545`, `:578-648`.
- AC5 and integration: the regenerated contract and client match the controller annotations (`accept` headers checked for all 9 generated functions). Errors are Problem Details. The idempotent create handles a lost race through `existingCreate` in a new transaction.
- Security: the endpoints are under `/api/v1` and need authentication. No logging was added to the reviewed main code (grep for logger/println finds nothing). Input lengths, RRULE, URL scheme and e-mail are validated. No secrets or personal data were found in the diff.
- Tests: 131 test functions across the 9 classes in `calendar.api`, plus `OpenApiContractTest`, which matches the 132 tests in tests.md. All earlier findings have regression tests.

## Review conclusion

No material issues found. The earlier findings CR-1 to CR-3 and the trashed-override bug (TW-1) are resolved and covered by tests. CR-4 and CR-5 are low-priority edge cases in how series edits propagate. The parent worker may fix them, or accept them with a recorded `tasks.py assume`. Neither blocks completion of the task. I did not run the test suite in this review. The parent must use its own test runs as evidence for criteria 4 and 5.
