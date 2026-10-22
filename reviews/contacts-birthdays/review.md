# Code Review

Date: 2026-10-22

Task: contacts-birthdays - Birthdays calendar

Scope: `git diff 639ea532..HEAD -- server` (implementation commit 2def8423, test commit 27d3db79), plus the integration points the change affects (calendar consumers in `contacts/aggregate`, `review`, the web calendar editor, `api/openapi.yaml`, calendar backup/sync).

Requirements:
- tasks/contacts-birthdays.md acceptance criteria and the four recorded assumptions
- SPEC.md §8.3 ("Birthdays shown as a virtual read-only calendar")
- AGENTS.md "Project notes", docs/ARCHITECTURE.md conventions
- Parent instructions (Feb 29 and range logic, listener/transaction behavior, backup/restore, sync, OpenAPI)

Git range:
- Branch: main
- HEAD: 27d3db798648edcd3679d4c0082473512491584f
- Base/start: 639ea5327439d5128bd9ea80a4144a8fcbc97f36
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 1 P2, 2 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Birthday occurrences expose an `eventId` that the API contract and the web editor treat as a fetchable event**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarEventService.kt:117` (with `web/src/app/features/calendar/edit/event-dialog.ts:180-191`, `api/openapi.yaml:11262`)
    - Category: Integration
    - Problem: Virtual occurrences get `eventId = seriesId = contact id`. The documented `OccurrenceResponse` contract (openapi.yaml 11262-11266, from `CalendarDtos.kt`) says `eventId` is "the event that provides its data (the series master for a generated occurrence ...)", and the web client relies on that. Clicking a birthday in the web calendar opens the event dialog (`calendar-view.ts:237`), which treats it as a generated series occurrence (`isGenerated`: eventId === seriesId), re-reads it, then calls `GET /calendar/events/{contactId}` (`event-dialog.ts:191`). The server returns 404 there by design (the assumption in the task notes, covered by the `birthday occurrences cannot be created in, read or changed` test). The dialog then shows its load-failure state, and Retry can never succeed. Dragging is already blocked because the calendar is read-only (`calendar-views.ts:87`). The API gives clients no way to tell a virtual calendar from an ordinary read-only one, so they cannot avoid this.
    - Why it matters: Opening a birthday is the main way to interact with the new calendar, and the existing client shows a broken dialog for it. The OpenAPI description no longer matches what the server returns.
    - Suggested fix: Document the virtual case in the `OccurrenceResponse` schema description (for example, "occurrences of a virtual calendar such as Birthdays have no stored event; `eventId`/`seriesId` identify the source item and `GET /events/{id}` returns 404") and regenerate `api/openapi.yaml`. Then add a follow-up web/android task to handle these occurrences (show them read-only from the occurrence data, or link to the contact). If the parent keeps the current contract, record that as an assumption and create the follow-up task anyway.

## P3 Low

- [ ] **CR-2 | Two first-time creations of the virtual calendar can race on `calendar_external_id`**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarService.kt:104-111`
    - Category: Correctness
    - Problem: `ensureVirtualCalendar` checks for the row and then inserts it, with no lock and no conflict handling. Two contact commits running at the same time, or a contact commit during the `ApplicationReadyEvent` startup check, can both run `REQUIRES_NEW` listeners that insert `virtual:birthdays`. One of them then fails on the unique index `calendar_external_id`. That failure happens in the AFTER_COMMIT `afterCompletion` callback, where Spring catches it and logs it at ERROR. The contact request still succeeds and one calendar exists, so the only effect is a misleading ERROR log. The tests.md report also lists this as untested.
    - Why it matters: In a single-user app this is unlikely and does no harm, but the ERROR log line suggests a real failure.
    - Suggested fix: Catch `DataIntegrityViolationException` around the insert and re-read the existing row, or use an `insert ... on conflict do nothing` before loading it.

- [ ] **CR-3 | Every occurrence request loads full contact entities with a birthday**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/contacts/birthdays/BirthdayCalendar.kt:15,41`
    - Category: Quality
    - Problem: `findByBirthdayIsNotNullAndDeletedAtIsNull()` loads whole `Contact` entities, including the JSON columns for emails, phones and addresses and `notes_md`. It does this for every contact with a birthday on every `GET /calendar/events`, and on every review or contact-aggregate call that lists occurrences, whatever the range. Only `id`, `displayName` and `birthday` are used.
    - Why it matters: This is unnecessary I/O and JSON deserialization on the calendar's hottest read path, and the cost grows with the size of the address book.
    - Suggested fix: Use an interface or DTO projection (`id`, `displayName`, `birthday`) in `BirthdayContactRepository`.

## Notes (verified, no finding)

- Feb 29 and range logic: `LocalDate.withYear` maps Feb 29 to Feb 28 in non-leap years. The provider's `[fromDate, toDate)` filter matches `OccurrenceRange` (with exclusive `toDate`) and the expander's all-day overlap rule. The `years` range covers ranges that cross a year boundary, and the 400-day `MAX_RANGE` keeps it at most 2-3 years.
- Listener: the `@TransactionalEventListener` + `REQUIRES_NEW` combination is valid. The CALENDAR change event that `ensureVirtualCalendar` publishes is ignored by the type check, so it causes no recursion. `fallbackExecution` covers publication without a transaction.
- SPI direction: calendar defines `VirtualCalendarProvider` in `calendar.api`, and contacts implements it. Calendar does not depend on contacts. This is sound and consistent with "modules talk only through services".
- Backup/restore: the calendar is an ordinary `calendar` row whose `external_id` and `read_only` are backed up and restored. The restore calls `ContactService.changed`, which re-triggers the idempotent ensure. Sync engines skip LOCAL sources (`CalendarProviderRegistry`, `CalendarSyncService:103`).
- Other `occurrences` consumers: `ContactAggregateService` filters by attendees, so birthdays do not appear there. `ReviewDataService` and the web Today and review views now show birthdays, with unique `eventId@occurrence` keys.
- OpenAPI: no controller or DTO change, so regeneration is not needed for the schema. Only the description issue in CR-1 applies.
- Tests: 28 tests in `contacts.birthdays.*` (MockMvc + Postgres Testcontainers, plus unit tests) cover AC1, including Feb 29 in leap and non-leap years, and AC2's command. The `tasks.py verify` evidence was not yet recorded at review time. That is the parent's job.

## Review conclusion

Material issues found; fix before completing the task. CR-1 needs at least the contract description fixed (or a recorded decision) plus a follow-up client task. CR-2 and CR-3 are optional cleanups.
