# Test Report

Date: 2026-10-20

Task: cal-recurrence - Recurrence expansion (third pass, after the CR-1 fix of the second review in `reviews/cal-recurrence/fixes.md`)

Scope: The task's commits sit on base `3a4ef4513a7beaedd6216d2976f96e2ab7b61a86`, which the parent supplied. They are the `cal-recurrence:` commits `c015be14`, `8ea3faf2`, `2e64fe47`, `74b7b9a7`, `99ff9fd9`, `46158c0b` and `824e0e0e`, up to HEAD `824e0e0eee14f6fa1ad4b840709fe9d6075aa397` on branch `main`. This pass also includes an uncommitted test addition in `OccurrenceExpanderEdgeCaseTests.kt`.

`git diff --stat 3a4ef451..HEAD` lists only these files:
- `OccurrenceExpander.kt`, the production file;
- the two test files in `server/src/test/kotlin/ch/sonensei/cockpit/calendar/recurrence/`;
- `reviews/cal-recurrence/*`.

No unrelated work is included. This pass focuses on the only production change since the last report: `824e0e0e`. It moves the ical4j search end for timed series to `range.to + DST_END_MARGIN`, where the margin is 2h.

Requirements:
- `tasks/cal-recurrence.md`: acceptance criteria and recorded assumptions.
- SPEC.md §6.1 (via the task), `docs/ARCHITECTURE.md` "Coverage", AGENTS.md.
- `reviews/cal-recurrence/fixes.md`, finding CR-1 of the second review. Its claims checked here:
  - the exact overlap filter still drops the extra candidates;
  - the start needs no margin;
  - COUNT and UNTIL are unaffected.

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/recurrence/OccurrenceExpanderEdgeCaseTests.kt`

## Tests added or updated

Count: 5 added, 0 changed. Total in scope: 56 (13 in `OccurrenceExpanderTests`, 43 in `OccurrenceExpanderEdgeCaseTests`). The fixer's regression test for the range that ends inside the repeated hour already exists and is not duplicated.

- `occurrences starting within two hours after the range end are excluded`:
  - A 30-minute series with range end 10:00 returns only 08:00 to 09:30. The 10:00 to 11:30 candidates fall inside the margin and are dropped.
  - Zero-duration hourly occurrences just after the end are excluded.
  - A series or single event that starts inside the margin is excluded.
  - Series that start exactly 2h after the end, or a day after it, are excluded. These cases cover the timed early return `if (!seed.isBefore(periodEnd))`. No test reached it any more, because with the margin the old "seed at range end" test no longer triggers it.
- `an occurrence at the ambiguous DST-end time is excluded when the range ends at or before it`:
  - The series is seeded in winter time. Its ical4j candidate, 01:30Z, now falls inside the margin, and the occurrence normalises to 00:30Z.
  - Ranges ending at 00:15Z and at 00:30Z (the exclusive end) return nothing.
  - A range starting at the occurrence's end (01:00Z) returns nothing either.
- `a summer-time series at the ambiguous DST-end time is found when the range ends inside the repeated hour`: the summer-seeded counterpart of the fixer's test. It also checks the exclusive end.
- `COUNT and UNTIL still end the series when its end lies inside the margin after the range`: for `FREQ=HOURLY;COUNT=3` and for a UTC `UNTIL`, the margin adds no occurrences beyond the series end. A range ending between two occurrences cuts correctly.
- `an override whose original occurrence lies just after the range end is shown only at its own time`: the 11:00 occurrence, which is inside the margin, is moved into the range. It appears once at 09:15, and the original does not appear.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: `OccurrenceExpander` (ical4j) expands RRULE/EXDATE/overrides in the event's zone into instants for [from, to) | `OccurrenceExpanderTests` (all 13) and the range-boundary, zone, override, invalid-rule, UNTIL and DST tests in `OccurrenceExpanderEdgeCaseTests`. New this pass: the margin exclusion test and the override-from-margin test. | `cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.calendar.recurrence.*' jacocoTestReport` | Pass |
| AC2: weekly across DST start/end (Europe/Zurich) | Existing DST tests and `an occurrence at the ambiguous DST-end time is found when the range ends inside the repeated hour` (fixer). New this pass: `... is excluded when the range ends at or before it` and `a summer-time series at the ambiguous DST-end time ...` | same | Pass |
| AC2: all-day events | Existing all-day tests. The margin change does not touch the all-day path. | same | Pass |
| AC2: moved and cancelled occurrences | Existing override and cancellation tests. New this pass: `an override whose original occurrence lies just after the range end ...` | same | Pass |
| AC2: COUNT/UNTIL | Existing COUNT/UNTIL tests. New this pass: `COUNT and UNTIL still end the series when its end lies inside the margin after the range` | same | Pass |
| AC2: monthly by weekday | `monthly by weekday` | same | Pass |
| AC3: `./gradlew test --tests 'ch.sonensei.cockpit.calendar.recurrence.*'` passes (Testcontainers where persistence is involved) | All 56 tests in the package. The expander is pure (confirmed assumption), so no persistence or Testcontainers is involved. | `cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.calendar.recurrence.*' jacocoTestReport` | Pass (56 tests, 0 failures, 0 skipped) |
| Assumption: invalid RRULE keeps DTSTART and logs WARN with the event id only | Existing invalid-rule tests | same | Pass |

