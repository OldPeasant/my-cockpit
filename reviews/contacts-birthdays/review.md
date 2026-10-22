# Code Review

Date: 2026-10-22

Task: contacts-birthdays - Birthdays calendar

Scope: The final task code, `git diff a300dc2f..d4a49482 -- server api web`, re-review after the fixes. While this review ran, the branch was rebased onto 9a47e3f5, giving HEAD 14311f86. `git diff d4a49482 14311f86 -- server api web` is empty, so the code reviewed here is the code at the new HEAD. `tasks/` and `reviews/` were read for context only.

Requirements:
- tasks/contacts-birthdays.md: both acceptance criteria and the four recorded assumptions
- SPEC.md §8.3 ("Birthdays shown as a virtual read-only calendar")
- AGENTS.md "Project notes", docs/ARCHITECTURE.md conventions
- The previous review report (CR-1..CR-3) and reviews/contacts-birthdays/fixes.md
- Parent instructions: confirm the earlier findings are fixed, and check the pessimistic lock (deadlock risk, the AFTER_COMMIT/REQUIRES_NEW and ApplicationReadyEvent transaction contexts) and the JPQL constructor projection

Git range:
- Branch: main
- HEAD: 14311f86906a600c99518bf5f3212ce38d0264a1 (code identical to d4a49482)
- Base/start: a300dc2f8dd709f79dbf625afd456659e05ced37 (the merge base after the rebase is 9a47e3f5)
- Commits reviewed: 5 (implementation, tests, review report, fixes, follow-up task file)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | The creation lock does not cover the case where no LOCAL source exists yet**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarService.kt:106-108` (with `localSource()` at line 162)
    - Category: Correctness
    - Problem: `ensureVirtualCalendar` locks the row returned by `localSource()`. If no LOCAL source exists (the user has never created a local calendar), two concurrent first calls each run `localSource()`, and each inserts its own `Cockpit` LOCAL source; nothing in `calendar_source` makes LOCAL unique. Each call then locks a different row and creates its own `virtual:birthdays` calendar. The unique index is on `(source_id, external_id)`, so it does not stop this. `virtualKeys` maps both calendars to the `birthdays` provider, so every birthday would then appear twice. The KDoc says concurrent first calls "wait for each other", but that only holds once the LOCAL source exists. The regression test sets up exactly that state first (`api.createCalendar() // the LOCAL source exists`). Two triggers are possible: a contact commit while the `ApplicationReadyEvent` check runs, or two contact commits at the same moment, on an install with no local calendar. In a single-user app this is unlikely. `create()` has the same source race, but that code existed before this task.
    - Why it matters: The result is rare but visible: two Birthdays calendars and every birthday listed twice. The comment also promises more than the code delivers.
    - Suggested fix: Make the first creation of the LOCAL source safe under concurrency, for example with a partial unique index `on calendar_source (type) where type = 'LOCAL'` in a new migration plus insert-or-reread. Or document the limitation in the KDoc and record it as an assumption.

## Earlier findings (previous revision)

- **CR-1 (P2, contract for virtual occurrences): resolved.** The `OccurrenceResponse` schema description (`CalendarDtos.kt:181-187`) and the `listEventOccurrences` description (`CalendarEventController.kt:54-58`) now document that virtual occurrences have no stored event, that `eventId`/`seriesId` are the source item, that `GET /calendar/events/{eventId}` returns 404, and that they cannot be changed. `api/openapi.yaml` and `web/src/app/api/generated/models/occurrence-response.ts` match the annotations and differ only in description text. The client gap has a follow-up task: `tasks/web-and-birthday-occurrences.md` (deps `contacts-birthdays`, touches the web and Android calendar features, with concrete criteria). This matches the suggested fix.
- **CR-2 (P3, creation race): resolved for the common case.** Once the LOCAL source exists, the double-checked creation under `findForUpdate` stops the duplicate insert and the ERROR log. The new test reproduces the race against real Postgres and passes. The case without a LOCAL source is the new CR-1 above.
- **CR-3 (P3, full entity loading): resolved.** `BirthdayContactRepository.findBirthdays()` is a JPQL constructor projection that reads only id, display name and birthday.

## Notes (verified, no finding)

- **Lock and transaction context.** `CalendarService` is `@Transactional` at class level, so `ensureVirtualCalendar` always runs in a transaction and `PESSIMISTIC_WRITE` never meets "no transaction in progress". It joins the caller's transaction in both paths:
    - `onContactChanged` (`@TransactionalEventListener` AFTER_COMMIT, `REQUIRES_NEW`) runs in a new transaction on its own connection. The contact transaction has already committed, so it holds no row locks that could form a cycle.
    - With `fallbackExecution`, an event published outside a transaction behaves the same way.
    - `onStartup` (`@EventListener(ApplicationReadyEvent)`, `@Transactional`, through the bean proxy) also opens its own transaction.
- **Deadlock risk.** Before taking the lock, the ensure transaction has only read, so it holds no locks. While holding it, it takes only its own insert's locks: the FK key-share lock on the same source row, and a unique-index entry that no other writer uses. The only other locker of `calendar_source` rows is `findForUpdate` itself. A transaction inserting a calendar into the LOCAL source can make ensure wait (FK key-share against FOR UPDATE), but ensure holds nothing that transaction needs, so no cycle can form. The fast path, where the calendar already exists, takes no lock. The pattern matches `UserAccountRepository`/`ReminderRepository`.
- **JPQL projection.** The class name is fully qualified. `Contact.displayName` is a non-null `String` and `birthday` is filtered `is not null`, so the non-null Kotlin constructor parameters are safe. `deletedAt is null` excludes Trash. The query runs against Postgres in the web and edge-case tests.
- **Ordering and merging.** Stored and virtual occurrences are merged through the extracted `OccurrenceExpander.ordered`, which uses the same comparator as before. The virtual filter `[fromDate, toDate)` matches the all-day overlap rule. Feb 29 falls on Feb 28 through `LocalDate.withYear`, which is tested for leap and non-leap years.
- **Module boundaries.** The `VirtualCalendarProvider` SPI lives in `calendar.api` and is implemented in `contacts.birthdays`. The calendar module does not depend on contacts. `ObjectProvider` makes the provider optional.
- **Tests.** I ran `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.birthdays.*'`: 29 tests, 0 failures, 0 errors (13 unit, 8 web, 8 edge-case; Postgres via Testcontainers). This covers AC1 (yearly all-day occurrences in `GET /calendar/events`, Feb 29) and runs exactly the command AC2 names. The parent still has to record the evidence with `tasks.py verify`.
- **No secrets, personal data or debug output** in the diff. The test data is fictitious.

## Review conclusion

No material issues found. The earlier findings are resolved. CR-1 is an optional low-priority hardening, or something to document as a limitation.
