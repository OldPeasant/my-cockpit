# Test Report

Date: 2026-11-22

Task: and-cal-hidden-indicator - Android calendar: day/week show which entries are scrolled out of view

Scope: final round. Task base ca0b99ad5dc76f36b9caaa2812302c665ae9686d; task commits as given by the parent: c1de00c67 (implementation), 0e51e81ce (CR-1 fix), b47fbb93e (CR-4/CR-3 fix). While this round ran, the parent rebased onto main (reflog 2026-11-22 10:31:55 +0100, new base e10090d80): the commits became 99c3753a7, 54f3c5737 and 44d8bbc0b (HEAD 44d8bbc0bf344300237c17caf053ee434f4d9b47). `git diff --stat b47fbb93e 44d8bbc0b -- android` is empty, and `git diff --stat ca0b99ad5 HEAD -- android` matches `git diff --stat e10090d80 HEAD -- android` (the same 7 task files), so the measured Android code is the same as the reviewed revision b47fbb93e. Uncommitted input: one new test in HiddenEntriesTest.kt (see below). `reviews/and-cal-hidden-indicator/review.md` also shows as modified in the working tree; it was not changed by the test-writer.

Requirements:
- `tasks/and-cal-hidden-indicator.md` acceptance criteria and recorded assumptions (scroll target rules, week chip touch target)
- `reviews/and-cal-hidden-indicator/review.md` (CR-1 to CR-4) and `fixes.md` (rounds 1 and 2)
- `AGENTS.md` (strings in English and German, fictitious test data), `docs/ARCHITECTURE.md` "Coverage"

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/HiddenEntriesTest.kt` (one test added in this round)

Task test files overall (all rounds): HiddenEntriesTest.kt, HiddenEntryChipsTest.kt, HiddenEntryStringsTest.kt.

## Tests added or updated

Count: 1 added in this round; no existing test changed or removed.

- HiddenEntriesTest `the full margin is kept from a viewport four margins tall, a quarter of the viewport below that`: pins the boundary of the CR-4 clamp `inset = min(margin, viewportHeight / 4)`. With a 30 px margin, a 120 px viewport keeps the full 30 px, and a 119 px viewport keeps 29 px (integer quarter), for both the "above" target and the "below" target of an entry taller than the viewport. The fixer's regression test only checks a 20 px viewport far below the margin; without this test a change to `<`/`<=`, to a different divisor, or to float rounding of the quarter would go unnoticed.

Review of the final behavior against the existing tests (no further gaps worth a test):
- CR-4 clamp: `a viewport smaller than the margin still reveals the nearest entry` (20 px viewport: target is start minus 5 px, and at that offset the entry is no longer hidden in either direction) plus the new boundary test.
- CR-1 margin: `scroll targets keep the revealed entry a margin away from the edge`, `a margin never makes a scroll target negative`, `an entry taller than the viewport is scrolled to a margin above its start`; Compose tap tests in HiddenEntryChipsTest assert the offsets with `HIDDEN_ENTRY_MARGIN` (day above/below, week column below).
- `hiddenChipLabel` (CR-3): HiddenEntryChipsTest `the day view chips say earlier and later, the week chips show the arrow and the number only` asserts all four combinations ("▼ 1 later", "▲ 2 earlier", "▼ 2", "▲ 1"); HiddenEntryStringsTest checks the English and German resources. The chip's visible text is removed from semantics by design (`clearAndSetSemantics`, TalkBack reads the description), so the label is checked through the helper. Remaining residual: nothing asserts that the private `HiddenChip` forwards its `week` flag unchanged to the helper. A swap at that call site would put the long label into the week columns, which the w360dp width test (`in the week each column has its own chips and they fit the narrow column on a 360dp phone`) would most likely catch. Not tested further: it would need exposing the text in semantics, which changes what TalkBack announces.

## Requirement coverage

Command A (focused + coverage): `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' createDebugUnitTestCoverageReport`
Command B (full Android): `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 chips per column, entirely above/below counted, partly visible counts as visible, none at n = 0, update while scrolling | HiddenEntriesTest (21 tests, incl. exact edges, one-px, overnight parts, multi-day middle day, unordered input, all-day ignored, minimum drawn height); HiddenEntryChipsTest `the day view shows how many...`, `the chips update while scrolling`, `in the week each column has its own chips...`, `an overnight entry is counted in both of its day columns`, `no chips when every entry is visible` | A, B | Pass |
| AC2 week chip fits (arrow + number) on 360dp; day view says more | HiddenEntryChipsTest week-width test and `the day view chips say earlier and later, the week chips show the arrow and the number only`; HiddenEntryStringsTest (both) | A, B | Pass |
| AC3 tap scrolls (animated) so the nearest hidden entry in that direction is in view (incl. margin, CR-4 clamp) | HiddenEntryChipsTest tap tests (day above/below, week column, overnight); HiddenEntriesTest scroll-target, margin, tall-entry, small-viewport and new boundary tests | A, B | Pass (target offsets asserted; the animation look is not observable in Robolectric) |
| AC4 TalkBack content descriptions | HiddenEntryChipsTest content-description assertions in English and German | A, B | Pass |
| AC5 pure visibility function with unit tests | HiddenEntriesTest (21 tests, no Compose) | A, B | Pass |
| AC6 strings in English and German | HiddenEntryStringsTest (both), HiddenEntryChipsTest `the chips are described in German` | A, B | Pass |
| AC7 `./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` | full Android suite | B | Pass for the Android command; `sh scripts/check.sh` Not run: left to the parent, which runs .taskcheck |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' createDebugUnitTestCoverageReport`

