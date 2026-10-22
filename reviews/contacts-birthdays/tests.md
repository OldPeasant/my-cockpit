# Test Report

Date: 2026-10-22

Task: contacts-birthdays - Birthdays calendar

Scope: task commits `639ea5327439d5128bd9ea80a4144a8fcbc97f36..2def8423d61cbb5ee25b8c28d8b16cc1b32de668` (one commit, "contacts-birthdays: virtual read-only birthdays calendar in GET /calendar/events"), branch `main`, plus the two new untracked test files listed below. No other uncommitted changes.

Requirements:
- `tasks/contacts-birthdays.md` acceptance criteria and the four recorded ASSUMPTIONs (lazy calendar creation, Feb 29 -> Feb 28, occurrences from the year after birth, age omitted above 130, TRANSPARENT, eventId = seriesId = contact id)
- SPEC.md §8.3 (virtual read-only birthdays calendar)
- docs/ARCHITECTURE.md "Coverage"
- Parent instructions (focus on provider date logic and the virtual-calendar SPI edges)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/birthdays/BirthdayCalendarUnitTests.kt` (new, plain JUnit + MockK, no Spring)
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/birthdays/BirthdayCalendarEdgeCaseWebTests.kt` (new, Spring Boot + MockMvc + Postgres Testcontainers, same context configuration as the existing `BirthdayCalendarWebTests`)

The existing `BirthdayCalendarWebTests.kt` (8 tests) is unchanged.

## Tests added or updated

Count: 20 (13 unit, 7 web/integration)

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: birthdays are yearly all-day occurrences in `GET /calendar/events` from a virtual calendar | `BirthdayCalendarWebTests` (existing, 8); `BirthdayCalendarUnitTests`: `an occurrence is a one-day series member of the contact titled with name and age`, `a range over several years yields one occurrence per year with the age reached`, `the end of the range is exclusive and its start inclusive`, `no contacts with a birthday yield nothing`; `BirthdayCalendarEdgeCaseWebTests`: `a range inside the birthday or ending at its start is judged in the user's zone`, `a range over the new year lists the birthdays of both years in date order`, `a hidden birthdays calendar still answers when requested explicitly`, `a range without any birthday keeps the stored events as before` | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.birthdays.*' ktlintCheck` | Pass |
| AC1: Feb 29 handled (Feb 28 in non-leap years, Feb 29 in leap years, incl. 2100/2400 century rules) | `February 29 falls on February 28 in non-leap years and stays on February 29 in leap years`, `a February 29 birthday is not on March 1 and not twice in a leap year`, existing `a February 29 birthday falls on February 28 in other years` | same | Pass |
| Empty / inverted ranges | `empty and inverted ranges yield nothing without reading contacts` | same | Pass |
| Year boundary ranges | `a range over the year boundary yields the birthdays of both years` (unit), `a range over the new year lists the birthdays of both years in date order` (web) | same | Pass |
| ASSUMPTION: no occurrence in the birth year; age omitted above 130 | `there is no occurrence in the year of birth or before`, `the age is shown up to 130 years and omitted above` (130 shown, 131 omitted) | same | Pass |
| ASSUMPTION: calendar created lazily at startup / after a contact change with a birthday; not for other item types, DELETED ops or contacts without birthday | `the initializer creates the calendar at startup only when a contact has a birthday`, `the initializer reacts only to created or updated contacts with a birthday` | same | Pass |
| SPI: only LOCAL calendars with `virtual:<key>` are virtual; a non-LOCAL calendar with external id `virtual:birthdays` yields nothing | `a calendar of another source whose external id looks virtual has no computed occurrences` | same | Pass |
| SPI: unknown virtual key yields nothing and does not break stored events | `a local calendar with an unknown virtual key has no occurrences and does not break the others` | same | Pass |
| SPI: `ensureVirtualCalendar` is idempotent and keeps the user's name/color/visibility | `ensuring the calendar again keeps the user's name, color and visibility` | same | Pass |
| AC2: `./gradlew test --tests 'ch.sonensei.cockpit.contacts.birthdays.*'` passes (Postgres via Testcontainers) | all 28 tests in the package | same | Pass (28 tests, 0 failures) |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.birthdays.*' ktlintCheck`

Result:

Pass (28 tests: 8 existing + 20 new; ktlintCheck clean). A first run failed 8 new unit tests because the test passed a random UUIDv4 as entity id (`Entity ids must be UUIDv7`); this was a test error and was fixed in the test, not in production code.

## Full test execution

Command:

`cd server && ./gradlew test jacocoTestReport`

Result:

Pass (exit 0, 4158 tests, 0 skipped, 0 failures, 4m55s). Web and Android suites ran as part of `.taskcheck` below.

## .taskcheck

Result:

Pass (`sh scripts/check.sh` -> "== all checks passed"; server, web and android lint + unit tests).

## Bugs found

