# Test Report

Date: 2026-10-22

Task: contacts-birthdays - Birthdays calendar

Scope: the final code of the task after the review fixes (second test-writer run). Task commits rebased onto base `9a47e3f5d674d437515fe536cb7c4a235672588d`: `766bef82` (implementation), `7475d4a4` (tests + earlier report), `df38f677` (review report), `a7dfcc68` (fixes CR-1..3), `14311f86` (task file only). HEAD is `14311f86906a600c99518bf5f3212ce38d0264a1` on `main`. The parent supplied base `a300dc2f` and HEAD `d4a49482`. Main was rebased while this run was in progress. `git diff d4a49482 HEAD -- server web api` is empty, so the code tree I measured is the one the parent asked for. Uncommitted inputs: my edits to `BirthdayCalendarEdgeCaseWebTests.kt`. `reviews/contacts-birthdays/review.md` was also modified in the working tree, but not by me (it is outside this report).

Requirements:
- `tasks/contacts-birthdays.md` acceptance criteria and the four recorded ASSUMPTIONs
- SPEC.md §8.3 (virtual read-only birthdays calendar)
- `reviews/contacts-birthdays/review.md` (CR-2 creation race, CR-3 projection) and `fixes.md` (the claimed behavior of the fixes)
- docs/ARCHITECTURE.md "Coverage"
- Parent instructions: re-verify `CalendarService.ensureVirtualCalendar` locking (`CalendarSourceRepository.findForUpdate`) and the `BirthdayContact` JPQL projection on the final code

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/birthdays/BirthdayCalendarEdgeCaseWebTests.kt` (2 tests added; existing tests unchanged)

`BirthdayCalendarUnitTests.kt` and `BirthdayCalendarWebTests.kt` are unchanged in this run.

## Tests added or updated

Count: 2 added (package total: 31 tests = 13 unit + 8 web + 10 edge-case)

- `two first-time creations before any local calendar exists yield one calendar in one local source`: a regression test for the creation race in the case the fix does not cover. It fails, see TW-1.
- `the birthday projection reads id, name and birthday of the contacts with a birthday not in the Trash`: calls `BirthdayContactRepository.findBirthdays()` directly against Postgres. The contacts are one with a birthday, emails and notes, one with a Feb 29 birthday, one without a birthday, and one with a birthday that is in the Trash. Only the first two come back, with exactly `id`, `displayName` and `birthday`.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: birthdays appear as yearly all-day occurrences in `GET /calendar/events` from a virtual calendar | `BirthdayCalendarWebTests` (8), `BirthdayCalendarUnitTests` (date logic, 13), `BirthdayCalendarEdgeCaseWebTests` (range/zone/visibility/SPI cases) | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.birthdays.*' ktlintCheck` | Pass |
| AC1: Feb 29 handled (Feb 28 in non-leap years, Feb 29 in leap years, 2100/2400) | `February 29 falls on February 28 in non-leap years and stays on February 29 in leap years`, `a February 29 birthday is not on March 1 and not twice in a leap year`, `a February 29 birthday falls on February 28 in other years` | same | Pass |
| CR-3 fix: projection reads only id/name/birthday of contacts with a birthday that are not in the Trash (real Postgres JPQL) | `the birthday projection reads id, name and birthday of the contacts with a birthday not in the Trash` (new); indirectly all web tests | same | Pass |
| CR-2 fix: concurrent first creations, LOCAL source exists: the second call waits and returns the same calendar | `a second first-time creation waits for the first one and returns its calendar` | same | Pass |
| CR-2 fix: concurrent first creations, no LOCAL source yet: one calendar, one LOCAL source | `two first-time creations before any local calendar exists yield one calendar in one local source` (new) | same | **Fail** (TW-1) |
| ASSUMPTION: lazy creation (startup / contact change with a birthday), idempotent, keeps the user's name/color/visibility | initializer unit tests, `the calendar is created only once there is a birthday, and only once`, `startup creates the calendar for birthdays saved before`, `ensuring the calendar again keeps the user's name, color and visibility` | same | Pass |
| ASSUMPTION: read-only, 404 on `GET /calendar/events/{contactId}` | `birthday occurrences cannot be created in, read or changed` | same | Pass |
| SPI: only LOCAL calendars whose external id is `virtual:` plus a provider key are virtual; an unknown key yields nothing | `a calendar of another source whose external id looks virtual has no computed occurrences`, `a local calendar with an unknown virtual key has no occurrences and does not break the others` | same | Pass |
| CR-1 contract doc (OpenAPI) | `OpenApiContractTest` (in the full suite) | `cd server && ./gradlew test jacocoTestReport --continue` | Pass |
| AC2: `./gradlew test --tests 'ch.sonensei.cockpit.contacts.birthdays.*'` passes (Postgres via Testcontainers) | all 31 tests in the package | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.birthdays.*' ktlintCheck` | **Fail**: 30 passed, 1 failed (TW-1) |

