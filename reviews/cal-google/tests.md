# Test Report

Date: 2026-10-21

Task: cal-google - Google Calendar adapter (SPEC.md §6.2). Third and final round, after 5029346c (KDoc re-wrap of `GoogleEventMapping.toRemote`, removal of the unused `GoogleEventMapping.isInstance`).

Scope: `git diff f65037ded7c52736372b283ae7257257a35599ae..aab965924f48cc4efd89d59de40a31dc461e81c8` (task commits cffbf776..aab96592 on `main`). The parent named the range 35f2550e..5029346c with HEAD 5029346c; the checked-out `main` holds the same commits rebased, and `git diff 5029346c aab96592` differs only in `tasks/cal-google.md` (one line), so the code is identical. Production scope: the three files in `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/google`. Tests: the three test classes in the same test package, the WireMock recordings, and the test-only `server/src/test/kotlin/ch/sonensei/cockpit/calendar/sync/FakeCalendarAdapter.kt`. The test added in this round is an uncommitted working-tree change (hashes under Coverage).

Requirements:
- `tasks/cal-google.md` acceptance criteria 1-3
- SPEC.md §6.2 (Google: `syncToken` incremental sync, 410 -> full resync, fallback poll every 5 min, writes via API)
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, AGENTS.md
- Previous reports `reviews/cal-google/tests.md` (round 2), `review.md`, `fixes.md`

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/google/GoogleCalendarAdapterTests.kt` (1 test added, plus the `java.time.Duration` import)

No existing test was changed, weakened or disabled. The only assertion removed since round 2 is the `isInstance` check in `GoogleEventMappingTests`, removed by the parent in 5029346c together with the function it tested. That function had no production caller, so no behavior lost its test. The same test still checks `isCancelled` and `key` of that instance.

## Tests added or updated

Count: 1 added in this round.

- `GoogleCalendarAdapterTests > the adapter serves Google sources and falls back to polling every 5 minutes`: SPEC.md §6.2 says the Google fallback poll runs every 5 min. The sync engine reads `pollInterval` from the adapter (`CalendarSyncService` due check, `CalendarSourceController` `pollIntervalSeconds`). No test read the Google adapter's value: JaCoCo showed `getPollInterval` as the only method of the package that never ran (`GoogleCalendarAdapter.kt:52`). The test also pins `sourceType` to GOOGLE, which the registry uses to pick the adapter.

Review of 5029346c: the KDoc change is not executable. Removing `isInstance` removes code that nothing in production called. No other test is needed.

Totals in scope: GoogleCalendarAdapterTests 35, GoogleEventMappingTests 19, GoogleCalendarSyncTests 5 (59). The sync-engine suites (CalendarSync*Tests) have 91 tests.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 calendar list | `listCalendars reads every page, prefers the user's name and skips deleted and free-busy calendars`, `GoogleEventMappingTests > a calendar without a name is named by its id, and only owners and writers may write`, `GoogleCalendarSyncTests > a first sync stores Google's calendar and events...` | `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.calendar.infra.google.*' --tests 'ch.sonensei.cockpit.calendar.sync.*' jacocoTestReport` | Pass |
| AC1 incremental sync with syncToken, 410 -> full resync | `the first fetch lists the calendar completely...`, `an incremental fetch sends the sync token...`, `an incremental run continues with the page token...`, `410 Gone for an expired sync token starts a complete listing`, `410 within an incremental run's pages also starts over completely, an unknown cursor too`, `410 during a complete listing is an error, not an endless restart`, `GoogleCalendarSyncTests > 410 for the stored sync token lists the calendar again and removes what Google no longer has` | same | Pass |
| AC1 / SPEC §6.2 fallback poll every 5 min | `the adapter serves Google sources and falls back to polling every 5 minutes` (new) | same | Pass |
| AC1 writes via API | `createEvent inserts with an id derived from the cockpit id...`, `a repeated insert whose id exists returns the stored event`, `updateEvent patches with If-Match and an explicit recurrence`, `deleteEvent deletes with If-Match...`, `412 on a write is a remote change, so is a 404 of the event`, `GoogleCalendarSyncTests > an event created in the cockpit is inserted at Google...`, mapping tests for request bodies | same | Pass |
| AC1 writes only with a Google connection | `a source linked to a connection of another provider fails without sending its token to Google`, `a source whose connection was removed fails as removed, not as unlinked, before calling Google`, `a source without connection or with a connection to reconnect fails before calling Google`, `every kind of token failure is a provider error naming what to do, never the token` | same | Pass |
| AC1 recurring instance edits | `an exception of a series is a patch of its instance...`, `an exception of an all-day series patches the instance named by its date`, `updating an exception patches its instance with If-Match, 410 there is a remote change`, `cancelling an edited occurrence keeps it cancelled in Google's answer`, `GoogleEventMappingTests > the status is read as tentative or cancelled, anything else is confirmed`, `GoogleEventMappingTests > a cancelled occurrence of an all-day series is keyed by its date` | same | Pass |
| AC1 'this and following' split | `GoogleCalendarSyncTests > changing this and following occurrences ends the series at Google and continues it as a new one with its exceptions` (Postgres via Testcontainers) | same | Pass |
| AC2 WireMock recordings incl. 410 and 412 | tests using `error-410-full-sync-required.json`, `error-412-condition-not-met.json` and the other recordings; `GoogleCalendarSyncTests > 412 from Google fails the edit with 409 and shows Google's version` | same | Pass |
| AC3 `./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.google.*'` passes | all 59 tests in the package (GoogleCalendarSyncTests with Postgres via Testcontainers) | same (a superset of the AC3 command) | Pass: 59 tests, 0 failures, 0 skipped |
| Sync-engine regression | CalendarSyncEdgeCaseTests 31, CalendarSyncOverrideTests 25, CalendarSyncTests 17, CalendarSyncUnitTests 18 | same | Pass: 91 tests, 0 failures |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.calendar.infra.google.*' --tests 'ch.sonensei.cockpit.calendar.sync.*' jacocoTestReport`

