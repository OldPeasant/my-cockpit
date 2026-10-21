# Test Report

Date: 2026-10-21

Task: cal-google - Google Calendar adapter (SPEC.md §6.2)

Scope: `git diff 04c871d6f9a1996de04712dfb6f11ccf850c2e0c..31f638f96e419a6f5308bed441f0a5e09c0c0eeb` (task commits 66e272e2, 54386289, 052654e6, 31f638f9 on `main`), limited to `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/google`, its tests and WireMock recordings. The new tests are uncommitted working-tree changes (see Coverage evidence for hashes).

Requirements:
- `tasks/cal-google.md` acceptance criteria 1-3
- SPEC.md §6.2 (via the adapter's KDoc and the sync engine contract in `calendar/sync/CalendarProviderAdapter.kt`)
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, AGENTS.md
- Parent agent instructions (gap list: calendarList paging, 409 retry, instance delete / 404, 403 rate limit vs denial, malformed events, all-day EXDATE, unknown cursor)
- `reviews/cal-google/review.md` finding CR-1, used to write a reproducing test

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/google/GoogleCalendarAdapterTests.kt` (12 tests added, no existing test changed)
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/google/GoogleEventMappingTests.kt` (new, 18 tests)

## Tests added or updated

Count: 30 added (GoogleCalendarAdapterTests 20 -> 32, GoogleEventMappingTests 0 -> 18; GoogleCalendarSyncTests unchanged at 5). Total in scope: 55.

Gaps the parent listed that the existing tests already covered: calendarList paging, deleted and free/busy calendars (`listCalendars reads every page...`); 409 retry with a cockpit-derived id; instance delete, 404 and 410 on delete; 403 rate limit vs. denial on calendarList; the event without times being skipped; unknown cursor (`410 within an incremental run's pages...`).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 calendar list | existing `listCalendars reads every page...`; new `a calendar without a name is named by its id, and only owners and writers may write` | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.google.*'` | Pass |
| AC1 incremental sync with syncToken, 410 -> full resync | existing 410/paging tests; new `sync and page tokens with reserved characters reach Google unchanged`, `a rate limit while fetching events is a provider error to retry later`, `a changed series without cancelled occurrences is kept as sent, and a last page without sync token leaves no cursor`, `a failing lookup of a series' cancelled occurrences fails the fetch`, `a changed all-day series gets the dates of its own cancelled occurrences only` | same | Pass |
| AC1 writes via API | new `409 for an insert without a cockpit-derived id is an error, not a lookup`, `a repeated insert whose stored copy cannot be fetched is a provider error`, `a delete Google rejects is a provider error`, `every kind of token failure is a provider error naming what to do, never the token`; mapping tests for bodies (zones, all-day EXDATE line, attendees, reminders, status) | same | Pass |
| AC1 recurring instance edits | new `updating an exception patches its instance with If-Match, 410 there is a remote change`, `an exception of an occurrence Google deleted (410) is a provider error`, mapping tests for EXDATE parsing (TZID, floating, VALUE=DATE, all-day, malformed), instance keys and ids | same | Pass |
| AC1 recurring instance edits: deleting an edited occurrence | new `cancelling an edited occurrence keeps it cancelled in Google's answer` | same | **Fail** (TW-1) |
| AC1 'this and following' split | existing `changing this and following occurrences ends the series...` (GoogleCalendarSyncTests, Postgres via Testcontainers) | same | Pass |
| AC2 WireMock recordings incl. 410 and 412 | existing tests using `error-410-*.json`, `error-412-*.json`; new tests use WireMock with recorded-style bodies | same | Pass |
| AC3 the scoped Gradle command passes | all 55 tests | same | **Fail**: 54 passed, 1 failed (TW-1) |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.calendar.infra.google.*' jacocoTestReport --continue`

Result:

Fail: 55 tests, 54 passed, 1 failed (`GoogleCalendarAdapterTests > cancelling an edited occurrence keeps it cancelled in Google's answer()`: expected CANCELLED but was CONFIRMED). ktlint passed. The same command without the TW-1 test (previous snapshot, 54 tests) passed completely: BUILD SUCCESSFUL.

## Full test execution

Command:

`cd server && ./gradlew check`

Result:

Fail: 3114 tests, 3 failed. All 3 are outside this test scope, in the cal-sync-engine tests. They fail because this task registers a Google adapter (see TW-2):
- `CalendarSyncEdgeCaseTests > GET sources shows a source without adapter as not syncable, without status` (expected syncable false, was true)
- `CalendarSyncEdgeCaseTests > syncDue never syncs local sources or types without adapter` (expected 1, was 2)
- `CalendarSyncTests > POST sync of a local, unsupported or unknown source fails` (expected 409, was 200)

This run was made before the TW-1 test was added. The Google tests all passed in it.

## .taskcheck

Result:

Not run. `.taskcheck` (`sh scripts/check.sh`) runs `cd server && ./gradlew --quiet check` with `set -e`. That command fails as shown above (TW-2) and now also on TW-1, so `.taskcheck` cannot pass until both are fixed. The web and Android steps were not attempted.

## Bugs found

### Google's answer with status "cancelled" is read as CONFIRMED

Finding ID:

`TW-1` (reproduces code-reviewer CR-1)

Test:

`GoogleCalendarAdapterTests > cancelling an edited occurrence keeps it cancelled in Google's answer`

Production source:

`server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/google/GoogleEventMapping.kt:74` (`status = if (node.text("status") == "tentative") TENTATIVE else CONFIRMED`), used by `GoogleCalendarAdapter.answer` (`GoogleCalendarAdapter.kt:269`)

Requirement:

AC1 "recurring instance edits". When the user deletes an occurrence that already has an override, `CalendarEventService.deleteOccurrence` (`CalendarEventService.kt:249-251`) sets the override CANCELLED. The write-through sends that as `updateEvent`, and the engine copies the adapter's returned event back onto the local override.

Expected behavior:

The PATCH sends `"status": "cancelled"` (this works). Google answers with the cancelled instance, and `updateEvent` returns a `RemoteEvent` with `status = CANCELLED`, so the occurrence stays removed locally.

Actual behavior:

The returned event has `status = CONFIRMED`. Per CR-1, the write-through then stores the override as CONFIRMED and SYNCED, so the deleted occurrence shows up again until the next sync.

Severity:

High

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

### Existing sync-engine tests use GOOGLE as "a type without adapter"

Finding ID:

`TW-2`

Test:

`CalendarSyncEdgeCaseTests > GET sources shows a source without adapter as not syncable, without status`, `CalendarSyncEdgeCaseTests > syncDue never syncs local sources or types without adapter`, `CalendarSyncTests > POST sync of a local, unsupported or unknown source fails`

Production source:

`server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/google/GoogleCalendarAdapter.kt:41` (`@Component`, which registers `CalendarSourceType.GOOGLE`). The failing assertions are at `server/src/test/kotlin/ch/sonensei/cockpit/calendar/sync/CalendarSyncEdgeCaseTests.kt:352,368` and `CalendarSyncTests.kt:307`.

Requirement:

AC3 and `.taskcheck` (`./gradlew check`) must pass. The behavior these tests check (a type without adapter is not syncable, is skipped by `syncDue`, and its POST sync returns 409) is still correct. Only their choice of GOOGLE as that type is out of date.

Expected behavior:

`./gradlew check` passes with the Google adapter registered.

Actual behavior:

The 3 tests fail because GOOGLE now has an adapter. They are outside this helper's edit scope and the task's `touches`, so I did not change them.

Severity:

High (blocks `.taskcheck` and `done`)

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.` The required change is in these three tests, not in production code: switch them to a source type that has no adapter (for example `CALDAV` or `MICROSOFT`, after checking that no other task has added one). Keep what they assert. Do not delete or skip them.

## Coverage

Status: verified for the snapshot before the TW-1 test was added (all 54 tests passed). For the final snapshot (55 tests), the coverage is partial-failed and only diagnostic, because its test run had the TW-1 failure. Production code is identical in both snapshots, and the counters are identical.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. All three files were added by this task, so whole-file coverage equals changed-line coverage here.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: cal-google; base 04c871d6f9a1996de04712dfb6f11ccf850c2e0c; HEAD 31f638f96e419a6f5308bed441f0a5e09c0c0eeb; commits 66e272e2, 54386289, 052654e6, 31f638f9 as given by the parent; files from `git diff --stat <base>..HEAD`
- Source/test snapshot identifier (including uncommitted inputs): production files unchanged from HEAD, sha256 `GoogleCalendarAdapter.kt` 418c5eb5…6c214, `GoogleCalendarClient.kt` 0cbd01e1…1d111, `GoogleEventMapping.kt` 31d80f06…a6a96. Verified run: `GoogleCalendarAdapterTests.kt` fb8e1bbf…c885f, `GoogleEventMappingTests.kt` 305cfbee…a4ba2, `GoogleCalendarSyncTests.kt` 06d4bfec…a45d (committed). Final diagnostic run: `GoogleCalendarAdapterTests.kt` e07bb232…a094, other files the same.
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/google/{GoogleCalendarAdapter,GoogleCalendarClient,GoogleEventMapping}.kt`. Excluded: tests and JSON test recordings (not production). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin (default tool version of Gradle 9.8.1); `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.calendar.infra.google.*' jacocoTestReport` in `server/`; tests selected: `ch.sonensei.cockpit.calendar.infra.google.*`
- Run start/completion time and exit status: verified run 2026-10-21 12:04:57-12:05:25 +02:00 (from JUnit timestamps and the report mtime), exit 0 (BUILD SUCCESSFUL), 54/54 passed. Diagnostic run: tests 2026-10-21 ~12:10:59 +02:00, exit 1 (1 failure); report generated with `./gradlew jacocoTestReport -x test` from that run's `test.exec` at 12:11:13.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`; verified run sha256 ff21b5fbccaa483d691f9d628744fe04feec9d8657ea40e60b240cfc3ae01e; diagnostic run sha256 54f3a21efcaad0a38aeb461d2dc70d0953767577f8cbe6d271b5381ab6ba6e70
- Evidence outputs are fresh and inputs stayed unchanged: yes. Gradle reran the tests because the test sources had changed. I took hashes before and after, and the files did not change during either run.
- Final run after latest test additions and production fixes: no for a passing run. The final snapshot's run fails on TW-1, so its coverage is only diagnostic. No production fixes have been made yet, and coverage must be measured again after the fixer's change.

Values below use covered/total (percentage) from the verified run. The diagnostic run gave the same counters.

| Changed file | Statements (JaCoCo instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/google/GoogleCalendarAdapter.kt` | 1054/1067 (98.8%) | 92/98 (93.9%) | 15/16 (93.8%) | 155/155 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/google/GoogleCalendarClient.kt` | 457/458 (99.8%) | 31/32 (96.9%) | 13/13 (100%) | 66/66 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/google/GoogleEventMapping.kt` | 1281/1282 (99.9%) | 109/110 (99.1%) | 27/27 (100%) | 159/159 (100%) |
| **Aggregate (summed counters, all 3 scoped files measured, none missing)** | 2792/2807 (99.5%) | 232/240 (96.7%) | 55/56 (98.2%) | 380/380 (100%) |

Before the new tests, the same files had: Adapter branches 88/98 and Mapping branches 108/110 (first run of this session, with the earlier test additions only partly in place).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `GoogleCalendarAdapter.kt:51` (`pollInterval` getter) | constant | Left: trivial |
| `GoogleCalendarAdapter.kt:120`, `:273` (rethrow of RuntimeExceptions other than IllegalArgumentException/DateTimeException) | defensive path for programming errors | Left: not reachable through valid or malformed Google JSON |
| `GoogleCalendarClient.kt:48` (`status < 200` side of `ok`) | Google never answers 1xx as a final status | Left: not meaningful |
| `GoogleEventMapping.kt:41` (`isInstance` false branch) | `isInstance` is not called by production code | Left. Note for the parent: `isInstance` is dead production code |
| `GoogleEventMapping.kt:74` | status "cancelled" in a write answer | TW-1 (failing test added) |

## Unable to test

- Real Google API behavior (for example, whether Google accepts a recurring event whose `timeZone` is null because its zone is a fixed offset other than UTC; Google documents `timeZone` as required for recurring events). WireMock cannot show this. The mapping test only pins down what the adapter sends. Follow-up only if fixed-offset zones can reach a series.
- CR-2 (reminders sent as popups) and CR-3 (connection provider not checked) are design and security choices for the parent to decide. I did not add a test for them, because neither the task nor the SPEC states the required behavior.
- `.taskcheck` web and Android steps were not run, because the server step already fails (TW-2).

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion. TW-1 (High) is a production fix in `GoogleEventMapping` / `answer`. TW-2 (High) is a change to three cal-sync-engine tests that still treat GOOGLE as a type without an adapter. Until both are fixed, `./gradlew check` and `.taskcheck` fail. Task-scoped coverage is high (lines 380/380, branches 232/240), and the remaining gaps are justified above.