## Focused test execution

Command:

`cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.calendar.recurrence.*' jacocoTestReport`

Result:

Pass: BUILD SUCCESSFUL, 56 tests, 0 failures, 0 errors, 0 skipped. `OccurrenceExpanderEdgeCaseTests` ran 43 and `OccurrenceExpanderTests` ran 13.

## Full test execution

Command:

`cd server && ./gradlew check` (ktlint and all server tests, including Testcontainers suites)

Result:

Pass: BUILD SUCCESSFUL in 4m 17s (17:52:28 to 17:56:46 +02:00). ktlint main/test checks passed. JUnit XML totals: 1393 tests, 0 failures, 0 errors, 0 skipped, in 122 suites.

## .taskcheck

Result:

Pass: `sh scripts/check.sh` printed "== all checks passed". It ran from 17:56:55 to 17:59:39 +02:00.

## Bugs found

None. The margin change behaved as required in every new test:
- candidates in the margin are dropped by the overlap filter;
- COUNT and UNTIL are not extended;
- the summer- and winter-seeded DST-end cases are both correct;
- no existing test failed.

## Coverage

Status: verified. This is a fresh run on HEAD `824e0e0e` plus this pass's test additions.

Metric: whole-file coverage of task-changed production files.

Changed-line coverage: the task added `OccurrenceExpander.kt` as a new file at base `3a4ef451`, so every executable line in it is an added line. Changed-line coverage therefore equals the whole-file LINE counter: 104/105 (99.0%). Changed-branch coverage equals the whole-file BRANCH counter for the same reason.

Evidence:
- **Task and scope:**
  - Task: `cal-recurrence`.
  - Base: `3a4ef4513a7beaedd6216d2976f96e2ab7b61a86`.
  - Measured HEAD: `824e0e0eee14f6fa1ad4b840709fe9d6075aa397`.
  - The parent supplied the task commits (`git log 3a4ef451..HEAD`: seven `cal-recurrence:` commits). They were cross-checked with `git diff --stat 3a4ef451..HEAD`.
- **Snapshot:** HEAD `824e0e0e` plus an uncommitted `OccurrenceExpanderEdgeCaseTests.kt`. SHA-256 of the measured files:
  - `OccurrenceExpander.kt`: `85891501f78ef68fc75fcaf28abfa4f3476c0ee114025f89b7a02c90071d84b7` (unchanged from HEAD)
  - `OccurrenceExpanderEdgeCaseTests.kt`: `9156107dba729a1d81c696d1303ef8ef181d4115c77810f105ebee2cd36cc5c9` (uncommitted, this pass)
  - `OccurrenceExpanderTests.kt`: `88ba4880d675f9a1b37ac6598ad9b4570ddc44fca748cd0ccf43c78e3cc2c96f`