## Focused test execution

Command:

`cd server && ./gradlew ktlintFormat && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.birthdays.*' ktlintCheck`

Result:

Fail. 31 tests, 1 failed: `BirthdayCalendarEdgeCaseWebTests > two first-time creations before any local calendar exists yield one calendar in one local source` (`expected:<1> but was:<2>` at the `virtual:birthdays` row count). ktlint is clean. The failure reproduced in the full run below.

## Full test execution

Command:

`cd server && ./gradlew test jacocoTestReport --continue` (full server suite, including the calendar tests, Testcontainers Postgres)

Result:

Fail. 4161 tests, 1 failed: the same TW-1 test. All other tests passed, including every `calendar.*` test and `OpenApiContractTest`. The fixer's report mentions an `IcsFeedClientTests` flake; it did not occur in this run. Build time 6m59s. Web and Android are not affected by this task's code in this revision; only the generated `occurrence-response.ts` description changed, and the fixer reported `npm run check` passing for it. I did not rerun them.

## .taskcheck

Result:

Not run. `scripts/check.sh` runs `cd server && ./gradlew check`, which includes the failing TW-1 test (the full server suite above), so it would fail on the server step. Rerun it after TW-1 is fixed.

## Bugs found

### Concurrent first creation of the birthdays calendar without a LOCAL source creates two LOCAL sources and two Birthdays calendars

Finding ID:

`TW-1`

Test:

`BirthdayCalendarEdgeCaseWebTests > two first-time creations before any local calendar exists yield one calendar in one local source`

Production source:

`server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarService.kt:106-110` (`ensureVirtualCalendar`) together with `localSource()` at `CalendarService.kt:167-169`

Requirement:

Review finding CR-2 and the documented behavior of the fix (KDoc of `ensureVirtualCalendar`, `fixes.md`): concurrent first calls (two contact commits, or one during the startup check) wait for each other, so the second finds the calendar instead of creating another one. The ASSUMPTION says there is one Birthdays calendar, created lazily once a contact has a birthday. The calendar is often created before any local calendar exists: a user whose first data is contacts has no LOCAL calendar source yet.

Expected behavior:

When no LOCAL source exists, two concurrent `ensureVirtualCalendar("birthdays", ...)` calls leave exactly one LOCAL source and one `virtual:birthdays` calendar, and both calls return that calendar's id.

Actual behavior:

`localSource()` cannot see the first transaction's uncommitted LOCAL source, so the second call creates its own LOCAL source. `findForUpdate` then locks a different row and does not wait. The unique index `calendar_external_id` is on `(source_id, external_id)`, so the second insert does not conflict. Result: 2 LOCAL sources and 2 `virtual:birthdays` calendars, and no error is logged. `virtualKeys` treats both as virtual (both sources are LOCAL), so every birthday is listed twice in `GET /calendar/events`. `localSource()` and the existing `create()` then pick one of the two sources by id. The test reproduces this deterministically with latches (no sleeps decide the outcome). The fixer's own regression test creates a calendar first (`api.createCalendar()`, "the LOCAL source exists"), so this case was not exercised. The unlocked `localSource()` creation already existed in `create()`, but `ensureVirtualCalendar` is new task code that claims concurrency safety, and it is the path that typically creates the first source.

Severity:

