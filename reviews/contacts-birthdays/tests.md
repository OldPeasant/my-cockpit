# Test Report

Date: 2026-10-22

Task: contacts-birthdays - Birthdays calendar

Scope: the final code of the task, `git diff ecc4a85fdd1237af423d31969dd58df7fda8c883..218c8e72a4e4312ede0082ec13f2d3142fcfe9ef` (base = the claim commit, HEAD = the merge of `origin/wip/contacts-birthdays`, on `main`). This is the third test-writer run. It reviews the final tests, including the two race tests from commit 9cdc69c1 (now `4f75b688`) that came after the second run: rollback, and no wait for an existing calendar. The working tree was clean when I started. I changed no test files in this run.

Requirements:
- `tasks/contacts-birthdays.md`: acceptance criteria and every ASSUMPTION, including the 05:35 note that `pg_advisory_xact_lock(VIRTUAL_CALENDAR_LOCK)` replaces the `findForUpdate` row lock
- SPEC.md §8.3 (virtual read-only birthdays calendar)
- `reviews/contacts-birthdays/review.md` and `fixes.md` (CR-1..3, TW-1)
- docs/ARCHITECTURE.md "Coverage" (informs the test-writer; not a gate, no threshold)
- Parent instructions: re-run on the final code; the parent ran Gradle, because this helper cannot

## Test files changed

None in this run. The final test files at HEAD are:

- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/birthdays/BirthdayCalendarUnitTests.kt` (blob `b3556bb3`)
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/birthdays/BirthdayCalendarWebTests.kt` (blob `6cd36e7e`)
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/birthdays/BirthdayCalendarEdgeCaseWebTests.kt` (blob `fb8e2692`)

## Tests added or updated

Count: 0 in this run. The package has 33 tests: 13 unit, 8 web and 12 edge-case.

I checked each acceptance criterion, assumption and changed behavior against the 33 tests and found no meaningful gap. For the same reason, I did not add tests for the few uncovered task branches (see Coverage). The two tests added in 9cdc69c1 are correct and deterministic:
- `a first-time creation that rolls back lets the waiting one create the calendar and its local source`: the second call waits on the advisory lock while the first is open. After the first rolls back, the second creates exactly one calendar in one LOCAL source. The ordering is controlled with latches. The only timing assumption is a 500 ms "still waiting" check, which is the same pattern as the other race tests.
- `an existing birthdays calendar is returned without waiting for another virtual calendar's creation`: covers the lock-free fast path in `ensureVirtualCalendar`. While another virtual calendar's creation holds the advisory lock, an existing calendar is still returned at once (within 5 s). `finally` releases the other transaction.

## Requirement coverage

