# Test Report

Date: 2026-11-22

Task: and-cal-hidden-indicator - Android calendar: day/week show which entries are scrolled out of view

Scope: task base d765ccba1e9588bf0f42410a019ce618eeff2cb3; task commit da0ac6667, rebased by the parent to 18014d625 (`git diff --stat da0ac6667 18014d625 -- android` is empty, so the android content is identical). Measured HEAD 580f2c7e482d67ff42bf3fc4c5e88da05fde21aa (adds only `reviews/and-cal-hidden-indicator/review.md` on top of 18014d625). Uncommitted inputs: the test files listed below (no uncommitted production changes).

Requirements:
- `tasks/and-cal-hidden-indicator.md` acceptance criteria
- `AGENTS.md` (strings in English and German, fictitious test data), `docs/ARCHITECTURE.md` "Coverage"

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/HiddenEntriesTest.kt` (extended)
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/HiddenEntryChipsTest.kt` (extended)
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/HiddenEntryStringsTest.kt` (new)

## Tests added or updated

Count: 12 added (6 pure, 4 Robolectric Compose, 2 Robolectric resources); no existing test changed or removed.

Added:
- HiddenEntriesTest: `a negative viewport height means nothing is reported`, `the nearest hidden entries are found whatever the order of the input`, `the parts of overnight entries on this day are counted like other timed entries`, `a middle day of a multi-day timed entry is never hidden`, `an entry just past the edge by one px is partly visible`, `scroll targets are rounded to whole px`
- HiddenEntryChipsTest: `tapping a week column's chip scrolls that column's nearest entry into view`, `an overnight entry is counted in both of its day columns`, `no chips when every entry is visible`, `the chips are described in German` (qualifiers de-rDE-w360dp-h640dp)
- HiddenEntryStringsTest: `the week label is arrow and number only, the day label says more` (en-rUS), `the labels and descriptions are in German` (de-rDE)

Note: the chip's visible text is removed from the semantics tree (`clearAndSetSemantics` on the Text, TalkBack reads the content description), so the visible labels ("▼ 2" in the week, "▼ 1 later" / "▲ 2 früher" in the day view) cannot be asserted through Compose semantics; HiddenEntryStringsTest checks the exact resources HiddenChip formats, and the week-width test checks that the week chip fits its column.

## Requirement coverage

Command A: `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests '*HiddenEntr*' --tests '*CalendarGridScrollTest'`
Command B: `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 chips per column, entirely above/below counted, partly visible counts as visible, none at n = 0, update while scrolling | HiddenEntriesTest (all 16, incl. edge, one-px, overnight, multi-day, unordered input); HiddenEntryChipsTest `the day view shows how many...`, `the chips update while scrolling`, `in the week each column has its own chips...`, `an overnight entry is counted in both of its day columns`, `no chips when every entry is visible` | A, B | Pass |
| AC2 week chip fits (arrow + number) on 360dp; day view says more | HiddenEntryChipsTest `in the week each column has its own chips and they fit the narrow column on a 360dp phone`; HiddenEntryStringsTest (both) | A, B | Pass |
| AC3 tap scrolls (animated) so the nearest hidden entry in that direction is in view | HiddenEntryChipsTest `the day view shows...a tap scrolls to the nearest`, `tapping a week column's chip scrolls that column's nearest entry into view`, `an overnight entry...`; HiddenEntriesTest `scroll targets...`, `an entry taller than the viewport...` | A, B | Pass (target offsets asserted; the animation itself is not observable in Robolectric beyond reaching the target via `animateScrollTo`) |
| AC4 TalkBack content descriptions | HiddenEntryChipsTest content-description assertions in English and German | A, B | Pass |
| AC5 pure visibility function with unit tests | HiddenEntriesTest (16 tests) | A, B | Pass |
| AC6 strings in English and German | HiddenEntryStringsTest (both), HiddenEntryChipsTest `the chips are described in German` | A, B | Pass |
| AC7 `./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` | full Android suite | B | Pass for the Android part; `sh scripts/check.sh` Not run by the test-writer (left to the parent, which runs .taskcheck) |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests '*HiddenEntr*' --tests '*CalendarGridScrollTest'`

Result:

Pass. CalendarGridScrollTest 11/11, HiddenEntriesTest 16/16, HiddenEntryChipsTest 7/7, HiddenEntryStringsTest 2/2 (JUnit XML in `android/app/build/test-results/testDebugUnitTest/`, timestamps 2026-11-22T09:06:45Z to 09:06:55Z). A first attempt failed only on ktlint (chain-method-continuation in a new test line), fixed in the test.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Pass (exit 0, completed about 2026-11-22T09:17Z). Server and web parts not run: the task changes Android only.

## .taskcheck

Result:

Not run by the test-writer (the parent runs `sh scripts/check.sh` before `done`).

## Bugs found

None. No production defect was found by the added tests.

## Coverage

Status: verified (Android, JaCoCo, agenda-package test selection)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured by intersecting JaCoCo XML line entries with the added/modified lines of `git diff -U0 d765ccba1 18014d625` (CalendarScreen.kt lines 4, 48, 57, 59, 63, 77, 80, 103, 217-220, 820-831, 837-930; HiddenEntries.kt is a new file, all lines). CalendarScreen.kt changed executable lines 75/75 covered (100%), branches on those lines 24/28; HiddenEntries.kt 27/27 lines, 22/22 branches.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-cal-hidden-indicator; base d765ccba1e9588bf0f42410a019ce618eeff2cb3; HEAD 580f2c7e482d67ff42bf3fc4c5e88da05fde21aa; task commit 18014d625 (rebased da0ac6667, as given by the parent); file scope from `git diff --stat` of the task commit.
- Source/test snapshot identifier (including uncommitted inputs): sha256 HiddenEntries.kt b4d7d31f41cd..., CalendarScreen.kt bacdac967f55..., HiddenEntriesTest.kt 51ca1a97bdac..., HiddenEntryChipsTest.kt a4f8f87502e8..., HiddenEntryStringsTest.kt d99ac58ec53e...; identical before and after the coverage and full runs.
- Exact included files; renamed/deleted/excluded files and reasons: included `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/HiddenEntries.kt`, `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt`. Excluded `android/app/src/main/res/values/strings_calendar_agenda.xml` and `values-de/strings_calendar_agenda.xml` (resources, not instrumentable; checked by HiddenEntryStringsTest). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`; `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' createDebugUnitTestCoverageReport` in `android/`; selection = all tests of package `ch.sonensei.cockpit.calendar.agenda` (pre-existing agenda tests contribute hits to CalendarScreen.kt).
- Run start/completion time and exit status: about 2026-11-22T09:07Z to 09:07:44Z; exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 3993b9bdcadc8561f858f7c5798a76c5a4ea0ad9c063f04788437f3b24b809b7, mtime 2026-11-22 10:07:44 +0100.
- Evidence outputs are fresh and inputs stayed unchanged: report written by this run (mtime after start); input hashes unchanged after the run.
- Final run after latest test additions and production fixes: yes (no production fixes so far; if the fixer changes code, this is stale).

