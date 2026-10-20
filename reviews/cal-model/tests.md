# Test Report

Date: 2026-10-20

Task: cal-model - Calendar data model

Scope: `git diff a661293219643f84f49b2709692db50452122eea..4255c5bb1e6a0956acbaa5a75c0f0efa048a32ca` (one task commit 4255c5b on `main`), plus the two new, uncommitted test files listed below.

Requirements:
- `tasks/cal-model.md` acceptance criteria and recorded assumptions
- SPEC.md §6.1 (sources, calendars, events), §6.2 (external_id, external_etag, sync_state), §4.3/§4.4 (resolver, search)
- docs/ARCHITECTURE.md "Coverage"

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/model/CalendarModelEdgeCaseTests.kt` (new)
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/model/CalendarItemsAndSearchEdgeCaseTests.kt` (new)

Existing tests (`CalendarModelTests.kt`, `CalendarItemsAndSearchTests.kt`) were not changed.

## Tests added or updated

Count: 25 (14 in CalendarModelEdgeCaseTests, 11 in CalendarItemsAndSearchEdgeCaseTests)

## Requirement coverage

Focused command (F): `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.model.*'` (Testcontainers postgres:16-alpine), 43 tests, 42 passed, 1 failed.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| calendar_source types LOCAL/GOOGLE/MICROSOFT/CALDAV/ICS_FEED, config object, credentials | `every source type of SPEC 6_1 is accepted by the database`; existing `a source keeps its config and encrypts its credentials`, `the database rejects an unknown source type and a non-object config` | F | Pass |
| calendar: color, visible, readOnly, default (single default), external id per source | `calendars are listed per source and external ids are unique per source only`, `a new calendar is visible, writable and not the default`; existing `a calendar keeps its flags and only one calendar is the default` | F | Pass |
| calendar_event: status/transparency/sync_state/attendees constraints | `the database rejects unknown status, transparency and sync state values and non-array attendees` | F | Pass |
| calendar_event: start/end with tz id, or all-day dates (exclusive end) | `the database rejects mixed timed and all-day columns`, `switching an event between timed and all-day clears the other form, and setAllDay defaults to one day`; existing timed/all-day round-trip tests | F | Pass |
| RRULE, EXDATEs | `exdates hold exactly one form and are unique per event`, `exdates can be added and removed on a saved series` | F | Pass |
| Override parent + recurrence id | `the database rejects inconsistent override columns`, `an all-day override stores its recurrence id as a date`, `an event overrides at most one occurrence and an override cannot be overridden`, `overrideOf clears an rrule set before` | F | Pass |
| external_id unique per calendar | `event external ids are unique per calendar only` | F | Pass |
| findCandidatesInRange boundaries | `range candidates exclude series and zero-length events at or after the end, and include long spans` | F | Pass |
| Resolver EVENT: subtitle in event zone, DST, date roll-over, repeats, override, deep link | `the subtitle uses the event's own zone and its daylight saving offset`, `the subtitle shows the local date when the zone moves the start past midnight`, `a timed series is marked as repeating and an override of an active series resolves without it`, `a soft-deleted event resolves again after it is restored` | F | Pass |
| Resolver EVENT: cancelled overrides are not resolved (recorded assumption) | `a cancelled override does not resolve` | F | **Fail (TW-1)** |
| Search hooks: cancelled/uncancelled overrides, override of deleted series, body, unindex calendar | `a cancelled single event still resolves and is indexed`, `indexing an override on its own while its series is deleted removes it from search`, `un-cancelling an override puts it back into search`, `cancelling an indexed override removes it from search`, `an event with only a location or no details is indexed by what it has`, `unindexing a calendar also removes series overrides, and an unknown calendar is a no-op` | F | Pass |
| Criterion 3: focused test command passes | all of the above | F | Fail (1 of 43, TW-1) |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.calendar.model.*'` and `cd server && ./gradlew ktlintCheck`

Result:

Fail: 43 tests, 1 failed (`CalendarItemsAndSearchEdgeCaseTests > a cancelled override does not resolve()`, TW-1). ktlintCheck: Pass (BUILD SUCCESSFUL).

## Full test execution

Command:

`cd server && ./gradlew test jacocoTestReport --continue`

Result:

Fail: 1044 tests completed, 1 failed (the same TW-1 test). No other failures.

## .taskcheck

Result:

Fail: `sh scripts/check.sh` -> server `:test` 1044 tests completed, 1 failed (TW-1), BUILD FAILED in 2m 38s.

## Bugs found

### Cancelled override of an active series still resolves as an item

Finding ID:

`TW-1`

Test:

`CalendarItemsAndSearchEdgeCaseTests.a cancelled override does not resolve`

Production source:

`server/src/main/kotlin/ch/sonensei/cockpit/calendar/model/EventItemResolver.kt:26`

Requirement:

Recorded assumption in `tasks/cal-model.md`: "cancelled overrides and overrides of a deleted series are neither indexed nor resolved". The indexer (`CalendarEventIndexer.apply`) implements the cancelled-override rule; the resolver does not.

Expected behavior:

`resolve(id)` returns null (and `exists(id)` false) for an override whose status is `CANCELLED`.