All rows were run by the parent with: `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.birthdays.*' jacocoTestReport` (Testcontainers Postgres), on HEAD 218c8e72, with a clean tree.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: birthdays appear as yearly all-day occurrences in `GET /calendar/events` from a virtual (read-only, LOCAL, visible, non-default) calendar | `birthdays are yearly all-day occurrences of a read-only virtual calendar`, `a range over several years yields one occurrence per year with the age reached`, `an occurrence is a one-day series member of the contact titled with name and age` | above | Pass |
| AC1: Feb 29 handled (Feb 28 in non-leap years, Feb 29 in leap years, 2100/2400, never on Mar 1, never twice) | `a February 29 birthday falls on February 28 in other years` (web), `February 29 falls on February 28 in non-leap years and stays on February 29 in leap years`, `a February 29 birthday is not on March 1 and not twice in a leap year` (unit) | above | Pass |
| AC1: merged with stored events in start order; calendar filter; visibility; hidden but explicitly requested | `occurrences merge with stored events in start order and respect the calendar filter and visibility`, `a hidden birthdays calendar still answers when requested explicitly`, `a range without any birthday keeps the stored events as before` | above | Pass |
| AC1: range edges in the user's zone, exclusive end, new year | `a range inside the birthday or ending at its start is judged in the user's zone`, `a range over the new year lists the birthdays of both years in date order`, unit tests on range boundaries, empty and inverted ranges | above | Pass |
| ASSUMPTION: no occurrence in the birth year; age omitted above 130 | `no occurrence before the first birthday, no age for placeholder years`, `there is no occurrence in the year of birth or before`, `the age is shown up to 130 years and omitted above` | above | Pass |
| ASSUMPTION: Trash and changed birthdays are reflected on the next fetch; projection reads only id/name/birthday | `contacts in the Trash and changed birthdays are reflected`, `the birthday projection reads id, name and birthday of the contacts with a birthday not in the Trash` | above | Pass |
| ASSUMPTION: lazy creation (startup / contact change with a birthday), only once, keeps the user's name/color/visibility | `the calendar is created only once there is a birthday, and only once`, `startup creates the calendar for birthdays saved before`, `ensuring the calendar again keeps the user's name, color and visibility`, both initializer unit tests | above | Pass |
| ASSUMPTION: read-only; 404 on `GET /calendar/events/{contactId}` | `birthday occurrences cannot be created in, read or changed` | above | Pass |
| SPI: only LOCAL `virtual:<key>` calendars are virtual; an unknown key yields nothing | `a calendar of another source whose external id looks virtual has no computed occurrences`, `a local calendar with an unknown virtual key has no occurrences and does not break the others` | above | Pass |
| ASSUMPTION 05:35 / TW-1 fix: concurrent first creation serialized by the advisory lock (with and without a LOCAL source, rollback of the first, no wait when the calendar exists) | `a second first-time creation waits for the first one and returns its calendar`, `two first-time creations before any local calendar exists yield one calendar in one local source`, `a first-time creation that rolls back lets the waiting one create the calendar and its local source`, `an existing birthdays calendar is returned without waiting for another virtual calendar's creation` | above | Pass |
| AC2: `./gradlew test --tests 'ch.sonensei.cockpit.contacts.birthdays.*'` passes (Postgres via Testcontainers) | all 33 tests | above | Pass (33/33: Unit 13, Web 8, EdgeCase 12, 0 failures, 0 skipped, per `build/test-results/test/TEST-*.xml`) |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.birthdays.*' jacocoTestReport` (run by the parent, because this helper cannot run Gradle)

Result:

Pass. BUILD SUCCESSFUL, 33 tests, 0 failures. I checked the result XMLs: `BirthdayCalendarEdgeCaseWebTests` tests=12, `BirthdayCalendarUnitTests` tests=13, `BirthdayCalendarWebTests` tests=8, all with failures=0, errors=0 and skipped=0. Timestamps are 2026-10-22T04:07:43Z to 04:08:08Z, which is after the HEAD commit at 06:07:25+02:00.

## Full test execution

Command:

`cd server && ./gradlew test` (full server suite)

Result:

Not run on 218c8e72 in this run, because this helper cannot run Gradle. The second run's full suite (4161 tests) passed except for TW-1, which is now fixed and covered by the passing race tests above. The parent should run the full suite through `.taskcheck` before `done`.

## .taskcheck

Result:

Not run by this helper (it cannot run Gradle). The task notes say `.taskcheck` passed on 9cdc69c1. The parent runs it before `done`.

## Bugs found

None in this run.

TW-1 from the previous run (concurrent first creation without a LOCAL source made two LOCAL sources and two Birthdays calendars) is fixed by the advisory lock in `CalendarService.ensureVirtualCalendar`. Its regression test `two first-time creations before any local calendar exists yield one calendar in one local source` now passes.

The remaining known limitation is accepted in the 05:35 assumption and the review's CR-1, so it is not a new defect: `CalendarService.create()` creates the LOCAL source without the lock. No test covers a simultaneous `create()` plus first `ensureVirtualCalendar`.

## Coverage

Status: verified (for the focused birthdays suite only; see the limitation below).

Metric: whole-file coverage of task-changed production files, measured from the birthdays suite alone. For the five calendar files this metric mostly reflects pre-existing code that the calendar module's own tests cover, not this suite.

