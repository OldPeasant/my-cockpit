# Test Report

Date: 2026-10-20

Task: cal-recurrence - Recurrence expansion

Scope: base `e5c45b44d3144647824cd28217b67d3620da2aed`..HEAD `ede9e8b0dc544c339386e3d116031c84bd548219` on `main`. The parent named task commit `4b0e570f8ed0fff1c381faf8246fda720c99d453`; it is not an ancestor of HEAD (rebased), and `git diff 4b0e570 ede9e8b` shows only `tasks/*.md` differences, so `ede9e8b` ("cal-recurrence: OccurrenceExpander with ical4j, tests") is the same implementation. The other commits in the range are `tasks.py` bookkeeping (assume/verify/heartbeat/confirm) of this and other tasks and touch only `tasks/*.md`. Production scope: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/recurrence/OccurrenceExpander.kt`. Uncommitted at measurement: the new test file below only.

Requirements:
- `tasks/cal-recurrence.md` acceptance criteria and its three recorded assumptions
- `SPEC.md` §6.1 (RRULE + EXDATE + overrides, time-zone aware or all-day)
- `docs/ARCHITECTURE.md` "Coverage"; `docs/QUALITY.md`
- KDoc of `CalendarEventRepository.findCandidatesInRange` (candidate contract the expander consumes)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/recurrence/OccurrenceExpanderEdgeCaseTests.kt` (new)

The existing `OccurrenceExpanderTests.kt` was not modified.

## Tests added or updated

Count: 25 (all in `OccurrenceExpanderEdgeCaseTests`)

## Requirement coverage

Command for every row: `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.recurrence.*'` (38 tests: 13 existing + 25 new).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Expands RRULE in the event's zone into instants for [from, to) | existing `weekly series keeps its wall-clock time ...`; new `series in another zone keeps its own wall-clock time while the user's zone differs`, `long-running daily series is expanded correctly far from its start` | see above | Pass |
| Weekly across DST start (Europe/Zurich) | existing DST start test; new `nonexistent local time at DST start is shifted forward for that week only`, `occurrence spanning the DST start keeps the master's exact duration` | see above | Pass |
| Weekly across DST end (Europe/Zurich) | existing DST end test (ambiguous 02:30 -> summer offset) | see above | Pass |
| All-day events | existing all-day tests; new `all-day range days follow the user's zone, not UTC`, `range ending one minute after midnight in the user's zone touches the next day`, `multi-day all-day occurrence that started before the range is included`, `all-day override is shown on its new date`, `all-day series starting on or after the last day of the range has no occurrences`, `all-day and timed occurrences on the same day are sorted by the start of the day in the user's zone` | see above | Pass |
| Moved occurrences (overrides) | existing moved tests; new `override is shown even when its recurrence id is also excluded or no longer matches the rule`, `expandSeries ignores overrides of another series`, `override whose master is not among the candidates is shown standalone`, `deleted override no longer replaces its occurrence` | see above | Pass |
| Cancelled occurrences | existing cancelled test; new `cancelled override is never shown, also when its master is not among the candidates` | see above | **Fail (TW-1)** |
| EXDATE | existing tests; new `excluded occurrences still count toward COUNT`, `override is shown even when its recurrence id is also excluded ...` | see above | Pass |
| COUNT / UNTIL | existing tests; new `exhausted COUNT series yields nothing far after its end`, `excluded occurrences still count toward COUNT` | see above | Pass |
| Monthly by weekday | existing `monthly by weekday` | see above | Pass |
| Range semantics [from, to), zero-duration events | new `zero-duration occurrence belongs to the range it starts in`, `zero-duration single event at the range boundaries`, `occurrence ending exactly at the range start or starting exactly at its end is excluded`, `occurrences longer than the interval overlap and are all returned`, `the range must end after it starts` | see above | Pass |
| Deleted events ignored, deterministic order | new `expand with no events or only deleted events yields nothing`, `expandSeries rejects an override and returns nothing for a deleted master`, `occurrences starting at the same moment are ordered by event id` | see above | Pass |
| Criterion 3: the recurrence test command passes | whole package | see above | **Fail** (1 of 38, TW-1). No persistence is involved (the expander is pure), so no Testcontainers test applies. |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.recurrence.*'`