Result:

Pass. Two runs:

1. Before adding the test, on the committed code: BUILD SUCCESSFUL in 55s, 149 tests (Google 58 with GoogleCalendarAdapterTests 34, sync engine 91), 0 failures, 0 skipped. ktlint up to date (inputs unchanged since the parent's last run).
2. Final run with the new test: BUILD SUCCESSFUL in 1m 17s, 150 tests in 7 classes (Google 59, sync engine 91), 0 failures, 0 errors, 0 skipped (counted from `server/build/test-results/test/TEST-*.xml`, timestamps 11:17:18Z-11:18:14Z). `compileTestKotlin`, `runKtlintCheckOverTestSourceSet`, `test` and `jacocoTestReport` executed; ktlint of the main source set was up to date (main sources unchanged).

## Full test execution

Command:

`cd server && ./gradlew check`

Result:

Not run in this round. The parent marked it optional (about 7 min). Round 2 ran it on the code before 5029346c: BUILD SUCCESSFUL, 3118 tests, 0 failures. Since then, production changed only by a KDoc edit and the removal of a function nothing called (`grep` finds no other reference to `isInstance` in `server/src`). Tests changed by one removed assertion and the one test added here. The focused run above compiles all main and test sources (`compileKotlin`, `compileTestKotlin`) and runs ktlint on both source sets, so a dangling reference would have failed it.

## .taskcheck

Result:

Not run. `.taskcheck` is `sh scripts/check.sh` (server, web and Android). The parent runs it before `done`.

## Bugs found

None. No production defect was found in this round, and TW-1 and TW-2 from round 1 stay fixed (their tests pass in the final run).

## Unable to test

- Calls against the real Google Calendar API: not possible here (no real account or credentials, and tests must not use real data). AC2 asks for WireMock recordings, which is what all tests use. No follow-up needed for this task.
- The time between polls in a running scheduler is not tested end to end here. The new test pins the adapter's value. The engine's use of `pollInterval` is covered by the sync-engine suites with the fake adapter (`CalendarSyncTests` checks `pollIntervalSeconds`).
- CR-2 and the attendee mapping (reminders written as popup overrides, the full attendee list written on edit) are recorded as assumptions in `tasks/cal-google.md`. No test asserts other behavior.

## Summary

Tests added and all validation passed. One test was added for the SPEC §6.2 5-minute poll interval, which no test had checked before. 5029346c needed no further tests. No production defects were found. Task-scoped coverage is 100% of lines and methods and 97.2% of branches, and the remaining gaps are justified below.

## Coverage

Status: verified (fresh run on the final code plus the new test, exit 0).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. This task added all three files (`git diff --stat f65037de..HEAD` shows them as new), so whole-file coverage equals changed-line coverage here.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: cal-google; base f65037ded7c52736372b283ae7257257a35599ae; measured HEAD aab965924f48cc4efd89d59de40a31dc461e81c8 (same code as 5029346c named by the parent, see Scope); task commits cffbf776..aab96592; production files from `git diff --stat f65037de..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs), sha256: `GoogleCalendarAdapter.kt` b7192bcf07ca6bc92f1656a2812f5a5dde1e3d09f5a9be3939f85412bb2c0cf0, `GoogleCalendarClient.kt` 0cbd01e1c540e4eea00e54990ec63d5d3cc0851e01500d39a1447d78cd61d111, `GoogleEventMapping.kt` 30e412d8f9922b5a576da3747f431916646189255458eaef42503d26a2b875f6 (all three as in HEAD); `GoogleCalendarAdapterTests.kt` d885c484e9d7906572e6b612cfbb6f1c1b315c69ff23215cbf6306f0a9ea8465 (uncommitted, with the new test), `GoogleCalendarSyncTests.kt` 06d4bfec3983fec5445fd4abf7c1370a51e69118f919febb67151ad44069a45d, `GoogleEventMappingTests.kt` 3c3edc2b497e573881fe158bd19a82ebe884f5a7ff5e2145c0008517c3a9f268, `sync/FakeCalendarAdapter.kt` d58d1fb44c2f92fcc3235356462579b8568a8ab039606135307ec9ffcd0d6ff7 (as in HEAD).
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/google/GoogleCalendarAdapter.kt`, `GoogleCalendarClient.kt`, `GoogleEventMapping.kt`. Excluded: tests, WireMock recordings (test resources), `reviews/` and `tasks/` (documentation), `sync/FakeCalendarAdapter.kt` (test code). No production files were renamed or deleted.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin (`server/build.gradle.kts`, Gradle 9.8.1), OpenJDK 21.0.12.1; `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.calendar.infra.google.*' --tests 'ch.sonensei.cockpit.calendar.sync.*' jacocoTestReport` in `server/`.
- Run start/completion time and exit status: started after the snapshot at 2026-10-21T13:17:03+02:00, report written 2026-10-21T13:18:23+02:00, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`, sha256 bc32e2c8758ad33c0b532a74b6bd9c4a2516dafc006a3e86fecb13f9fbb01a04 (Git-ignored). Earlier reports: round 2 72a7a3b2..., and this round's first run ca952a99... (149 tests, before the new test).
- Evidence outputs are fresh and inputs stayed unchanged: yes. Gradle recompiled the test sources and re-ran `test` and `jacocoTestReport`, and the report hash changed. `sha256sum -c` of the snapshot after the run: all OK.
- Final run after latest test additions and production fixes: yes. It ran on the final production code (5029346c content, no later production change) with the new test.

Values below use covered/total (percentage), from the JaCoCo XML per-sourcefile counters in package `ch/sonensei/cockpit/calendar/infra/google`. Statements = JaCoCo INSTRUCTION, functions = METHOD.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/google/GoogleCalendarAdapter.kt` | 1087/1097 (99.1%) | 98/104 (94.2%) | 16/16 (100%) | 160/160 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/google/GoogleCalendarClient.kt` | 457/458 (99.8%) | 31/32 (96.9%) | 13/13 (100%) | 66/66 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/google/GoogleEventMapping.kt` | 1281/1281 (100%) | 110/110 (100%) | 26/26 (100%) | 161/161 (100%) |
| **Aggregate (summed counters, 3 of 3 scoped files, none missing or excluded)** | 2825/2836 (99.6%) | 239/246 (97.2%) | 55/55 (100%) | 387/387 (100%) |

There is no task-level coverage threshold (ARCHITECTURE.md "Coverage": coverage informs the test-writer and is not a gate). Compared with round 2, `GoogleEventMapping.kt` lost the `isInstance` function and its one missed branch. `GoogleCalendarAdapter.kt` now has all methods covered thanks to the new poll-interval test.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `GoogleCalendarAdapter.kt:52` (was 3 instructions, `getPollInterval`, before this round) | SPEC §6.2 fallback poll every 5 min | Test added: `the adapter serves Google sources and falls back to polling every 5 minutes`. Covered now |
| `GoogleCalendarAdapter.kt:121` (3 of 4 branches, 5 instructions missed) | Rethrowing a RuntimeException other than IllegalArgumentException/DateTimeException while reading a listed event | Justified gap: a defensive rethrow for unexpected programming errors. The mapping only throws the two handled types, and those are tested (`events without id, without start or with an invalid date cannot be read`; malformed events skipped in the adapter tests) |
| `GoogleCalendarAdapter.kt:274` (3 of 4 branches, 5 instructions missed) | The same defensive rethrow in `answer()` for write answers | Justified gap, same reason. The handled case is tested (`an answer to a write that cannot be read is a provider error`) |
| `GoogleCalendarClient.kt:48` (1 of 4 branches, 1 instruction missed) | One bound of `status in 200..299` (the tests never send a non-error status on one side of the range) | Justified gap: success and many error statuses (400, 403, 404, 409, 410, 412, 5xx) are tested |
