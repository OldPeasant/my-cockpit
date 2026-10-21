# Test Report

Date: 2026-10-21

Task: cal-google - Google Calendar adapter (SPEC.md §6.2). Second round, after the fix commit d2324910.

Scope: `git diff 1d2f349eede723cd1f2f25dda0ade84523830235..f798eb17ff25ce8ec407f654172b76ebf403c3a2` (task commits ce10f500..f798eb17 on `main`, as given by the parent), limited to `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/google`, its tests and WireMock recordings, plus the test-only `server/src/test/kotlin/ch/sonensei/cockpit/calendar/sync/FakeCalendarAdapter.kt` (TW-2 fix). The tests added in this round are uncommitted working-tree changes (hashes under Coverage).

Requirements:
- `tasks/cal-google.md` acceptance criteria 1-3
- SPEC.md §6.2 (through the adapter's KDoc and the sync engine contract `calendar/sync/CalendarProviderAdapter.kt`)
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, AGENTS.md
- `reviews/cal-google/fixes.md` (fixes for CR-1/TW-1, TW-2, CR-3) and the parent's instruction to confirm them

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/google/GoogleCalendarAdapterTests.kt` (1 test added; the `FixedTokens` test double got a `removed` flag)
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/google/GoogleEventMappingTests.kt` (1 test added)

No existing test was changed, weakened or disabled.

## Tests added or updated

Count: 2 added in this round.

- `GoogleEventMappingTests > the status is read as tentative or cancelled, anything else is confirmed`: unit check of the CR-1/TW-1 mapping change in `toRemote`. Covers tentative, cancelled, confirmed, an unknown value and a missing status.
- `GoogleCalendarAdapterTests > a source whose connection was removed fails as removed, not as unlinked, before calling Google`: the CR-3 fix added a provider check. A connection the token store no longer knows (`connection()` returns null) must still give the "was removed" error from the token path, not "not linked", and no request may go to Google.

Totals in scope: GoogleCalendarAdapterTests 34, GoogleEventMappingTests 19, GoogleCalendarSyncTests 5 (58). The sync-engine suites (CalendarSync*Tests) have 91 tests.

How the fixes are covered:

| Fix | Test(s) |
|---|---|
| CR-1/TW-1: a cancelled write answer stays CANCELLED | `GoogleCalendarAdapterTests > cancelling an edited occurrence keeps it cancelled in Google's answer` (from round 1, failed before the fix, passes now). New: `GoogleEventMappingTests > the status is read as tentative or cancelled, anything else is confirmed`. Listings still treat cancelled items as deletions/EXDATEs: `an incremental fetch sends the sync token, and a changed series gets its cancelled occurrences as EXDATEs`, `a changed all-day series gets the dates of its own cancelled occurrences only`, `GoogleEventMappingTests > a cancelled occurrence of an all-day series is keyed by its date` (all pass) |
| TW-2: sync tests use a registry with only the fake adapter | `CalendarSyncEdgeCaseTests > GET sources shows a source without adapter as not syncable, without status`, `CalendarSyncEdgeCaseTests > syncDue never syncs local sources or types without adapter`, `CalendarSyncTests > POST sync of a local, unsupported or unknown source fails` now pass. `GoogleCalendarSyncTests` (5 tests) still runs against the real registry with the Google adapter and passes |
| CR-3: refuse a connection of another provider | `a source linked to a connection of another provider fails without sending its token to Google` (fixer, all 5 operations, 0 WireMock requests). New: `a source whose connection was removed fails as removed, not as unlinked, before calling Google`. The GOOGLE-provider happy path runs through every other adapter test, because `FixedTokens` now returns a GOOGLE connection |

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 calendar list | `listCalendars reads every page, prefers the user's name and skips deleted and free-busy calendars`, `GoogleEventMappingTests > a calendar without a name is named by its id, and only owners and writers may write`, `GoogleCalendarSyncTests > a first sync stores Google's calendar and events...` | `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.calendar.infra.google.*' --tests 'ch.sonensei.cockpit.calendar.sync.*' jacocoTestReport` | Pass |
| AC1 incremental sync with syncToken, 410 -> full resync | `the first fetch lists the calendar completely...`, `an incremental fetch sends the sync token...`, `an incremental run continues with the page token...`, `410 Gone for an expired sync token starts a complete listing`, `410 within an incremental run's pages also starts over completely, an unknown cursor too`, `410 during a complete listing is an error, not an endless restart`, `GoogleCalendarSyncTests > 410 for the stored sync token lists the calendar again and removes what Google no longer has` | same | Pass |
| AC1 writes via API | `createEvent inserts with an id derived from the cockpit id...`, `a repeated insert whose id exists returns the stored event`, `updateEvent patches with If-Match and an explicit recurrence`, `deleteEvent deletes with If-Match...`, `412 on a write is a remote change, so is a 404 of the event`, `GoogleCalendarSyncTests > an event created in the cockpit is inserted at Google...`, mapping tests for request bodies | same | Pass |
| AC1 writes only with a Google connection (CR-3) | `a source linked to a connection of another provider fails without sending its token to Google`, `a source whose connection was removed fails as removed, not as unlinked, before calling Google`, `a source without connection or with a connection to reconnect fails before calling Google`, `every kind of token failure is a provider error naming what to do, never the token` | same | Pass |
| AC1 recurring instance edits (incl. cancelling an edited occurrence, CR-1/TW-1) | `an exception of a series is a patch of its instance...`, `an exception of an all-day series patches the instance named by its date`, `updating an exception patches its instance with If-Match, 410 there is a remote change`, `cancelling an edited occurrence keeps it cancelled in Google's answer`, `the status is read as tentative or cancelled, anything else is confirmed` | same | Pass |
| AC1 'this and following' split | `GoogleCalendarSyncTests > changing this and following occurrences ends the series at Google and continues it as a new one with its exceptions` (Postgres via Testcontainers) | same | Pass |
| AC2 WireMock recordings incl. 410 and 412 | tests using `error-410-full-sync-required.json`, `error-412-condition-not-met.json` and the other recordings; `GoogleCalendarSyncTests > 412 from Google fails the edit with 409 and shows Google's version` | same | Pass |
| AC3 `./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.google.*'` passes | all 58 tests in the package | same (a superset of the AC3 command) | Pass: 58 tests, 0 failures |
| Sync-engine regression (TW-2) | CalendarSyncEdgeCaseTests 31, CalendarSyncOverrideTests 25, CalendarSyncTests 17, CalendarSyncUnitTests 18 | same | Pass: 91 tests, 0 failures |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.calendar.infra.google.*' --tests 'ch.sonensei.cockpit.calendar.sync.*' jacocoTestReport`

Result:

Pass. BUILD SUCCESSFUL in 58s. 149 tests in 7 classes (Google 58, sync engine 91), 0 failures, 0 skipped. ktlint (main and test source sets) passed.

## Full test execution

Command:

`cd server && ./gradlew check`

Result:

Pass. BUILD SUCCESSFUL in 7m 7s. 3118 tests, 0 failures, 0 errors, 0 skipped (counted from `server/build/test-results/test/TEST-*.xml`). In this run the known flaky `ClockConfigurationTests` passed as well.

## .taskcheck

Result:

Not run. `.taskcheck` is `sh scripts/check.sh` (server, web and Android). This task changes only server code, and its server part (`./gradlew check`) passed above. The parent runs `.taskcheck` before `done`.

## Bugs found

None in this round. TW-1 (with CR-1) and TW-2 from round 1 are fixed. Their tests pass on the final code (see "How the fixes are covered").

## Coverage

Status: verified (fresh run on the final code plus the 2 new tests, exit 0).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. All three files were added by this task (`git diff --stat` of the base shows them as new), so whole-file coverage equals changed-line coverage here.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: cal-google; base 1d2f349eede723cd1f2f25dda0ade84523830235; HEAD f798eb17ff25ce8ec407f654172b76ebf403c3a2; commits ce10f500..f798eb17 as given by the parent; production files from `git diff --stat 1d2f349e..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs), sha256 prefixes: `GoogleCalendarAdapter.kt` b7192bcf07ca6bc9, `GoogleCalendarClient.kt` 0cbd01e1c540e4ee, `GoogleEventMapping.kt` 3cccb75e0a52c94e (all three unchanged from HEAD); `GoogleCalendarAdapterTests.kt` 7a89560f41fa7593 (uncommitted), `GoogleCalendarSyncTests.kt` 06d4bfec3983fec5 (HEAD), `GoogleEventMappingTests.kt` 32804b5166b78543 (uncommitted), `sync/FakeCalendarAdapter.kt` d58d1fb44c2f92fc (HEAD).
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/google/GoogleCalendarAdapter.kt`, `GoogleCalendarClient.kt`, `GoogleEventMapping.kt`. Excluded: tests, WireMock recordings (test resources), `reviews/` (documentation), and `sync/FakeCalendarAdapter.kt` (test code). No production files were renamed or deleted.
- Provider/version, command, working directory, and test selection: JaCoCo (Gradle `jacoco` plugin, version from the project's build), OpenJDK 21.0.12.1; `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.calendar.infra.google.*' --tests 'ch.sonensei.cockpit.calendar.sync.*' jacocoTestReport` in `server/`.
- Run start/completion time and exit status: started 2026-10-21T12:44:28+02:00, report written 2026-10-21T12:45:30+02:00, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`, sha256 72a7a3b270f1b7600b21f607fca2dcbed62bb818b0cb947bb9d7b71b96a70027 (Git-ignored).
- Evidence outputs are fresh and inputs stayed unchanged: yes. Gradle recompiled the test sources and re-executed `test` and `jacocoTestReport`. `sha256sum -c` of the snapshot after both runs: all OK.
- Final run after latest test additions and production fixes: yes. It ran on the final production code (d2324910 fixes, no later production change) with both new tests.

Values below use covered/total (percentage), from JaCoCo `<sourcefile>` counters in package `ch/sonensei/cockpit/calendar/infra/google`. Statements = JaCoCo INSTRUCTION.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/google/GoogleCalendarAdapter.kt` | 1084/1097 (98.8%) | 98/104 (94.2%) | 15/16 (93.8%) | 160/160 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/google/GoogleCalendarClient.kt` | 457/458 (99.8%) | 31/32 (96.9%) | 13/13 (100%) | 66/66 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/google/GoogleEventMapping.kt` | 1289/1290 (99.9%) | 111/112 (99.1%) | 27/27 (100%) | 162/162 (100%) |
| **Aggregate (summed counters, 3 of 3 scoped files, none missing or excluded)** | 2830/2845 (99.5%) | 240/248 (96.8%) | 55/56 (98.2%) | 388/388 (100%) |

There is no task-level coverage threshold (ARCHITECTURE.md "Coverage": coverage informs the test-writer and is not a gate).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `GoogleCalendarAdapter.kt:121` (3 of 4 branches missed) | Re-throwing a RuntimeException other than IllegalArgumentException/DateTimeException while reading a listed event | Justified gap: a defensive rethrow of unexpected programming errors. The mapping only throws the two handled types, which are tested (`events without id, without start or with an invalid date cannot be read`; skipped malformed events in the adapter tests) |
| `GoogleCalendarAdapter.kt:274` (3 of 4 branches missed) | Same defensive rethrow in `answer()` for write answers | Justified gap, same reason. The handled case is tested (`an answer to a write that cannot be read is a provider error`) |
| `GoogleCalendarAdapter.kt` (1 of 16 methods) | A Kotlin-generated method of the adapter (all 160 lines are covered) | Justified gap: no source line is uncovered |
| `GoogleCalendarClient.kt:48` (1 of 4 branches) | One bound of `status in 200..299` (a status above 299 that is not an error is never sent by the tests on that side of the range, or vice versa) | Justified gap: both success and many error statuses (400, 403, 404, 409, 410, 412, 5xx) are tested |
| `GoogleEventMapping.kt:41` (1 of 2 branches) | `isInstance` returning false (no `recurringEventId`) | No test added: `isInstance` has no production caller (only `GoogleEventMappingTests:128` calls it, with an instance). It is unused code, not behavior. Reported to the parent as a maintainability note (remove it or use it); not a defect |

## Unable to test

- Calls against the real Google Calendar API: not possible here (no real account or credentials, and tests must not use real data). AC2 asks for WireMock recordings, which is what all tests use. No follow-up needed for this task.
- CR-2 (reminders are written as popup overrides) was deliberately not fixed. The parent records it as an assumption. No test asserts other behavior.

## Summary

Tests added and all validation passed. The fixes for TW-1/CR-1, TW-2 and CR-3 are covered by passing tests. Task-scoped coverage is 100% of lines and 96.8% of branches, and the remaining gaps are justified. No production defects were found in this round.
