# Code Review

Date: 2026-10-21

Task: gtd-convert-event - Convert task to calendar event

Scope: committed changes of the task, `git diff a8fe0266..edcdb0f1` (two commits: the implementation 8caa872f and the test-only commit edcdb0f1).

Requirements:
- `tasks/gtd-convert-event.md` acceptance criteria and recorded assumptions
- SPEC.md §5.3 (inbox processing "To calendar (event)"), §5.4 (`/convert-to-event`), §4.2 (Problem Details, ETag/If-Match)
- docs/QUALITY.md, AGENTS.md project notes (module boundaries, generated files)
- Existing convention: `TaskToNoteService` / `TaskToNoteController` (gtd-convert-note), `CalendarEventService.create`, `CalendarWriteThrough`

Git range:
- Branch: main
- HEAD: edcdb0f1791c96df67b97bd0cd5bcf7b3bb4f69d
- Base/start: a8fe02662740b1b60542fb4cd96988739c8533ef
- Commits reviewed: 2 (8caa872f gtd-convert-event: POST /tasks/{id}/convert-to-event; edcdb0f1 gtd-convert-event: edge-case and rollback tests)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## What was checked

- `TaskToEventService.convert`: loads the task (404 for unknown or trashed tasks), collects its links, moves the task to the Trash after the If-Match callback runs (412 before any change), and creates the event through `CalendarEventService.create`, the calendar module's public service, with no access to its repositories. It then copies the links over as manual links with the same direction and label. It skips linked items that no longer exist, so `LinkService.create` cannot mark the transaction rollback-only. Everything runs in one `@Transactional` method. Every failure (`InvalidRequestException`, `ConflictException`, `PreconditionFailed`, provider errors) is a runtime exception and undoes the task deletion.
- Synced calendars: `CalendarWriteThrough` records the provider write and pushes it in `beforeCommit`, after the links are copied. So a failed conversion cannot leave an orphaned remote event, and a provider failure aborts the commit (502 documented).
- Field limits: the task title (500) and notes (100,000) limits equal the event title and description limits. Blank notes give no description, as the recorded assumption says.
- Controller: `POST /api/v1/tasks/{id}/convert-to-event` consumes JSON and answers 201 with `Location` `/api/v1/calendar/events/{id}` (matches the `CalendarEventController` mapping) and the event ETag. If-Match is optional, as in convert-to-note. The request fields match the createEvent time fields (recorded assumption) and AC #1.
- OpenAPI: the `api/openapi.yaml` path and the `ConvertToEventRequest` schema match the controller. The regenerated `web/src/app/api/generated` files match the YAML. They are outside `touches`, which is recorded as an assumption; `api:check` requires them.
- Security: the endpoint is under the authenticated `/api/v1` chain (401 without a user) and CSRF-protected (403 without a token, nothing converted). No task notes are logged and there are no secrets in the diff.
- Commit edcdb0f1 changes only tests (`TaskToEventEdgeCaseTests.kt`, 460 lines; `TaskToEventRollbackTests.kt`, 79 lines). No production code changed after 8caa872f. Checked the contents:
  - `assertUnchanged` checks that `task.deleted_at` is null and that no `calendar_event` row exists.
  - Rollback is covered for: a read-only local calendar (409), no `calendarId` with no default calendar (400 with Problem Details), a read-only synced calendar (409, no provider write), an external source without sync support (409), a failing provider (502: no remote event, the task's links and search document are restored), invalid time zones or dates, a weak If-Match, and malformed bodies or ids.
  - `TaskToEventRollbackTests` makes the link copy throw through a `LinkService` spy. It proves that the event, the task deletion and the search index entries are all rolled back.
  - Also covered: the synced write-through success path, CREATED/DELETED change events, the search index switching from the task to the event, the ETag matching GET, the one-hour default and keeping a given time zone, whitespace-only notes, a completed task, If-Match `*`, blank and list forms, a double conversion (404, one event), links to tasks, trashed tasks and unresolvable types, and restoring the task from the Trash.
  - The tests check behavior through HTTP and the database. Mocking is limited to the one failure injection.
- Test run: per the parent worker, `./gradlew test --tests 'ch.sonensei.cockpit.gtd.convert.*'` gave 58 tests, 0 failures. I did not re-run it (read-only review). The criteria are not yet recorded in `tasks.py verify`; recording them is the parent's job.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-2 | Link carry-over logic duplicated verbatim from TaskToNoteService**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/gtd/convert/domain/TaskToEventService.kt:62-73`
    - Category: Quality
    - Problem: The `linked.forEach { ... }` block and the private `exists()` helper are identical to `TaskToNoteService.kt:52-63`, including the comment about rollback-only.
    - Why it matters: The carry-over rule is subtle: sync links become manual and missing targets are skipped to avoid a rollback-only transaction. Two copies in the same package can drift apart when only one gets fixed.
    - Suggested fix: Extract a small package-internal helper in `gtd/convert/domain` (for example `carryOverLinks(from: ItemRef, to: ItemRef, links: List<Link>)`) and use it in both services. Optional; leaving it as is is acceptable.

Resolved during review:
- CR-1 (P3, Tests): "Committed tests do not cover the 409 read-only calendar and no-default-calendar 400 paths". Resolved by edcdb0f1. `TaskToEventEdgeCaseTests` "a read-only local calendar is a conflict and changes nothing" (line 166) and "without a calendar and without a default calendar it is a bad request and changes nothing" (line 178) assert the status and `assertUnchanged(task)`. Read-only synced and unsupported external calendars are covered too.

## Review conclusion

No material issues found.