Result:

Fail: 38 tests completed, 1 failed (`OccurrenceExpanderEdgeCaseTests > cancelled override is never shown, also when its master is not among the candidates`, TW-1). `cd server && ./gradlew ktlintCheck` -> BUILD SUCCESSFUL.

## Full test execution

Command:

`cd server && ./gradlew test`

Result:

Fail: 1270 tests completed, 1 failed (run before the last two tests were added); the only failing class was `OccurrenceExpanderEdgeCaseTests` (TW-1). No other server test failed. Web and Android are untouched by this task and were not run separately.

## .taskcheck

Result:

Fail: `sh scripts/check.sh` -> TFP gate tests OK (18 tests); `server: ktlint + tests` -> 1272 tests completed, 1 failed (TW-1 only); the script stops there (`set -eu`), so the web and Android steps did not run. Expected to pass at the server step once TW-1 is fixed.

## Bugs found

### Cancelled override of a series whose master is not among the candidates is shown as an event

Finding ID:

`TW-1`

Test:

`OccurrenceExpanderEdgeCaseTests.cancelled override is never shown, also when its master is not among the candidates`

Production source:

`server/src/main/kotlin/ch/sonensei/cockpit/calendar/recurrence/OccurrenceExpander.kt:88-93` (orphan handling in `expand`)

Requirement:

Acceptance criterion 1/2 (cancelled occurrences are removed); class KDoc: "a [EventStatus.CANCELLED] override removes it"; `CalendarEvent` KDoc: "a CANCELLED override removes the occurrence".

Expected behavior:

A CANCELLED override never produces an occurrence. `expandSeries` already filters `status != CANCELLED`; orphan overrides (master not in `events`) should be filtered the same way.

Actual behavior:

The orphan path calls `asOccurrence` on every live override without checking the status. Reproduction: daily master starting 2026-06-10 09:00 Zurich; an override of its 2026-06-10 occurrence moved to 2026-06-05 09:00 and set to CANCELLED. `expand(listOf(override), 2026-06-01..2026-06-08)` returns one occurrence (`seriesId=null`, start `2026-06-05T07:00:00Z`). `findCandidatesInRange` returns exactly this input in real use: the master starts after `to`, so it is not a candidate, but all overrides of the calendar are. With the master in the input, the same override is correctly hidden, so the result depends on whether the master happens to be a candidate.

Severity:

Medium (a cancelled meeting shows up in the calendar; only for overrides whose own times are before the series start, e.g. a moved and then cancelled first occurrence, or a provider that stores cancelled exceptions with the moved time).

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Coverage