Low. It needs two first-time creations at the same moment on an instance without any local calendar, for example two clients saving contacts with birthdays at once on a fresh install, or a contact save during the startup check. The result is persistent (duplicate calendar and duplicate occurrences) and needs manual cleanup.

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Coverage

Status: partial-failed. The coverage data is fresh and complete for the full server suite, but the run exited nonzero because of the TW-1 test. The numbers below are diagnostic until TW-1 is fixed and coverage is re-measured.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured by intersecting JaCoCo XML per-line counters (`ci > 0`, `cb`/`mb`) with the added lines of `git diff -U0 9a47e3f5..HEAD -- server/src/main` (the same code as `a300dc2f..d4a49482`). Executable changed lines: 74/74 covered (100%). Branches on changed lines: 41/46 covered (89.1%); the 5 missed branches are listed in the gap table. `CalendarDtos.kt`, `CalendarEventController.kt` and `CalendarRepositories.kt` have no executable changed lines (descriptions, an interface annotation).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `contacts-birthdays`. Base supplied by the parent: `a300dc2f8dd709f79dbf625afd456659e05ced37` (task commits up to `d4a49482`). After the rebase on main during this run, the base is `9a47e3f5d674d437515fe536cb7c4a235672588d` and the measured HEAD is `14311f86906a600c99518bf5f3212ce38d0264a1`, with commits `766bef82`, `7475d4a4`, `df38f677`, `a7dfcc68`, `14311f86`. Ownership comes from the parent's commit list, matched by commit subject. `git diff d4a49482 HEAD -- server web api` is empty.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `14311f86` plus the modified `BirthdayCalendarEdgeCaseWebTests.kt` sha256 `9417ee3030c9914e911699797930a68b969305eaaaa740a715c988a82435ee45`. Unchanged: `BirthdayCalendarUnitTests.kt` sha256 `4852b26b7c6cc2cf0e7c57a8a1db51bab4cc418f1d2307d80c547543132c81a2`, `BirthdayCalendarWebTests.kt` sha256 `71d7ae509991ac6f546717c4f11d9e2a2a7064081216c25aab28bec842e282bd`.
- Exact included files; renamed/deleted/excluded files and reasons: the 9 production files from `git diff --name-only 9a47e3f5..HEAD -- server/src/main` (table below). There are no renames or deletions. `CalendarRepositories.kt` holds only Spring Data repository interfaces, so it compiles to no instrumented bytecode and JaCoCo reports no counters for it: unmeasured, not 100%. Its new `findForUpdate` query runs through Spring Data proxies in the race tests. Excluded: test files, `api/openapi.yaml` and `web/.../occurrence-response.ts` (generated), and the `tasks/` and `reviews/` files.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin. The build configuration is unchanged since the earlier run, which recorded version 0.8.15; I did not query the version again. Command: `./gradlew test jacocoTestReport --continue` in `server/`, full server suite. `jacocoTestReport` was skipped because `test` failed, so I generated the report from this run's `build/jacoco/test.exec` with `./gradlew jacocoTestReport -x test`.
- Run start/completion time and exit status: about 2026-10-22T05:12:50+02:00 to 05:19:49+02:00 (6m59s, from Gradle's build time and the `test.exec` mtime). Exit status nonzero (BUILD FAILED, 1 test failed). The report was generated at 05:19:56+02:00, exit 0.
- Generated report paths and report hashes/run identifier: `server/build/jacoco/test.exec` sha256 `f4f3a1d7f63291bfddb7b9400325084a5bd0ce39b81605475bc52082d21d251f` (mtime 05:19:48). `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 `759f88ef33cba1bd9ab0bd05691fc0c4901e5d40de88d05ed312e1b7a9236d98`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The test task ran again (new test compiled in). `test.exec` and the test result XML have this run's timestamps. The test-file hash and the git status were the same before and after the run.
- Final run after latest test additions and production fixes: yes for the test additions and the CR-1..3 fixes. A fix for TW-1 will make these numbers stale.

Values below use covered/total (percentage). JaCoCo has no statement counter; the Statements column shows INSTRUCTION counters.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/contacts/birthdays/BirthdayCalendar.kt` | 141/141 (100%) | 10/10 (100%) | 8/8 (100%) | 19/19 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/contacts/birthdays/BirthdayCalendarInitializer.kt` | 43/43 (100%) | 8/8 (100%) | 4/4 (100%) | 11/11 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/VirtualCalendars.kt` | 64/68 (94.1%) | N/A (0 branches) | 8/8 (100%) | 8/8 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarService.kt` | 666/697 (95.6%) | 59/62 (95.2%) | 24/25 (96.0%) | 83/84 (98.8%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarEventService.kt` | 3092/3133 (98.7%) | 347/362 (95.9%) | 53/55 (96.4%) | 375/378 (99.2%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarEventController.kt` | 653/659 (99.1%) | 35/36 (97.2%) | 12/14 (85.7%) | 72/73 (98.6%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarDtos.kt` | 1411/1505 (93.8%) | 34/40 (85.0%) | 104/122 (85.2%) | 213/213 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/recurrence/OccurrenceExpander.kt` | 891/931 (95.7%) | 81/96 (84.4%) | 31/33 (93.9%) | 105/106 (99.1%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/model/CalendarRepositories.kt` | N/A (no bytecode) | N/A | N/A | N/A |
| Aggregate (summed counters over the 8 measured files; `CalendarRepositories.kt` unmeasured, see above) | 6961/7177 (97.0%) | 574/614 (93.5%) | 244/269 (90.7%) | 886/892 (99.3%) |

Most uncovered counters in the five calendar files are pre-existing code outside the task's changed lines: `CalendarService.kt:26-31, 157`; `CalendarEventService.kt:93, 145, 266-267, 413, 420, 533, 561, 604, 734-756`; `CalendarDtos.kt:56-57, 104, 164, 173-174, 246-270`; `CalendarEventController.kt:234, 263, 282`; `OccurrenceExpander.kt:101, 124-248`. They are not listed one by one below.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CalendarService.kt:104-111` (no line or branch gap, but a behavior gap) | Concurrent first creation without a LOCAL source | Test added; it fails: TW-1 |
| `BirthdayCalendar.kt:22-27` (JPQL projection; JaCoCo cannot see the query) | CR-3: only contacts with a birthday that are not in the Trash; correct column mapping | Test added: `the birthday projection reads ...` (Pass) |
| `CalendarEventService.kt:107` (`providers.isEmpty()` true branch) | No virtual provider registered | Not reachable in the application context (the birthdays provider is always a bean); defensive, non-material |
| `CalendarEventService.kt:112` (2 branches of the overlap filter, plus the null branch of `providers[key]?`, which is reported as partial) | A provider returning occurrences outside the requested days | The birthdays provider already returns only in-range occurrences (unit-tested). A test would need a fake provider bean and its own Spring context; non-material. The unknown-key path is tested at the API level. |
| `CalendarEventService.kt:121` (`occurrenceDate == null`) | Virtual occurrence without a series date | The only provider always sets it; non-material |
| `CalendarService.kt:126` (1 branch: LOCAL calendar with a non-null external id not starting with `virtual:`) | Other LOCAL calendars with external ids | LOCAL calendars normally have a null external id; the non-LOCAL and unknown-key cases are tested; non-material |
| `VirtualCalendars.kt:15-16` (default values of `seriesId` / `occurrenceDate`) | Data-class defaults | The only provider always passes both; non-material |

## Unable to test

- The startup check (`ApplicationReadyEvent`) racing a contact commit was not tested as a real event race. It uses the same `ensureVirtualCalendar` path as the two race tests, so TW-1 and its fix apply to it as well.
- Contact changes arriving through provider sync (CardDAV/Google/Microsoft) were not exercised separately. They go through the same `ChangeEvent` path that the initializer unit test covers.
- Web and Android handling of birthday occurrences (opening one in the event dialog) is out of scope. It is the follow-up task `web-and-birthday-occurrences`.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion. TW-1 (Low): a concurrent first creation without an existing LOCAL calendar source creates duplicate LOCAL sources and duplicate Birthdays calendars. All other 4160 server tests pass. The CR-2 lock works when a LOCAL source exists, and the CR-3 projection is verified against Postgres. Coverage numbers are fresh but partial-failed because of the TW-1 test, so coverage must be re-measured after the fix.
