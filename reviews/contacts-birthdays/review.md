# Code Review

Date: 2026-10-22

Task: contacts-birthdays - Birthdays calendar

Scope: The whole task, `git diff ecc4a85fdd1237af423d31969dd58df7fda8c883..218c8e72a4e4312ede0082ec13f2d3142fcfe9ef`, as a re-review of the final code. Production files reviewed: `calendar/api/CalendarService.kt`, `CalendarEventService.kt`, `VirtualCalendars.kt`, `CalendarDtos.kt`, `CalendarEventController.kt`, `calendar/recurrence/OccurrenceExpander.kt`, `contacts/birthdays/BirthdayCalendar.kt`, `BirthdayCalendarInitializer.kt`, `api/openapi.yaml` and the generated web model. Tests reviewed: the three suites in `server/src/test/kotlin/ch/sonensei/cockpit/contacts/birthdays/`. Since the previous review (e36eaf61), the only code change is two new race tests in `BirthdayCalendarEdgeCaseWebTests.kt` (rollback, and no waiting when the calendar exists). `git diff e36eaf61..218c8e72 -- server api web` touches only that file. I read `tasks/` and `reviews/` for context only. The working tree was clean.

Requirements:
- tasks/contacts-birthdays.md: both acceptance criteria and every recorded assumption, including the 05:35 assumption that replaces the row lock with the advisory lock and accepts the `create()` race
- SPEC.md §8.3 ("Birthdays shown as a virtual read-only calendar")
- AGENTS.md "Project notes", docs/ARCHITECTURE.md conventions
- The previous review (e36eaf61) and the parent's instructions for this re-review

Git range:
- Branch: main
- HEAD: 218c8e72a4e4312ede0082ec13f2d3142fcfe9ef
- Base/start: ecc4a85fdd1237af423d31969dd58df7fda8c883
- Commits reviewed: 10 (0e65ff39..218c8e72, including the merge of origin/wip/contacts-birthdays)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Earlier findings (previous revisions)

- **Previous CR-1 (`create()` takes the LOCAL source without `VIRTUAL_CALENDAR_LOCK`): accepted.** The code is unchanged: `CalendarService.create()` still calls `localSource()` without the lock. The 05:35 assumption in the task file records this as a known, accepted race for a single-user app, so I do not report it again.
- **Previous CR-2 (stale row-lock assumption): resolved.** The 05:35 assumption explicitly replaces the `findForUpdate` note and describes `pg_advisory_xact_lock(VIRTUAL_CALENDAR_LOCK)`.
- **First-round findings (contract documentation for virtual occurrences, creation race, full-entity loading): still resolved.** `OccurrenceResponse` and the `listEventOccurrences` description document the virtual case, and `openapi.yaml` and the generated web model match the Kotlin annotations. `BirthdayContactRepository.findBirthdays` uses a constructor projection that loads only id, name and birthday. The race is handled by the advisory lock. Client handling of the 404 on these occurrences is the follow-up task `web-and-birthday-occurrences`.

## Notes (verified, no finding)

- **AC1: implemented.** `CalendarEventService.occurrences` merges the provider's occurrences for virtual calendars among the requested or visible calendar ids. It filters them to the range's `[fromDate, toDate)` in the user's zone, and orders them with stored events through `OccurrenceExpander.ordered`. Occurrences are all-day (exclusive end date), TRANSPARENT, and use the contact id as `eventId`/`seriesId` with the date as `occurrence`. For Feb 29, `LocalDate.withYear` gives Feb 28 in non-leap years. Covered in `BirthdayCalendarUnitTests` (leap and non-leap years, never March 1, never twice) and in `BirthdayCalendarWebTests` "a February 29 birthday falls on February 28 in other years" through `GET /calendar/events`.
- **Read-only.** The calendar is created with `readOnly = true`, so `requireWritable` rejects new events in it. The web test "birthday occurrences cannot be created in, read or changed" covers this.
- **New race tests (since e36eaf61).**
  - "a first-time creation that rolls back ..." checks that the waiting caller blocks, then creates the calendar itself after the first one rolls back, leaving one LOCAL source. This is correct, because the transaction-scoped lock is released on rollback.
  - "an existing birthdays calendar is returned without waiting ..." holds the lock through another virtual key's creation and checks that the fast path returns the existing calendar without waiting.
  - Both release their latches in `finally` or with timeouts, and `@AfterEach` resets the calendar tables.
  - The negative timing check (`shouldThrow<TimeoutException>` after 500 ms) can only pass falsely, not fail intermittently.
- **Lock semantics.** The class-level `@Transactional` on `CalendarService` means the lock always runs inside a transaction: the caller's, the `REQUIRES_NEW` transaction of `onContactChanged`, or the `onStartup` transaction. Under READ COMMITTED, the re-reads after the wait (`localSource()`, `findBySourceIdAndExternalId`) are JPQL queries with a new snapshot, so they see the committed rows.
- **Listener failure mode.** `onContactChanged` is an AFTER_COMMIT `@TransactionalEventListener`. Spring runs it after the contact transaction completes, so a failure there does not roll back the contact save.
- **Other callers of `CalendarEventService.occurrences`.**
  - `ContactAggregateService.related` filters by attendees, and birthday occurrences have none, so it is unaffected.
  - `ReviewDataService` now also lists birthdays in the weekly review's past and upcoming events. That fits a calendar shown by default.
  - The web review step tracks rows by `eventId + occurrence`, which stays unique for birthdays.
- **Architecture.** The SPI (`VirtualCalendarProvider`, `VirtualOccurrence`) lives in `calendar.api`. `contacts.birthdays` depends on `calendar.api` only, through the `CalendarService` service and the SPI, and the calendar module does not depend on contacts. The changes to calendar files outside the task's `touches` are recorded in the assumptions. There is no schema change, so no migration is needed. The calendar is an ordinary stored row, so backup covers it.
- **Security and data.** No secrets, personal data or debug output. Test data is fictitious. Nothing is logged at INFO or above. The projection does not load notes, emails or addresses.
- **AC2 evidence.** I cannot run Gradle here. The parent reports `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.birthdays.*'` on HEAD 218c8e72 -> 33 tests, 0 failures (Testcontainers Postgres). That count matches the test files: 13 unit, 8 web and 12 edge-case tests. The parent must record this with `tasks.py verify`.

## Review conclusion

No material issues found.