Status: partial-failed. The coverage run is fresh and complete, but its test command exited nonzero because of TW-1. The failing test runs the code under test completely (it fails at its last assertion), so the counters are representative. Rerun after the fix.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. The file is new in this task, so all of its executable lines are changed lines, and whole-file line coverage equals changed-line coverage here.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `cal-recurrence`; base `e5c45b44d3144647824cd28217b67d3620da2aed` (from the parent); HEAD `ede9e8b0dc544c339386e3d116031c84bd548219`. Task-owned code commit `ede9e8b` (a rebased equivalent of `4b0e570`, which the parent named). File scope from the parent, cross-checked with `git diff --name-only e5c45b44..HEAD -- server/src/main web/src android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): sha256 `OccurrenceExpander.kt` 7022b4cf7a250252a67937e8f7d52eab95d207f9d81d2c07dc7e070e17e6af93; `OccurrenceExpanderTests.kt` 88ba4880d675f9a1b37ac6598ad9b4570ddc44fca748cd0ccf43c78e3cc2c96f; `OccurrenceExpanderEdgeCaseTests.kt` (uncommitted) ab876b49ef97062eacfac5df8883487e5fadb58ad6671819542a8463b6145e78. The hashes were the same before and after the run.
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/calendar/recurrence/OccurrenceExpander.kt` (3 classes: `OccurrenceRange`, `Occurrence`, `OccurrenceExpander`). No renamed, deleted or excluded production files. `tasks/*.md` are not production code.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin (Gradle 9.8.1, default JaCoCo tool version, no `toolVersion` override), Kotlin 2.4.10. `./gradlew test --tests 'ch.sonensei.cockpit.calendar.recurrence.*'`, then `./gradlew jacocoTestReport -x test` (the report task is skipped after a failed test task, so it was run on the fresh exec file). Working directory `server/`. Selection: the recurrence package (38 tests).
- Run start/completion time and exit status: 2026-10-20 17:16:26 +02:00 to 17:17:01 +02:00. The test task exited nonzero (1 failure, TW-1). `jacocoTestReport` succeeded.
- Generated report paths and report hashes/run identifier: `server/build/jacoco/test.exec` (written 17:16:54, sha256 d30acb53f6cbd0318273d5e0a096b569716713157ab892acc95a1b8668209929); `server/build/reports/jacoco/test/jacocoTestReport.xml` (written 17:17:00, sha256 f5ab1fc6316125a30024b1ac419be5e5e90f2dd1a5321958b984459958fae074).
- Evidence outputs are fresh and inputs stayed unchanged: yes. The exec file and XML were rewritten by this run, and the input hashes match the snapshot.
- Final run after latest test additions and production fixes: yes for the latest test additions. No production fix exists yet (TW-1 is open). The numbers describe the pre-fix revision `ede9e8b`.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements (JaCoCo instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/recurrence/OccurrenceExpander.kt` | 810/846 (95.7%) | 77/92 (83.7%) | 28/28 (100%) | 89/90 (98.9%) |

The aggregate equals the single file above. No files are missing or unmeasured. The earlier full-suite run (before the last two tests, 1270 tests) gave 798/846 instructions, 76/92 branches, 26/28 methods and 89/90 lines. The two tests added after it covered the sort tie-break lambdas and the "all-day series starts after the range" branch.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `OccurrenceExpander.kt:110, 135, 136, 143, 144, 179, 180, 182, 183` (null branches of `startDate`/`endDate`/`startAt`/`endAt`) | Defensive handling of inconsistent rows. `setTimed`/`setAllDay` always set both values, and the fields have protected setters. | Not reachable through the entity API without reflection or a corrupt DB row. Justified gap. |
| `OccurrenceExpander.kt:161, 164, 168` (`seriesOccurrence` type-mismatch `else null`) | Defensive. `originalStarts` always yields an id of the master's own kind. | Not reachable. Justified gap. |
| `OccurrenceExpander.kt:204` (`timeZone == null` -> UTC) | Fallback for a timed row without a zone (recorded assumption: "UTC if none"). | `setTimed` always sets the zone, so the fallback is only reachable from legacy or corrupt rows. Not tested here. A persistence-level test could cover it if needed. |
| `OccurrenceExpander.kt:93` (one branch: `asOccurrence` returned null for an orphan) | Same inconsistent-row defence. | Justified gap. The orphan CANCELLED gap is TW-1, which is a missing check, not a missing branch. |

## Unable to test

- Inconsistent persisted rows (a null start/end, a timed row without `time_zone`): the entity API cannot create them. A repository-level test would need raw SQL. Optional follow-up, low value.
- Possible point for the parent to decide (not a test failure): `CalendarEventRepository.findCandidatesInRange` already excludes overrides of deleted masters, so the expander's "orphan" rule only applies to masters that start after the range. If a caller ever passes a deleted master together with its live overrides, `expand` shows those overrides as standalone events. This matches the recorded assumption ("overrides whose master is not among the candidates are shown standalone") and is therefore not reported as a defect.
- Whether an RRULE whose DTSTART does not match the rule (e.g. DTSTART on a Wednesday with `BYDAY=MO;COUNT=n`) should count DTSTART toward COUNT is left open by RFC 5545 ("undefined"), so no test was added.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion (TW-1: a cancelled orphan override is shown). All other acceptance criteria pass: DST start/end (including the nonexistent 02:30 local time), all-day in non-UTC user zones, moved/cancelled/deleted overrides, COUNT/UNTIL, monthly by weekday and range boundaries.