Actual behavior:

The resolver only checks that the event and (for an override) its master are active, so a cancelled override resolves to a `ResolvedItem` (`expected:<null> but was:<ResolvedItem(...)>` at line 120). Links to a cancelled occurrence therefore show as a live item, which is inconsistent with search.

Severity:

Medium

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Coverage

Status: partial-failed: fresh data from the full run, but the test task failed (TW-1), so `jacocoTestReport` was skipped by Gradle and the report was then generated from that run's fresh `test.exec` with `./gradlew jacocoTestReport -x test`. Diagnostic only; rerun after the fix.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately; every task production file is new in this task, so whole-file coverage equals changed-file coverage here.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: cal-model; base a661293219643f84f49b2709692db50452122eea (from parent); HEAD 4255c5bb1e6a0956acbaa5a75c0f0efa048a32ca; task commit 4255c5b; scope `git diff --name-only a661293..HEAD -- server/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 4255c5b, clean production tree; untracked test files sha256 `CalendarModelEdgeCaseTests.kt` d9320d0f33bdd634d740d95d6f5ff598232ae1f740cc875c113c11c6fd8c2f38, `CalendarItemsAndSearchEdgeCaseTests.kt` bd0575977df1d3f27c42182460ae3bdd70855c4c6d9e60339b685f7e504526a3; existing `CalendarModelTests.kt` 7070bf2b..., `CalendarItemsAndSearchTests.kt` a75a3f71... (unchanged). Same hashes before and after the runs.
- Exact included files: the 7 Kotlin files under `server/src/main/kotlin/ch/sonensei/cockpit/calendar/model/`. Excluded: `V202610201351__calendar_model.sql` (SQL, not instrumentable by JaCoCo; exercised via its constraint tests). `CalendarRepositories.kt` holds only Spring Data interfaces, so it has no JaCoCo counters (N/A).
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (default tool version, no `toolVersion` set); `./gradlew test jacocoTestReport --continue` then `./gradlew jacocoTestReport -x test`; working dir `server/`; full server test suite.
- Run start/completion time and exit status: 2026-10-20 16:05:29 to 16:08:19 +02:00, exit status failure (1 failed test); report generation 16:08:28, success.
- Generated report paths and report hashes/run identifier: `server/build/jacoco/test.exec` (16:08:18, sha256 c1fae49b...c440d), `server/build/reports/jacoco/test/jacocoTestReport.xml` (16:08:28, sha256 1da56175c957f0d1f35008deb3f52d8616cdb66d6d5d3e0be4c38477446bb8c1).
- Evidence outputs are fresh and inputs stayed unchanged: yes (exec written by this run; XML produced from it; no report existed before; hashes unchanged).
- Final run after latest test additions and production fixes: yes for the latest tests; no production fix exists yet (TW-1 open), so this describes the pre-fix revision 4255c5b.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `calendar/model/CalendarEvent.kt` | 444/451 (98.4%) | 31/32 (96.9%) | 61/63 (96.8%) | 80/81 (98.8%) |
| `calendar/model/CalendarEventIndexer.kt` | 119/119 (100%) | 14/14 (100%) | 4/4 (100%) | 16/16 (100%) |
| `calendar/model/EventItemResolver.kt` | 118/122 (96.7%) | 14/14 (100%) | 5/7 (71.4%) | 19/19 (100%) |
| `calendar/model/Calendar.kt` | 69/82 (84.1%) | N/A | 14/18 (77.8%) | 14/15 (93.3%) |
| `calendar/model/CalendarSource.kt` | 47/54 (87.0%) | N/A | 8/10 (80.0%) | 10/11 (90.9%) |
| `calendar/model/CalendarEnums.kt` | 149/149 (100%) | N/A | 5/5 (100%) | 19/19 (100%) |
| `calendar/model/CalendarRepositories.kt` | N/A | N/A | N/A | N/A |
| **Aggregate (summed counters, 6 measured files; repositories N/A, SQL migration excluded)** | 946/977 (96.8%) | 59/60 (98.3%) | 97/107 (90.7%) | 158/161 (98.1%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CalendarEvent.kt:39`, `Calendar.kt:20`, `CalendarSource.kt:26` (7 instr. each) | Kotlin synthetic default-argument constructor variant | Non-material, generated code |
| `CalendarEvent.kt:166` (1 branch: master is an override) | Overriding an override | Unreachable through the entity API (an override's rrule is cleared, so `isRecurring` short-circuits); behavior tested by `an event overrides at most one occurrence and an override cannot be overridden` |
| `Calendar.kt:36,39` (getters of externalId / externalEtag) | Trivial accessors; columns are tested via repository lookup and round-trip | Non-material |
| `EventItemResolver.kt:44-45` (2 synthetic companion accessors) | Generated code | Non-material |
| `EventItemResolver.kt:26` | Cancelled override still resolves | Covered; test fails, TW-1 |

## Unable to test

- Nothing in the task scope. The SQL migration has no line coverage (JaCoCo instruments JVM bytecode only); its constraints are covered by the DB rejection tests.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion (TW-1: `EventItemResolver` resolves cancelled overrides).