None.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured separately by intersecting JaCoCo XML per-line counters (`ci > 0`) with the added/modified lines of `git diff -U0 639ea532..2def8423 -- server/src/main`: 68/68 executable changed lines covered; branches on changed lines 39/44 covered.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `contacts-birthdays`; base `639ea5327439d5128bd9ea80a4144a8fcbc97f36` (supplied by parent); HEAD `2def8423d61cbb5ee25b8c28d8b16cc1b32de668`; the single task commit `2def8423` (`git log 639ea532..HEAD`), matched against the parent's file list.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `2def8423` plus untracked tests: `BirthdayCalendarUnitTests.kt` sha256 `5ed19b6084b9cc5563f761444b6d78bd3d0edd92aaced75666004f9269d1c880`, `BirthdayCalendarEdgeCaseWebTests.kt` sha256 `bdd0037b0d2ce630d5c1f699a81f4d495b6b10ac92fe222eb9825a5132cbf7c6`; existing `BirthdayCalendarWebTests.kt` sha256 `71d7ae509991ac6f546717c4f11d9e2a2a7064081216c25aab28bec842e282bd`.
- Exact included files; renamed/deleted/excluded files and reasons: the six files below. No renames or deletions. Excluded: `BirthdayCalendarWebTests.kt` (test file). No production file missing from the report.
- Provider/version, command, working directory, and test selection: JaCoCo 0.8.15 (Gradle `jacoco` plugin); `./gradlew test jacocoTestReport`; working directory `server/`; full server test suite.
- Run start/completion time and exit status: 2026-10-22T04:38:53+02:00 to 2026-10-22T04:43:48+02:00, exit 0.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 `df3700f4835f97fafc0f9484d104b18816d68ac53620dc11889a07d989ed433c`.
- Evidence outputs are fresh and inputs stayed unchanged: yes; the test task re-executed because the new tests were compiled in, HEAD and the test-file hashes were identical before and after the run.
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage). JaCoCo has no statement counter; the Statements column shows INSTRUCTION counters.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/contacts/birthdays/BirthdayCalendar.kt` | 122/122 (100%) | 10/10 (100%) | 4/4 (100%) | 15/15 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/contacts/birthdays/BirthdayCalendarInitializer.kt` | 43/43 (100%) | 8/8 (100%) | 4/4 (100%) | 11/11 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/VirtualCalendars.kt` | 64/68 (94.1%) | N/A (0 branches) | 8/8 (100%) | 8/8 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarService.kt` | 647/678 (95.4%) | 57/60 (95.0%) | 24/25 (96.0%) | 81/82 (98.8%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarEventService.kt` | 3092/3133 (98.7%) | 347/362 (95.9%) | 53/55 (96.4%) | 375/378 (99.2%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/recurrence/OccurrenceExpander.kt` | 891/931 (95.7%) | 81/96 (84.4%) | 31/33 (93.9%) | 105/106 (99.1%) |
| Aggregate (summed counters, all 6 files, none unmeasured or excluded) | 4859/4975 (97.7%) | 503/536 (93.8%) | 124/129 (96.1%) | 595/600 (99.2%) |

The uncovered lines/branches of `CalendarService.kt`, `CalendarEventService.kt` and `OccurrenceExpander.kt` outside the task's changed lines are pre-existing code (e.g. `CalendarService.kt:26-31`, `CalendarEventService.kt:145, 413, 420`, `OccurrenceExpander.kt:124-248`) and are not listed individually.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CalendarEventService.kt:107` (`providers.isEmpty()` true branch) | No virtual provider registered | Not reachable in the application context (the birthdays provider is always a bean); defensive, non-material |
| `CalendarEventService.kt:112` (2 branches of the overlap filter) | A provider returning occurrences outside the requested days | The birthdays provider already returns only in-range occurrences (tested in unit tests), so the filter never rejects; a test would need a fake provider bean and a separate Spring context; non-material |
| `CalendarEventService.kt:121` (`occurrenceDate == null`) | Virtual occurrence without a series date | The birthdays provider always sets it; no other provider exists; non-material |
| `CalendarService.kt:122` (1 branch: LOCAL calendar with a non-null external id not starting with `virtual:`) | Other LOCAL calendars with external ids | LOCAL calendars normally have a null external id (schema comment); the non-LOCAL and unknown-key cases are tested; non-material |
| `VirtualCalendars.kt:15-16` (default values of `seriesId` / `occurrenceDate`) | Data-class defaults | The only provider always passes both; non-material |

## Unable to test

- Concurrent first creation of the birthdays calendar (two contacts with birthdays committed at the same moment): both `REQUIRES_NEW` listeners could try to insert `virtual:birthdays` and one would hit the `calendar_external_id` unique index. Not tested (needs a deterministic concurrency harness); not confirmed as a defect. Low risk, follow-up optional.
- Contact changes arriving through provider sync (CardDAV/Google/Microsoft) were not exercised separately; they go through the same `ChangeEvent` path that the initializer unit test covers.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production defects found.
