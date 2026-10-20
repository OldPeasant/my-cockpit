# Code Review

Date: 2026-10-20

Task: cal-local-api - Calendar API for local calendars

Scope: `git diff 9be84bf7..3d08c222 -- server/ api/` (committed changes only). The generated web client (`web/src/app/api/generated/`) was checked only for consistency with `api/openapi.yaml`.

Requirements:
- `tasks/cal-local-api.md`: acceptance criteria and the recorded ASSUMPTION notes
- SPEC.md §4.2 (UUIDv7, ETag/If-Match, Problem Details, soft delete/Trash), §4.3, §4.4 (search), §6.1, §6.2, §6.4
- docs/ARCHITECTURE.md and docs/QUALITY.md
- AGENTS.md project notes (module boundaries, generated files, no logging of content)
- Parent agent instructions (focus on series splitting, overrides, EXDATEs, DST, all-day, COUNT/UNTIL, locking, change events, search, Trash, JPA pitfalls, OpenAPI accuracy)

Git range:
- Branch: main
- HEAD: 3d08c2229e259a7ae5d30e6afbcda397fe6a5902
- Base/start: 9be84bf73ca170ddaa7300e1d7f4d6ac78048a4d
- Commits reviewed: 2 (3880a7f9 implementation; 3d08c222 tests, regenerated openapi.yaml and web client)
- Uncommitted changes: none at review start

Files read in full: `CalendarController.kt`, `CalendarService.kt`, `CalendarEventController.kt`, `CalendarEventService.kt`, `EventCommands.kt`, `CalendarDtos.kt`, `EventTrashContributor.kt`, the three test classes (key sections). Context read: `calendar/model/*` (entity, repositories, indexer, item resolver), `calendar/recurrence/OccurrenceExpander.kt`, migration `V202610201351__calendar_model.sql`, `core/web/ProblemDetailsAdvice.kt`, `core/web/ETags.kt`, `core/trash/domain/TrashContributor.kt` and `TrashService.kt`, `core/events/domain/ChangeEventPublisher.kt`.

Commands and results:
- `git log --oneline 9be84bf7..3d08c222` -> 2 commits as above.
- `git diff --stat 9be84bf7..3d08c222 -- server/ api/` -> 12 files, +3807 lines.
- `./gradlew test --tests 'ch.sonensei.cockpit.calendar.api.*'` -> could not be run by the reviewer: the sandbox denied running Gradle. Instead I read the existing results in `server/build/test-results/test/` (written 2026-10-20 18:39 local, after commit 3d08c222 at 18:37): CalendarEventWebTests 17 tests, CalendarSeriesWebTests 15, CalendarWebTests 6; 0 failures, 0 errors, 0 skipped. The parent should re-run this itself before recording evidence.
- `grep -A2 "application/problem+json:" api/openapi.yaml | grep -F schemas/ | grep -v ProblemDetail` -> `OccurrenceListResponse` (new) and `ChecklistResponse` (existing precedent).
- `grep -rln "accept: 'application/problem+json'" web/src/app/api/generated/fn` -> `calendars/list-event-occurrences.ts`, `tasks/list-checklist-items.ts`.

Findings CR-1 to CR-3 were found by reading the code and tracing it by hand. I could not run a reproducing test, because the reviewer may not add tests and Gradle could not be run.