Changed-line coverage: measured. Method: intersect JaCoCo XML per-line counters (`ci > 0`; `cb`/`mb`) with the added/modified lines of `git diff -U0 ecc4a85f..218c8e72 -- <file>`. Result: executable changed lines 77/78 (98.7%), branches on changed lines 46/52 (88.5%). `CalendarDtos.kt` and `CalendarEventController.kt` have no executable changed lines (description strings only).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `contacts-birthdays`. The base is `ecc4a85fdd1237af423d31969dd58df7fda8c883` and the measured HEAD is `218c8e72a4e4312ede0082ec13f2d3142fcfe9ef`, both supplied by the parent. Task commits from `git log ecc4a85f..218c8e72`: `0e65ff39`, `4d1a65ba`, `2a60258e`, `29d59a8b`, `0bea08a0`, `ccf6db89`, `139b2432`, `a00dbc12`, `4f75b688`, and the merge `218c8e72`. The file scope is `git diff --name-only ecc4a85f..218c8e72 -- server/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `218c8e72` with a clean working tree (`git status --porcelain` empty). There are no uncommitted inputs. Test blobs: EdgeCase `fb8e2692`, Unit `b3556bb3`, Web `6cd36e7e`.
- Exact included files: `contacts/birthdays/BirthdayCalendar.kt`, `contacts/birthdays/BirthdayCalendarInitializer.kt`, `calendar/api/VirtualCalendars.kt`, `calendar/api/CalendarService.kt`, `calendar/api/CalendarEventService.kt`, `calendar/api/CalendarEventController.kt`, `calendar/api/CalendarDtos.kt`, `calendar/recurrence/OccurrenceExpander.kt`, all under `server/src/main/kotlin/ch/sonensei/cockpit/`. There are no renames or deletions. `CalendarRepositories.kt` is no longer in the diff (`findForUpdate` was removed). Excluded: test files, `api/openapi.yaml` and `web/.../occurrence-response.ts` (generated description changes), and `tasks/` and `reviews/`.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin (0.8.15 per the earlier run; the build configuration is unchanged). The command is `./gradlew test --tests 'ch.sonensei.cockpit.contacts.birthdays.*' jacocoTestReport`, run in `server/` by the parent. Only the three birthdays test classes ran: `build/test-results/test/` holds only their three XMLs.
- Run start/completion time and exit status: tests ran from 2026-10-22T04:07:43Z to 04:08:10Z (result XML timestamps and mtimes). `test.exec` mtime is 06:08:09+02:00 and the report mtime is 06:08:12+02:00. Exit status: BUILD SUCCESSFUL, as reported by the parent.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 `adfd37a521a481e51fabbf747a4ebef031d055cb0adf642133efb3f26d172a49`. Exec data: `server/build/jacoco/test.exec` (1087675 bytes).
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report postdates the HEAD commit. The tree was clean, and I made no source or test changes in this run.
- Final run after latest test additions and production fixes: yes. It ran on the final HEAD 218c8e72, after the 9cdc69c1 race tests and the advisory-lock fix.

Values come from the JaCoCo XML per source file, as covered/total (percentage). JaCoCo has no statement counter, so the Statements column uses INSTRUCTION counters. The parent quoted class-level counters (for example `BirthdayCalendarProvider` 15/15 lines + `BirthdayContact` 4/4 = 19/19 for the file; `CalendarService` class 71/77, whose file also contains `CalendarView`/`CalendarChanges`). The file-level values below are consistent with those.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `contacts/birthdays/BirthdayCalendar.kt` | 141/141 (100%) | 10/10 (100%) | 8/8 (100%) | 19/19 (100%) |
| `contacts/birthdays/BirthdayCalendarInitializer.kt` | 43/43 (100%) | 8/8 (100%) | 4/4 (100%) | 11/11 (100%) |
| `calendar/api/VirtualCalendars.kt` | 64/68 (94.1%) | N/A (0 branches) | 8/8 (100%) | 8/8 (100%) |
| `calendar/api/CalendarService.kt` | 571/720 (79.3%) | 40/64 (62.5%) | 24/26 (92.3%) | 79/86 (91.9%) |
| `calendar/api/CalendarEventService.kt` | 577/3133 (18.4%) | 53/362 (14.6%) | 14/55 (25.5%) | 83/378 (22.0%) |
| `calendar/api/CalendarEventController.kt` | 186/659 (28.2%) | 1/36 (2.8%) | 4/14 (28.6%) | 37/73 (50.7%) |
| `calendar/api/CalendarDtos.kt` | 977/1505 (64.9%) | 21/40 (52.5%) | 77/122 (63.1%) | 182/213 (85.4%) |
| `calendar/recurrence/OccurrenceExpander.kt` | 356/931 (38.2%) | 22/96 (22.9%) | 24/33 (72.7%) | 52/106 (49.1%) |
| Aggregate (summed counters, all 8 files measured, none excluded) | 2915/7200 (40.5%) | 155/616 (25.2%) | 163/270 (60.4%) | 471/894 (52.7%) |

The low whole-file values in the five calendar files are pre-existing code (event CRUD, recurrence edits, series scopes, the other DTOs and endpoints) that only the calendar module's tests exercise. Those tests were not part of this focused run. Second run, full suite, same files before the advisory-lock change: CalendarService 83/84 lines, CalendarEventService 375/378, OccurrenceExpander 105/106. This run does not re-measure those values.

Uncovered task code (changed lines):

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `OccurrenceExpander.kt:139` (`return ordered(generated + overridden, range.zone)` in series expansion) | Refactor only: stored recurring series now sort through the shared `ordered()` | Not exercised by the birthdays suite, because no birthday test stores a recurring series. The calendar recurrence tests cover it; the second run's full suite covered this file at 105/106 lines. Justified gap. |
| `CalendarEventService.kt:107` (`providers.isEmpty()` true branch) | No virtual provider registered | Not reachable in the application context (the birthdays provider is always a bean); defensive |
| `CalendarEventService.kt:112` (2 branches: the overlap filter rejecting a provider's out-of-range occurrence, and `providers[key]?` with a known key and a null result) | A provider returning occurrences outside the requested days | The only provider already returns in-range occurrences only (unit-tested). A test would need a fake provider bean and its own Spring context. The unknown-key path is tested at the API level. Non-material. |
| `CalendarEventService.kt:121` (`occurrenceDate == null`) | Virtual occurrence without a series date | The only provider always sets it; non-material |
| `CalendarService.kt:131` (1 branch: a LOCAL calendar whose non-null external id does not start with `virtual:`) | Other LOCAL calendars with an external id | LOCAL calendars normally have a null external id. The non-LOCAL and unknown-key cases are tested. Non-material. |
| `CalendarService.kt:173` (1 branch of `firstOrNull { type == LOCAL }`: a non-LOCAL source listed first) | Picking the LOCAL source when other sources exist | Exercised when a feed source sorts first by id. That depends on random ids in the impostor test, so this run did not hit it. The predicate is pre-existing logic moved into `existingLocalSource()`. Non-material. |
| `VirtualCalendars.kt:15-16` (data-class default values of `endDate` / `seriesId` / `occurrenceDate`, 4 instructions) | Defaults of the SPI data class | The only provider passes all values explicitly; non-material |

## Unable to test

- Full server suite and `.taskcheck` on 218c8e72: this helper cannot run Gradle. The parent must run them before `done`.
- The startup check (`ApplicationReadyEvent`) racing a real contact commit was not tested as an event race. It uses the same `ensureVirtualCalendar` path and lock as the four race tests.
- `CalendarService.create()` racing a first `ensureVirtualCalendar` (possible second LOCAL source): this is accepted behavior per the 05:35 assumption and CR-1, so no test was added. Follow-up only if humans reject that assumption.
- Contact changes through provider sync (CardDAV/Google/Microsoft) were not exercised separately. They publish the same `ChangeEvent` that the initializer tests cover.
- Web and Android handling of birthday occurrences is out of scope (follow-up task `web-and-birthday-occurrences`).

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. All 33 birthdays tests pass on the final revision 218c8e72, including the regression test for TW-1 and the rollback and no-wait race tests. Both acceptance criteria are covered. All new birthdays code is fully covered: changed lines 77/78 and changed branches 46/52, with the remaining gaps defensive or covered by the calendar suite. The full server suite and `.taskcheck` still have to be run by the parent.
