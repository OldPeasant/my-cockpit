# Test Report

Date: 2026-10-20

Task: cal-recurrence - Recurrence expansion (second pass, after fixes in `reviews/cal-recurrence/fixes.md`)

Scope: task commits on base `5537ffa91f44c8f44d5311076731fb7f738c87f8` (heartbeat commit, supplied by the parent): `67d8680e`, `53b64bc2`, `a59471a4`, `7a15f449` (HEAD `7a15f449df126fd9fde0003090e8b05a3ef05d60`), branch `main`, plus this pass's uncommitted test addition in `OccurrenceExpanderEdgeCaseTests.kt`. Production change in scope: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/recurrence/OccurrenceExpander.kt` (new file). `git diff --stat 5537ffa9..HEAD` lists only that file, the two test files in `server/src/test/kotlin/ch/sonensei/cockpit/calendar/recurrence/` and `reviews/cal-recurrence/*`, so no unrelated work is included.

Requirements:
- `tasks/cal-recurrence.md` acceptance criteria and recorded assumptions (pure expander, user-zone all-day overlap, earlier offset at DST end and forward shift at DST start, override precedence and standalone orphans, first-occurrence fallback for an invalid RRULE, date-only UNTIL in the event's zone)
- SPEC.md §6.1 (via the task), `docs/ARCHITECTURE.md` "Coverage", AGENTS.md
- `reviews/cal-recurrence/fixes.md` (TW-1, CR-1, CR-2, CR-3) and the regression tests of `7a15f449` (not duplicated)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/recurrence/OccurrenceExpanderEdgeCaseTests.kt`

## Tests added or updated

Count: 3 added, 0 changed. Total in scope: 50 (13 in `OccurrenceExpanderTests`, 37 in `OccurrenceExpanderEdgeCaseTests`).

- `an EXDATE or override at the nonexistent DST-start time matches the shifted occurrence`: mirrors the DST-end EXDATE regression test for the DST-start gap. An EXDATE or override recurrence id for 2026-03-29 02:30 Zurich, which does not exist, resolves to 01:30Z. It must remove or replace the shifted 03:30 occurrence.
- `a UTC UNTIL equal to an occurrence after the DST change includes it`: a weekly 09:00 Zurich series with `UNTIL=20260330T070000Z` includes the 30 March occurrence, which is in summer time. `...T065959Z` excludes it. This checks that UNTIL is inclusive and that UTC UNTIL interacts correctly with the wall-clock shift.
- `a cancelled all-day override removes that date of the series`: covers a cancelled occurrence of an all-day series, both with and without its master among the candidates. Before this pass, cancellation was tested only for timed series.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: `OccurrenceExpander` (ical4j) expands RRULE/EXDATE/overrides in the event's zone into instants for [from, to) | `OccurrenceExpanderTests` (all 13), `OccurrenceExpanderEdgeCaseTests` range-boundary, zone, override, invalid-rule, UNTIL and DST tests, incl. the 3 new ones | `cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.calendar.recurrence.*' jacocoTestReport` | Pass |
| AC2: weekly across DST start/end (Europe/Zurich) | `weekly series keeps its wall-clock time across DST start/end in Europe-Zurich`, `nonexistent local time at DST start ...`, `occurrence spanning the DST start ...`, `ambiguous time at DST end uses the earlier offset ...`, `a series starting at the later of two ambiguous times ...`, `an EXDATE or override at the ambiguous DST-end time ...`, new `an EXDATE or override at the nonexistent DST-start time ...`, new `a UTC UNTIL equal to an occurrence after the DST change ...` | same | Pass |
| AC2: all-day events | `all-day series recurs by date`, `all-day single event and exdates of an all-day series`, `all-day range days follow the user's zone, not UTC`, `range ending one minute after midnight ...`, `multi-day all-day occurrence ...`, `all-day override is shown on its new date`, `all-day series starting on or after the last day ...`, `an all-day series with an invalid rule ...`, new `a cancelled all-day override removes that date of the series` | same | Pass |
| AC2: moved and cancelled occurrences | `moved occurrence is shown at its new time, cancelled and excluded ones are removed`, `occurrence moved into or out of the range ...`, `deleted override no longer replaces its occurrence`, `override is shown even when its recurrence id is also excluded ...`, `override whose master is not among the candidates ...`, `cancelled override is never shown, also when its master is not among the candidates`, `overrides still apply to a series with an invalid rule`, new cancelled all-day override test | same | Pass |
| AC2: COUNT/UNTIL | `COUNT limits the series ...`, `UNTIL ends the series inclusively`, `excluded occurrences still count toward COUNT`, `exhausted COUNT series ...`, `a date-only UNTIL on a timed series includes that whole day ...`, `a date-only UNTIL ... in the event's zone, not in UTC`, new `a UTC UNTIL equal to an occurrence after the DST change includes it` | same | Pass |
| AC2: monthly by weekday | `monthly by weekday` (2TU and -1FR with COUNT; the 2TU series crosses the DST start) | same | Pass |
| AC3: `./gradlew test --tests 'ch.sonensei.cockpit.calendar.recurrence.*'` passes (Testcontainers where persistence is involved) | All 50 tests in the package. The expander is pure (confirmed assumption), so no persistence or Testcontainers is involved. | `cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.calendar.recurrence.*' jacocoTestReport` | Pass (50 tests, 0 failures, 0 skipped) |
| Assumption: invalid RRULE keeps DTSTART and logs WARN with the event id only | `a series with an invalid rule keeps its first occurrence ...`, `an all-day series with an invalid rule ...`, `an invalid rule is logged at WARN with the event id only ...` | same | Pass |

## Focused test execution

Command:

`cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.calendar.recurrence.*' jacocoTestReport`

`--rerun` was needed: without it Gradle reported `:test UP-TO-DATE` and would have reused cached results.

Result:

Pass: BUILD SUCCESSFUL, 50 tests, 0 failures, 0 errors, 0 skipped (`OccurrenceExpanderTests` 13, `OccurrenceExpanderEdgeCaseTests` 37).

## Full test execution

Command:

`cd server && ./gradlew check` (ktlint and all server tests, including Testcontainers suites)

Result:

Pass: BUILD SUCCESSFUL in 4m 51s. JUnit XML totals: 1297 tests, 0 failures, 0 errors, 0 skipped, in 116 suites.

## .taskcheck

Result:

Pass: `sh scripts/check.sh` printed "all checks passed" (TFP gate, server, web, android), 17:39:51 to 17:41:25 +02:00.

## Bugs found

None. The final revision behaved as required in every new test, and no existing test failed.

Observation, not a bug: `expand` shows a live override standalone when its master was passed in but is soft-deleted. `isDeleted` filters the master out before the master ids are collected. This matches the documented contract that "an override whose master is missing is shown on its own". In practice the candidate query does not return deleted masters, so the expander cannot tell the two cases apart. Whether deleting a series also deletes its overrides belongs to the event write/sync tasks, not to this task. The parent may decide whether it needs an assumption.

## Coverage

Status: verified (fresh run on the final revision, including the tests added in this pass).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: the task added `OccurrenceExpander.kt` as a new file, so every executable line in it is an added line. Changed-line coverage therefore equals the whole-file LINE counter: 103/104 (99.0%). Changed-branch coverage is not inferred separately: it equals the whole-file BRANCH counter because the whole file is new.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `cal-recurrence`. Base `5537ffa91f44c8f44d5311076731fb7f738c87f8`, measured HEAD `7a15f449df126fd9fde0003090e8b05a3ef05d60`. Task commits `67d8680e`, `53b64bc2`, `a59471a4` and `7a15f449` were supplied by the parent worker and cross-checked with `git log`/`git diff --stat 5537ffa9..HEAD` and with the task's `touches`.
- Source/test snapshot identifier, including uncommitted inputs: HEAD `7a15f449` plus an uncommitted `OccurrenceExpanderEdgeCaseTests.kt`. SHA-256 of the files measured:
  - `OccurrenceExpander.kt`: `2c97034e1322bc7a3ff858822a222a45849be18dffafd874e2f164ebff8ac1c9` (unchanged from HEAD)
  - `OccurrenceExpanderEdgeCaseTests.kt`: `55bbb647ab510208b3cbe9d1ca1cd41fe85ca2766f2151350fb772491747613e` (uncommitted, this pass)
  - `OccurrenceExpanderTests.kt`: `88ba4880d675f9a1b37ac6598ad9b4570ddc44fca748cd0ccf43c78e3cc2c96f`
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/calendar/recurrence/OccurrenceExpander.kt`. No renames or deletions. Excluded: the test files and `reviews/cal-recurrence/*` (documentation).
- Provider/version, command, working directory, and test selection: JaCoCo 0.8.15 through the Gradle `jacoco` plugin. Command `./gradlew test --rerun --tests 'ch.sonensei.cockpit.calendar.recurrence.*' jacocoTestReport`, run in `server/`. Test selection: package `ch.sonensei.cockpit.calendar.recurrence` (50 tests).
- Run start/completion time and exit status: 2026-10-20T17:34:40+02:00 to 17:34:48+02:00, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`, modified 2026-10-20 17:34:48.633 +02:00, SHA-256 `def3f8c970a10a4ff63eca14d653d0ac69aa2b36b20767069f155932bd8b31cc`. The JUnit XML timestamps are 15:34:46Z and 15:34:47Z.
- Evidence outputs are fresh and inputs stayed unchanged: yes. `--rerun` forced test execution, and the report mtime falls inside the run window. The SHA-256 hashes of all three files were identical before and after the run.
- Final run after latest test additions and production fixes: yes. The run is after the `a59471a4` fixes, the `7a15f449` regression tests and this pass's 3 tests. No production code changed during this pass.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements (JaCoCo INSTRUCTION) | Branches | Functions (METHOD) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/recurrence/OccurrenceExpander.kt` | 900/938 (95.9%) | 81/96 (84.4%) | 32/33 (97.0%) | 103/104 (99.0%) |

Aggregate (one file, no missing or unmeasured files): equal to the row above. No coverage threshold is defined: ARCHITECTURE.md says "It is not a gate".

These numbers are identical to those of the pre-addition run of this pass (17:33:28, report SHA-256 `feb43af1...`). The 3 new tests add behavioral checks on paths that were already executed, not new lines or branches.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `OccurrenceExpander.kt:118, 143, 144, 151, 152, 207, 208, 210, 211` (one missed `?:` or `?.` null branch each) | Defensive handling of inconsistent rows: a timed event with null `startAt`/`endAt`, or an all-day event with null `startDate`/`endDate` | Not reachable through the public API. `setTimed`/`setAllDay` always set both values (protected setters), and the DB check constraint in `V202610201351__calendar_model.sql` requires them. Justified gap. |
| `OccurrenceExpander.kt:101` (1 missed branch: `asOccurrence` returns null for an orphan override) | Same inconsistent-row guard on the orphan path | Not reachable for the same reason. Justified gap. |
| `OccurrenceExpander.kt:189, 192, 196` (3 missed branches; `else null` at line 196 is the only missed line) | `seriesOccurrence` receiving an `OccurrenceId` of the other kind (date for a timed series or instant for an all-day one) | Unreachable: `originalStarts` only produces ids of the master's own kind. Justified gap. |
| `OccurrenceExpander.kt:232` (2 missed branches: null `timeZone` falls back to UTC) | Recorded assumption: "in the event's zone (UTC if none)" | `timeZone` has a protected setter, `setTimed` always sets it, and the DB constraint requires `time_zone` for timed events. A test would need reflection on the JPA field. Justified defensive gap: not a reachable path. |
| `OccurrenceExpander.kt:236` (2 missed instructions in the companion object) | None (synthetic companion constructor bytecode) | Non-material. |

## Unable to test

- The UTC fallback for a timed event without a time zone (line 232): it cannot be constructed through the public API and is rejected by the DB constraint. It does not need a follow-up unless another task makes `time_zone` nullable for timed events.
- Integration with `findCandidatesInRange` (repository plus expander) is out of scope. The confirmed assumption makes the expander pure, and the consumer (calendar API/service) belongs to a later task. Follow-up belongs to that task.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. This pass added 3 behavioral tests:
- DST-start EXDATE/override matching;
- UTC UNTIL across the DST change;
- cancelled all-day override.

The focused suite (50 tests), `./gradlew check` (1297 tests) and `.taskcheck` all pass on the final revision. The remaining uncovered branches are unreachable defensive guards.