Summary: 0 P0, 0 P1, 1 P2, 2 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | FOLLOWING / ALL on an occurrence that already has a moved override do not move that occurrence to the requested times**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarEventService.kt:296` (also `:255`, `:368-369`)
    - Category: Correctness
    - Problem: For `scope=FOLLOWING` (and for `scope=ALL` addressed through an override or `occurrence=`), the new series times come from `originalTiming.shifted(shown, requested)`, where `shown` is the override's current (moved) times. The override is then carried over by `carryExceptions`. Because the override was moved earlier, `unmoved` is false, so it keeps its old times (`val timing = if (unmoved) to.occurrence(moved) else Timing.of(override)`). It is recreated at the new recurrence id with those old times, and the addressed occurrence never gets the times the client sent. Example with the weekly 09:00 series used in the tests:
        1. `PATCH ?scope=THIS&occurrence=<23 Mar 09:00>` moves 23 March to 11:00.
        2. `PATCH ?scope=FOLLOWING&occurrence=<23 Mar 09:00>` with `start` = 14:00 starts the new series at 12:00, since 09:00 + (14:00 - 11:00) = 12:00.
        3. The recreated override for its first occurrence still shows 23 March at 11:00. No occurrence is at 14:00.

      `ALL` behaves the same way: the series shifts by +3h, and the dragged occurrence stays at 11:00. This contradicts the documented API contract in `EventPatchRequest` (`CalendarDtos.kt:286`, "the time fields are the new times of that occurrence") and the task assumption "the time fields are the new times of that occurrence and the series moves by the same wall-clock offset". No test covers FOLLOWING or ALL with time fields on an occurrence that has a moved override. `PATCH FOLLOWING splits the series...` and `PATCH ALL with an occurrence moves the series...` only move generated occurrences.
    - Why it matters: Dragging an already moved occurrence in the week view (SPEC §6.3) and picking "this and following" or "all" makes the dragged event snap back to its old time, while every other occurrence moves. The user's change is silently lost for the occurrence they actually edited.
    - Suggested fix: In `splitSeries` and `updateSeries`, when `override` (the addressed occurrence's override) is set and `fields.timing` was sent, give that override the resolved requested times (`Timing.resolve(shown, input, zone)`) after the carry or recreation. One way is to pass the addressed recurrence id and its new timing into `carryExceptions`. Add web tests for FOLLOWING and for ALL with `start` on an occurrence that was first moved with THIS. Content fields are a separate question: `PATCH ALL passes changed fields on...` asserts on purpose that the addressed override keeps its own values. Record that as a decision (`tasks.py assume`) if it stays.

## P3 Low

- [ ] **CR-2 | Concurrent or double-submitted `scope=THIS` edits of the same generated occurrence end in a generic 500**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarEventService.kt:218` (insert via `events.saveAndFlush(override)` in `updateOccurrence`)
    - Category: Correctness
    - Problem: If-Match is checked against the master's version, and creating an override does not change the master. So two concurrent THIS PATCHes (double click, client retry) for the same occurrence both pass the check. Both then insert an override, and the second one violates the unique index `calendar_event_override_occurrence (parent_event_id, recurrence_id_at, recurrence_id_date)`. The resulting `DataIntegrityViolationException` is not mapped by `ProblemDetailsAdvice`, so the client gets `500 An unexpected error occurred` and an ERROR log. The documented answer for concurrent changes is `409 Changed concurrently`.
    - Why it matters: A plausible user action produces a server error instead of the documented conflict response, and the client cannot tell it is safe to reload and retry.
    - Suggested fix: Bump the master's version when an override is created (for example, touch the master so its `@Version` increments), so the loser fails with `OptimisticLockingFailureException` (409). Or catch `DataIntegrityViolationException` around override creation and throw `ConflictException`.

- [ ] **CR-3 | `listEventOccurrences` documents its 200 body as `application/problem+json`, and the generated client asks for it**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarEventController.kt:59`
    - Category: Integration
    - Problem: The 200 `@ApiResponse` has no `content`, so springdoc takes both `produces` types. `api/openapi.yaml` lists `OccurrenceListResponse` under `application/problem+json`, and the generated `web/src/app/api/generated/fn/calendars/list-event-occurrences.ts` sends `accept: 'application/problem+json'`. Successful occurrence lists are then served with a Problem Details content type. The other new GET endpoints (`getEvent`, `getCalendar`) avoid this by declaring `content = [Content(mediaType = APPLICATION_JSON_VALUE, ...)]` on the 200 response. `ChecklistController` has the same pre-existing issue.
    - Why it matters: The API contract is inaccurate. Clients or interceptors that treat `application/problem+json` as an error format would misread successful responses.
    - Suggested fix: Add `content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = OccurrenceListResponse::class))]` to the 200 response, then regenerate `api/openapi.yaml` and the web client.

## Checked without findings

- Calendars: GET/POST/PATCH match the AC. The single default is flushed before a new default is set (partial unique index). Calendar change events use type `CALENDAR` (assumption recorded). If-Match is enforced through `ETags.checkIfMatch` (428/412).
- Events: the UUIDv7 check on client ids, the idempotent create, and the 409 for ids in the Trash all work. The `DataIntegrityViolationException` retry in the controller runs a new transaction (the service transaction has already rolled back; `open-in-view=false`).
- Series logic: COUNT is converted to UNTIL on the old series, and the remaining COUNT includes EXDATEs (RFC 5545). UNTIL is `occurrence - 1s` in UTC (timed) or the day before (all-day). Wall-clock shifting across DST and zone changes is consistent between `Timing.shifted` and `moveOccurrenceId`. Exceptions are dropped when the kind changes (timed/all-day). Override recreation deletes and flushes before inserting, which respects the immutable `parent_event_id`/`recurrence_id_*` columns and the unique index.
- Search and Trash: `CalendarEventIndexer.index` runs on every change, including override cascade. Removed overrides publish `ItemDeletedEvent` (search and links cleaned). Purge removes overrides with their links. `TrashService` publishes the master's change and delete events.
- Security: all endpoints are under `/api/v1` and need a login (tested). No new logging of event content. Input lengths, URL scheme, e-mail and the RRULE are validated. Errors are Problem Details.

## Review conclusion

Material issues found; fix before completing the task. CR-1 is a correctness defect in the documented FOLLOWING/ALL time semantics for occurrences that already have a moved override, and it has no test. CR-2 and CR-3 are low-priority robustness and contract-accuracy issues that the parent may fix or record as accepted. Because I could not run the test suite myself, the parent must re-run `./gradlew test --tests 'ch.sonensei.cockpit.calendar.api.*'` for evidence.