Values below use covered/total (percentage).

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/HiddenEntries.kt` | 161/161 (100.0%) | 22/22 (100.0%) | 8/8 (100.0%) | 27/27 (100.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt` | 7940/8037 (98.8%) | 319/354 (90.1%) | 144/159 (90.6%) | 646/650 (99.4%) |
| Aggregate (summed counters, both files) | 8101/8198 (98.8%) | 341/376 (90.7%) | 152/167 (91.0%) | 673/677 (99.4%) |

No scoped file is unmeasured.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CalendarScreen.kt:824, 830, 866, 916` (one of two branches each, 1-2 instructions) | Compose compiler generated recomposition/skip branches in lambdas of the changed code, not task behavior | Justified gap: all source lines executed; not meaningfully testable |
| `CalendarScreen.kt` other partial lines (129-179, 251-533, 636-641, 737-739, 810, 973-1112) | Pre-existing code outside the task's changed lines | Out of task scope |

## Unable to test

- The scroll animation itself (AC3 "animated"): Robolectric only shows that `animateScrollTo` reaches the target offset; the animation's look is not observable. No follow-up needed beyond a manual check if wanted.
- The visible chip text through the UI tree: removed from semantics by design (`clearAndSetSemantics`); covered through the string resources instead.
- `sh scripts/check.sh` was left to the parent.

## Summary

Tests added and all validation passed.
