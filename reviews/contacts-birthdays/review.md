# Code Review

Date: 2026-10-22

Task: contacts-birthdays - Birthdays calendar

Scope: The whole task, `git diff f703b4f39cfe38684b96ca4c0534bfb81f0942ab..e36eaf61 -- server api web`, as a re-review of the final code. I looked most closely at what changed since the previous review (`git diff a91643ad..e36eaf61 -- server api web`, commits ab4a727a and e36eaf61): the advisory lock in `CalendarService.ensureVirtualCalendar`, the removal of `CalendarSourceRepository.findForUpdate`, and the stronger race-test assertions. I read `tasks/` and `reviews/` for context only. The working tree was clean when the review started.

Requirements:
- tasks/contacts-birthdays.md: both acceptance criteria and the recorded assumptions
- SPEC.md §8.3 ("Birthdays shown as a virtual read-only calendar")
- AGENTS.md "Project notes", docs/ARCHITECTURE.md conventions
- The previous review report, reviews/contacts-birthdays/fixes.md (TW-1 round)
- Parent instructions: re-review the final revision after the advisory-lock fix

Git range:
- Branch: main
- HEAD: e36eaf61
- Base/start: f703b4f39cfe38684b96ca4c0534bfb81f0942ab
- Commits reviewed: 8 (e4d887d7..e36eaf61)

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | `create()` can still add a second LOCAL source next to the birthdays calendar, and that leads to a duplicate Birthdays calendar later**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarService.kt:67` (with `ensureVirtualCalendar` at lines 109-114 and `existingLocalSource()` at line 173)
    - Category: Correctness
    - Problem: Only `ensureVirtualCalendar` takes `VIRTUAL_CALENDAR_LOCK`. `create()` (POST of a local calendar) still calls `localSource()` without the lock. Suppose no LOCAL source exists yet, and a user's first `create()` overlaps with the first birthday-calendar creation. Each transaction cannot see the other's uncommitted source, so both insert a `Cockpit` LOCAL source. If the `create()` source S2 has the lower (UUIDv7, time-ordered) id, every later `ensureVirtualCalendar` takes S2 as `existingLocalSource()` (first LOCAL by id). It finds no `virtual:birthdays` calendar in S2, so it creates a second one there. The first one stays in S1, and `virtualKeys` maps both to the `birthdays` provider, so every birthday shows up twice. Two concurrent `create()` calls could already produce duplicate sources before this task. The duplicate Birthdays calendar is a new consequence, because the lookup now depends on which LOCAL source sorts first. fixes.md mentions this path but leaves it unresolved. In a single-user app the window is very small.
    - Why it matters: The outcome is rare but visible (every birthday listed twice), and the KDoc on `ensureVirtualCalendar` says a second LOCAL source cannot happen.
    - Suggested fix: Take the same advisory lock in `create()` before `localSource()`, for example by moving the lock into a `lockedLocalSource()` helper that both methods use. Alternatively, make the virtual-calendar lookup independent of the source (find by `externalId` across all LOCAL sources). If the parent leaves this open, record it as an assumption or a follow-up task.

- [ ] **CR-2 | The recorded assumption still describes the removed row lock**
    - File: `tasks/contacts-birthdays.md:23`
    - Category: Quality
    - Problem: The assumption says concurrent first creation "is serialized by a row lock on the LOCAL calendar source (CalendarSourceRepository.findForUpdate)". ab4a727a removed `findForUpdate` and replaced it with `pg_advisory_xact_lock`. The handoff note on line 25 says an updated assumption still has to be recorded, and none exists yet.
    - Why it matters: Humans review the assumptions with `tasks.py assumptions`, and this one describes a mechanism that no longer exists.
    - Suggested fix: The parent should record a new assumption (`tasks.py assume`) saying the transaction-scoped advisory lock (key "CAL VIRT") replaces the row lock, and that `create()` is not covered (or covered, after CR-1).

## Earlier findings (previous revisions)

- **First review CR-1 (contract for virtual occurrences), CR-2 (creation race with an existing source), CR-3 (full entity loading): still resolved.** The code since the last review did not touch `CalendarDtos.kt`, `CalendarEventController.kt`, `api/openapi.yaml`, the generated web model or `BirthdayCalendar.kt`.
- **Re-review CR-1 / TW-1 (concurrent first creation without a LOCAL source): resolved for `ensureVirtualCalendar` callers.** The lock is taken before `localSource()`, so the second caller blocks until the first commits. In Postgres READ COMMITTED, each statement after the wait takes a new snapshot, so the re-read in `localSource()` and `findBySourceIdAndExternalId` sees the committed source and calendar. Both reads are JPQL queries, not first-level-cache lookups, so a fast-path read that found nothing does not hide the new rows. The test `two first-time creations before any local calendar exists ...` now asserts that the second call blocks (`shouldThrow<TimeoutException>`), and that exactly one source and one calendar exist afterwards. The remaining `create()` path is CR-1 above.

## Notes (verified, no finding)

- **Lock correctness and deadlock risk.** `pg_advisory_xact_lock` is released at commit or rollback. The class-level `@Transactional` means it always runs inside a transaction: the caller's, or the `REQUIRES_NEW` / `ApplicationReadyEvent` transactions of `BirthdayCalendarInitializer`. Before the lock, the transaction has only read, so it holds no locks that could form a cycle. The fast path, where the calendar exists, takes no lock. `queryForList` on a `void` function matches the precedent in `FilesService.kt:126,256`. The key `0x43414C20_56495254` differs from the two `FilesService` keys. These are the only `pg_advisory` uses in `server/src/main`.
- **Removed `findForUpdate`.** A grep shows no remaining callers, and the unused `LockModeType`/`Lock` imports were removed too.
- **Module boundaries, ordering, Feb 29, projection.** These are unchanged since the previous review: the SPI lives in `calendar.api`, `contacts.birthdays` implements it, and calendar does not depend on contacts. Merging goes through `OccurrenceExpander.ordered`. Feb 29 falls on Feb 28 in non-leap years.
- **AC1** is implemented and covered by `BirthdayCalendarWebTests` (yearly all-day occurrences in `GET /calendar/events`, Feb 29 in leap and non-leap years).
- **AC2 test run.** My own run of `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.birthdays.*'` (started about 05:28) failed after 6 minutes with a Gradle-level `java.io.EOFException` in the `:test` task, not with a test failure. That pattern fits a test worker that was killed or a concurrent Gradle run in the same clone, so it neither proves nor disproves anything. The newest result files in `server/build/test-results/test/` are from a run at 05:26 by another process: 31 tests (8 unit, 13 web, 10 edge-case), 0 failures, 0 errors. I cannot tie those files to a specific revision. The parent must record AC2 evidence from its own clean run at the final HEAD.
- **No secrets, personal data or debug output** in the diff. The test data is fictitious.

## Review conclusion

No material issues found. The TW-1 fix is correct for the path it targets. CR-1 is a low-priority remaining race on `create()`: fix it or record it. CR-2 is a stale assumption that the parent should update.