Result:

Pass (BUILD SUCCESSFUL in 46s). JUnit XML: 31 test classes, 272 tests, 0 failures, 0 errors, 0 skipped; HiddenEntriesTest 21/21, HiddenEntryChipsTest 8/8, HiddenEntryStringsTest 2/2 (suite timestamps 2026-11-22T09:32:19Z to 09:32:21Z). ktlintCheck passed on the new test.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Pass (exit 0, 2026-11-22T09:32:50Z to about 09:38:44Z). JUnit XML: 463 test classes, 5644 tests, 0 failures, 0 errors, 0 skipped. Server and web not run: the task changes Android only.

## .taskcheck

Result:

Not run by the test-writer (the parent runs `sh scripts/check.sh` before `done`).

## Bugs found

None. The final code (CR-1 margin, CR-4 clamp, `hiddenChipLabel`) behaved as required in every test.

## Coverage

Status: verified (Android, JaCoCo, agenda-package test selection, final revision)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured by intersecting the JaCoCo XML `line` entries with the added/modified lines of `git diff -U0 ca0b99ad5 HEAD` per file (script `.tmp/cov.py`, Git-ignored). CalendarScreen.kt changed executable lines 78/78 covered (100%), branches on those lines 24/28; HiddenEntries.kt changed executable lines 32/32, branches 22/22 (new file except the package context).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-cal-hidden-indicator; base ca0b99ad5dc76f36b9caaa2812302c665ae9686d (after the parent's rebase e10090d80cc201ab83018cb861fc13f2067f9811, identical Android diff); HEAD 44d8bbc0bf344300237c17caf053ee434f4d9b47 (rebased b47fbb93e, empty Android diff between them); task commits from the parent; file scope from `git diff --stat` of the task range.
- Source/test snapshot identifier (including uncommitted inputs): sha256 HiddenEntries.kt d43cc468ea91..., CalendarScreen.kt 969cf545f50a..., HiddenEntriesTest.kt a747b3af3c0e... (includes the uncommitted new test), HiddenEntryChipsTest.kt b64dacc98096..., HiddenEntryStringsTest.kt d99ac58ec53e...; identical before the coverage run and after the full run.
- Exact included files; renamed/deleted/excluded files and reasons: included `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/HiddenEntries.kt` and `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt`. Excluded `android/app/src/main/res/values/strings_calendar_agenda.xml` and `android/app/src/main/res/values-de/strings_calendar_agenda.xml` (resources, not instrumentable; checked by HiddenEntryStringsTest and the label test). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (Gradle 9.8.1); Command A in `android/`; selection = all tests of package `ch.sonensei.cockpit.calendar.agenda` (pre-existing agenda tests contribute hits to CalendarScreen.kt).
- Run start/completion time and exit status: started after 2026-11-22T09:31:34Z, report written 2026-11-22 10:32:26 +0100 (09:32:26Z); exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 534e62e04cf23073224f9f939de82fb29d6209b5cf7e095ac3080fc18af6469d.
- Evidence outputs are fresh and inputs stayed unchanged: compiled test sources and the test task re-executed in this run (new test compiled, 21 HiddenEntriesTest results), report mtime after the run start; input hashes unchanged after the run. The parent's rebase finished at 09:31:55Z with no Android change, so the tree under test was identical throughout.
- Final run after latest test additions and production fixes: yes (after CR-4/CR-3 fix and the test added in this round).

Values below use covered/total (percentage). JaCoCo instructions stand in for statements, methods for functions.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/HiddenEntries.kt` | 205/205 (100.0%) | 22/22 (100.0%) | 9/9 (100.0%) | 32/32 (100.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt` | 7996/8093 (98.8%) | 319/354 (90.1%) | 146/161 (90.7%) | 649/653 (99.4%) |
| Aggregate (summed counters, both files) | 8201/8298 (98.8%) | 341/376 (90.7%) | 155/170 (91.2%) | 681/685 (99.4%) |

No scoped file is unmeasured; the two string resource files are excluded as stated above.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CalendarScreen.kt:824, 830, 870, 929` (one of two branches each, 1-2 instructions) | Compose compiler generated recomposition/skip branches in lambdas of the changed code (column loops, the chip's click lambda, the chip surface semantics), not task behavior | Justified gap: every source line executed; not meaningfully testable |
| `HiddenChip` call site of `hiddenChipLabel(count, earlier, week)` | A swapped `week` flag at the call site | Covered indirectly by the w360dp week-width test; direct assertion would require exposing the chip text in semantics (changes TalkBack output) |
| `CalendarScreen.kt` other partial lines outside the task diff | Pre-existing code outside the task's changed lines | Out of task scope |

## Unable to test

- The scroll animation itself (AC3 "animated"): Robolectric only shows that `animateScrollTo` reaches the target offset; the animation's look is not observable. A manual check is optional.
- The visible chip text through the UI tree: removed from semantics by design; covered through `hiddenChipLabel` and the string resources instead.
- `sh scripts/check.sh` was left to the parent.

## Summary

Tests added and all validation passed.