- **Files:**
  - Included: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/recurrence/OccurrenceExpander.kt`.
  - No renames or deletions.
  - Excluded: the test files, and `reviews/cal-recurrence/*` (documentation).
- **Provider and command:**
  - Provider: JaCoCo 0.8.15 through the Gradle `jacoco` plugin.
  - Command: `./gradlew test --rerun --tests 'ch.sonensei.cockpit.calendar.recurrence.*' jacocoTestReport`, run in `server/`.
  - Test selection: package `ch.sonensei.cockpit.calendar.recurrence` (56 tests).
  - The report was read with `.tmp/jacoco_scope.py`, which uses the provider's native counters.
- **Run time and status:** 2026-10-20T17:52:09+02:00 to 17:52:18+02:00, BUILD SUCCESSFUL.
- **Report:**
  - Path: `server/build/reports/jacoco/test/jacocoTestReport.xml`.
  - Modified 2026-10-20 17:52:18.217 +02:00.
  - SHA-256: `c6025256c5f999c4db5f1a1843089c45be28aa662bfd4ab1e2e10f62326c4da4`.
  - JUnit XML timestamps: 15:52:16Z and 15:52:17Z.
- **Freshness:** yes.
  - `--rerun` forced test execution.
  - The report's modification time falls inside the run window.
  - `sha256sum -c` matched all three files after the run and again after `./gradlew check`.
- **Final run:** yes. This run is after the `824e0e0e` fix and all of this pass's tests. No production code changed during this pass.

An earlier run in this pass (17:51:27 to 17:51:47, before the last added case) showed line 160 newly uncovered. It covered branches 80/96. That run is superseded and not reported as the result.

Values below use covered/total (percentage).

| Changed file | Statements (JaCoCo INSTRUCTION) | Branches | Functions (METHOD) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/recurrence/OccurrenceExpander.kt` | 909/949 (95.8%) | 81/96 (84.4%) | 32/34 (94.1%) | 104/105 (99.0%) |

Aggregate: one file, with no missing or unmeasured files, so it equals the row above. No coverage threshold is defined: ARCHITECTURE.md says "It is not a gate".

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `OccurrenceExpander.kt:160` (timed early return when the seed is at or after `range.to + 2h`) | Series starting after the range yield nothing | The margin moved this line out of reach of the earlier tests. It is now covered by `occurrences starting within two hours after the range end are excluded`. |
| `OccurrenceExpander.kt:118, 143, 144, 151, 152, 210, 211, 213, 214` (one missed `?:`/`?.` null branch each) | Defensive handling of inconsistent rows: a timed event with null `startAt`/`endAt`, or an all-day event with null `startDate`/`endDate` | Not reachable through the public API. `setTimed`/`setAllDay` always set both values, and the DB check constraint requires them. Justified gap. |
| `OccurrenceExpander.kt:101` (1 missed branch: `asOccurrence` returns null for an orphan override) | Same inconsistent-row guard on the orphan path | Not reachable, for the same reason. Justified gap. |
| `OccurrenceExpander.kt:192, 195, 199` (3 missed branches; `else null` at line 199 is the only missed line) | `seriesOccurrence` receiving an `OccurrenceId` of the other kind | Unreachable: `originalStarts` only produces ids of the master's own kind. Justified gap. |
| `OccurrenceExpander.kt:235` (2 missed branches: a null `timeZone` falls back to UTC) | Recorded assumption: "in the event's zone (UTC if none)" | `setTimed` always sets the zone, and the DB constraint requires `time_zone` for timed events. Justified defensive gap. |
| `OccurrenceExpander.kt:239, 242` (synthetic `Companion.getDATE_ONLY_UNTIL`/`getDST_END_MARGIN`, 2 instructions each) | None: Kotlin accessors that the class does not call, because it reads the fields directly | Non-material. They explain METHOD going from 32/33 to 32/34: the new constant adds one synthetic getter. |

## Unable to test

- The UTC fallback for a timed event without a time zone (line 235): it cannot be constructed through the public API, and the DB constraint rejects it. It needs no follow-up unless another task makes `time_zone` nullable for timed events.
- A zone with a DST jump larger than 2h, to check whether the margin is still enough: no such zone exists in the current tz database. This is the fixer's stated premise ("more than the largest DST offset jump"). It is not testable with real zone data, and no follow-up is needed.
- Integration with `findCandidatesInRange` is out of scope. The expander is pure (confirmed assumption), and its consumer belongs to a later task.

## Summary

Tests and task-scoped coverage are sufficient, and no production defects were found. This pass added 5 tests for the `DST_END_MARGIN` change:
- exclusion of occurrences and series inside or past the margin, which restores coverage of the timed early return at line 160;
- exclusion at the DST-end ambiguous time;
- the summer-seeded DST-end case;
- COUNT/UNTIL not extended by the margin;
- an override moved from the margin into the range.

The focused suite (56 tests), `./gradlew check` (1393 tests) and `.taskcheck` all pass on the final revision. The remaining uncovered branches are unreachable defensive guards.
