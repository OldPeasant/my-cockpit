# Fix Report

Date: 2026-10-22

Task: contacts-birthdays - Birthdays calendar

Scope: Findings CR-1, CR-2 and CR-3 from `reviews/contacts-birthdays/review.md` (task commits 62465bb8, 42a2e2b1, b475864c on base eb0cbfa7). This round started from uncommitted changes left by an earlier interrupted fix session. I reviewed them: the CR-1 and CR-3 parts were kept, and the CR-2 part was replaced (see CR-2).

## Fixed findings

### CR-1 — Birthday occurrences expose an `eventId` that the API contract treats as a fetchable event

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarDtos.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarEventController.kt`
- `api/openapi.yaml` (regenerated)
- `web/src/app/api/generated/models/occurrence-response.ts` (regenerated)

Problem:
The `OccurrenceResponse` contract said `eventId` is always a stored event. Birthday occurrences carry the contact id there, and `GET /calendar/events/{eventId}` answers 404 for it.

Fix:
The `OccurrenceResponse` schema description now documents the virtual case: occurrences of a virtual read-only calendar such as Birthdays have no stored event, `eventId`/`seriesId` identify the item they are computed from (the contact), `GET /calendar/events/{eventId}` answers 404, and they cannot be changed. The `listEventOccurrences` description points to this. I regenerated `api/openapi.yaml` with `UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'` and the web client with `npm run api:gen`. Both outputs were byte-identical to the working-tree edits from the earlier session, so nothing in them was edited by hand. The web and Android clients do not handle these occurrences yet; that follow-up task is the parent worker's job.

Validation:
- `cd server && UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'` — Pass (no further diff)
- `cd web && npm run api:gen` — Pass (only `occurrence-response.ts` changed, same as the working tree)
- `cd server && ./gradlew check` (includes `OpenApiContractTest`) — Pass for this test
- `cd web && npm run check` — Pass (150 files, 3045 tests)

### CR-2 — Two first-time creations of the virtual calendar can race on `calendar_external_id`

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarService.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/model/CalendarRepositories.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/birthdays/BirthdayCalendarEdgeCaseWebTests.kt`

Problem:
`ensureVirtualCalendar` checked for the row and then inserted it. Two concurrent first calls both inserted, and the loser failed on the unique index, which produced an ERROR log.

Fix:
The earlier session's attempt is not kept. It wrapped the initializer in a `REQUIRES_NEW` `TransactionTemplate` and caught `DataIntegrityViolationException`. That still lets the duplicate insert reach Postgres, so Hibernate's `SqlExceptionHelper` still logs the constraint violation at ERROR. It also only covered callers that go through the initializer. I reverted the initializer to its committed `@Transactional` form. `ensureVirtualCalendar` now uses a double-checked creation instead: when the calendar is missing, it locks the LOCAL source row (`CalendarSourceRepository.findForUpdate`, `PESSIMISTIC_WRITE`, the same pattern as `UserAccountRepository.findFirstForUpdate`) and checks again before inserting. A concurrent second call waits for the first transaction to commit, then finds the calendar (READ COMMITTED). No duplicate insert happens and nothing is logged. The existing-calendar fast path takes no lock.

Regression test: `a second first-time creation waits for the first one and returns its calendar` (Postgres Testcontainers). The first transaction inserts the calendar and stays open. A second `ensureVirtualCalendar` call must block. After the first commits, the second must return the same id, and exactly one `virtual:birthdays` row may exist. Negative check: with the lock line commented out, this test failed with `DataIntegrityViolationException` (duplicate key). With the fix it passes.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.birthdays.BirthdayCalendarEdgeCaseWebTests'` with the fix disabled — Fail as expected (duplicate key)
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.birthdays.*'` — Pass

### CR-3 — Every occurrence request loads full contact entities with a birthday

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/contacts/birthdays/BirthdayCalendar.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/birthdays/BirthdayCalendarUnitTests.kt`

Problem:
`findByBirthdayIsNotNullAndDeletedAtIsNull()` loaded whole `Contact` entities, including the JSON columns and notes, but only `id`, `displayName` and `birthday` were used.

Fix:
Kept from the earlier session. `BirthdayContactRepository.findBirthdays()` is a JPQL constructor projection (`select new ch.sonensei.cockpit.contacts.birthdays.BirthdayContact(c.id, c.displayName, c.birthday) ... where c.birthday is not null and c.deletedAt is null`) into a small `BirthdayContact` data class. The provider and the unit tests use it. I checked it against real Postgres: the MockMvc/Testcontainers tests in `BirthdayCalendarWebTests` and `BirthdayCalendarEdgeCaseWebTests` go through it.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.birthdays.*'` — Pass (8 unit + 13 web + 8 edge-case = 29 tests, 0 failures)

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat` — Pass
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.birthdays.*'` — Pass (29 tests, Postgres via Testcontainers)

Full test suite:
- `cd server && ./gradlew check` — 4159 tests, 1 failure: `IcsFeedClientTests > a feed of exactly the maximum size is read, a larger Content-Length is refused before reading()`. That test is unrelated: this change does not touch the ICS feed code. It passed on its own when rerun (`./gradlew test --tests '*IcsFeedClientTests'` — Pass), so it is a timing flake under full-suite load.
- `cd web && npm run check` — Pass (150 test files, 3045 tests; lint and build OK, existing bundle-budget warning only)

Lint/type-check/build:
- Lint and compile as part of `./gradlew check` — Pass (no compiler warnings in the changed files)

`.taskcheck`:
- Not run: the server and web parts were run separately above, and Android is unaffected. The parent worker runs `.taskcheck` before `done`.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed. The only failure in the full server suite is one unrelated flaky test, which passed when rerun on its own.
