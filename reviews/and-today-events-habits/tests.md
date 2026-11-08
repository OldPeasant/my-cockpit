# Test Report

Date: 2026-11-08

Task: and-today-events-habits - Android Today shows events and habits (round 2, after the fixes)

Scope: `git diff 0f47c5801e7f1e8b591d3c9cf3418b207dceaa66..a34f77661bd7476e576ad6fb56e43bcc8dc8fc64`. The parent supplied the base and the task commits: cb4ec48ec (implementation), 1556df62f (tests), 134c5cfa1 (test-writer tests and review reports) and a34f77661 (fixes for the review findings, see `reviews/and-today-events-habits/fixes.md`). HEAD was read from `.git/refs/heads/main`. The test-writer cannot run Bash or git here, so it took the file list from the parent, the task `touches` and fixes.md, and checked it by reading the files. This round adds one test, which is uncommitted (see below).

Requirements:
- tasks/and-today-events-habits.md (acceptance criteria and the two recorded assumptions)
- reviews/and-today-events-habits/fixes.md (the behavior the fixes promise: CR-1, CR-2, CR-3, TW-OBS-3, LINT-1)
- AGENTS.md project notes (tests, ktlint style, fictitious data)
- The habit check semantics in `habits/HabitsRepository.kt` (`Habit.nextCount`, from and-habits-manage)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/gtd/today/TodayExtrasTest.kt` (round 2: one test added)

Files changed by earlier rounds and the fixer, reviewed again in this round against the final code:
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/today/TodayExtrasTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/today/TodayWithEditorTest.kt` (new from the fixer)

## Tests added or updated

Count: 1 added in this round. No existing test was changed.

- `a habit list requested before a failed check keeps the failed check message` (TodayExtrasTest). It covers the one fix branch that nothing tested before. According to fixes.md (CR-1), a habit reload clears `checkFailed` only if no check started or ended while the list was loading. The test holds a habit list with a `CompletableDeferred`, then sends a check that fails (Offline), and then lets the older list arrive. It checks that the failure message stays and that the habit is unchanged. Before this test, changing `checkFailed = current.checkFailed && changes != changeSeq` to `checkFailed = false` in `TodayExtrasViewModel.load()` would not have made any test fail. The `a later load ... clears the failed check message` test covers only the opposite branch.

I reviewed the rest of the final code against the existing tests and found no other real gap:
- CR-1 ordering: event loads that answer out of order, habit loads that answer out of order, a list requested before a check ends, and a list requested while a check is in flight each have a test.
- CR-2 `Occurrence.isOn`: tested with tomorrow's event, one that ended yesterday, a multi-day event that spans today, one with no end date, and today's event.
- TW-OBS-3 `eventTime`: covers an event lasting all of today, one starting before today and ending at the start of tomorrow, and 00:00 to 23:00.
- CR-3 `TodayWithEditor`: TodayWithEditorTest covers opening the editor, reloading after close, and the birthday opening its contact and going back.
- LINT-1 has no behavior change. `lintDebug` checks it.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: Today lists today's events (time, title, calendar color; a tap opens the editor or viewer) | TodayExtrasTest: `load lists today's visible events all-day first and only the habits due today`, `event times leave out the side outside today`, `all-day events of other days are left out`, `only the newest load's events show, also when an older load answers last`, `archived habits are left out and no events show while the calendars cannot be loaded`, `events show their time and title, and a tap opens the event`, `a done habit offers undo without a count and an event shows its location`. TodayWithEditorTest: `a tapped event opens it in the editor, and closing the editor loads the events and habits again`, `a tapped birthday opens its contact, not the editor, and back returns to Today` | parent: `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.today.*' lintDebug` | Pass (parent run on HEAD a34f77661: TodayExtrasTest 20/20, TodayWithEditorTest 2/2). No test asserts the calendar color, because it is a drawn dot and not exposed in semantics. |
| AC2: habits due today with a check button (same semantics as and-habits-manage, count up to the target) | TodayExtrasTest: `a check sends the next count and shows the habit the server returns`, `a failed check keeps the habit and says so`, `a failed load keeps what was shown`, `a second tap while the check is being sent is ignored`, `a successful check clears the message of an earlier failed one`, `only the newest load's habits show, also when an older load answers last`, `a habit list requested before a check finished does not undo the check`, `a habit list requested while a check is on its way keeps the checked habit`, `a later load shows habits checked elsewhere and clears the failed check message`, `habits due today show their count and the check button checks them`, `a habit being checked has its button disabled and a failed check shows a message`, `a done habit offers undo without a count and an event shows its location`; new: `a habit list requested before a failed check keeps the failed check message` | same | Pass for the 20 tests the parent ran. The new test was not run: sub-agents cannot run Gradle, so the parent must run it. The view model passes the habit to `HabitsRepository.check`, which applies the `nextCount` rule from and-habits-manage, so the semantics are reused rather than duplicated. |
| AC3: an empty Today offers "Pick a next action" (opens Next) | TodayExtrasTest: `an empty day offers to pick a next action, also with events`, `the empty day action shows without events or habits`; TodayScreenTest (empty-day cases) | same | Pass (parent run). The `openNext` wiring in `ui/CockpitShell.kt` was checked only by reading it. |
| AC4: new strings in English and German | Checked by reading `res/values/strings_next_today.xml` and `res/values-de/strings_next_today.xml` (the fixes did not change them). The executable check is Android lint (MissingTranslation). | parent: `lintDebug` in the command above | Pass (parent: lint reported 0 errors) |
| AC5: tests cover the new behavior; ktlintCheck, testDebugUnitTest, lintDebug and check.sh pass | all of the above | parent: the focused command above; the full `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` | ktlintCheck, the today tests and lintDebug passed (parent run). The full Android unit suite and `scripts/check.sh` were not run by the test-writer, which has no shell. The parent must run them, together with the new test. |

## Focused test execution

Command:

`cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.today.*' lintDebug` (run by the parent on HEAD a34f77661, before the round 2 test was added)

Result:

Pass: BUILD SUCCESSFUL. TodayExtrasTest 20/20, TodayWithEditorTest 2/2, TodayScreenTest 15/15, TodayRouteTest 2/2, TodayViewModelTest 28/28. lintDebug reported 0 errors. The round 2 test has not been run yet. The parent must run `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.today.TodayExtrasTest'`, which should report 21 tests.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Not run by the test-writer, which cannot use Gradle or Bash. The parent ran the today package and lint (see above) and must run the full suite.

## .taskcheck

Result:

Not run by the test-writer, which has no shell access. The parent must run `sh scripts/check.sh`.

## Bugs found

No production defects found. The new test was written to pass against the final code. I traced it by hand: the second `load()` records `changes = 0`, the failed check moves `changeSeq` to 2, so `changes != changeSeq` keeps `checkFailed` true, and `lastChange[water] = 2 > 0` keeps the habit shown on screen, which is unchanged because the check failed.

The three earlier observations are resolved: the load and check race and the sticky failure message by CR-1, and the 00:00 to 00:00 time by TW-OBS-3. CR-4 (duplicated editor callbacks) is still out of scope, as fixes.md records. It is a maintainability point, not a testable defect.

## Coverage

Status: unavailable. The test-writer cannot run Gradle or any shell command here, so no coverage tool ran. The parent's run had no coverage task. No numbers from earlier runs or for the whole repository are used in place of a measurement.

Metric: whole-file coverage of task-changed production files (not measured).
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-today-events-habits, base 0f47c5801e7f1e8b591d3c9cf3418b207dceaa66, HEAD a34f77661bd7476e576ad6fb56e43bcc8dc8fc64. The commits (cb4ec48ec, 1556df62f, 134c5cfa1, a34f77661) and the file scope came from the parent, fixes.md and the task `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD a34f77661 plus the uncommitted round 2 addition to `TodayExtrasTest.kt` described above. No content hash was computed because there was no shell access.
- Exact included files: `gtd/today/TodayExtrasViewModel.kt`, `gtd/today/TodayExtras.kt`, `gtd/today/TodayWithEditor.kt`, `gtd/today/TodayScreen.kt`, `ui/CockpitShell.kt` (one line). Excluded: the string resources (no executable code). No files were renamed or deleted.
- Provider/version, command, working directory, and test selection: none ran. The parent has to run the project's coverage command (docs/ARCHITECTURE.md "Coverage").
- Run start/completion time and exit status: N/A.
- Generated report paths and report hashes/run identifier: N/A.
- Evidence outputs are fresh and inputs stayed unchanged: N/A.
- Final run after latest test additions and production fixes: no. No coverage run took place.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `gtd/today/TodayExtrasViewModel.kt` | N/A (not measured) | N/A | N/A | N/A |
| `gtd/today/TodayExtras.kt` | N/A (not measured) | N/A | N/A | N/A |
| `gtd/today/TodayWithEditor.kt` | N/A (not measured) | N/A | N/A | N/A |
| `gtd/today/TodayScreen.kt` | N/A (not measured) | N/A | N/A | N/A |
| `ui/CockpitShell.kt` | N/A (not measured) | N/A | N/A | N/A |

Estimate from reading the code (not a measurement):

- `TodayExtrasViewModel.kt`: every branch is probably covered once the round 2 test is included. That includes load success, failed occurrences, failed calendars, the hidden-calendar filter, `isOn` for all-day events of today, other days, multi-day events and events with no end date, the archived and not-due filters, stale event and habit loads, a list kept while a check is newer, and `checkFailed` both cleared and kept on reload. For check: Ok, failed, a second tap ignored, and a failure cleared by the next check. Not covered: the `isOn` fallback when `endDate` is not after `startDate` (malformed data).
- `TodayExtras.kt`: covered are the empty and non-empty event and habit sections, the failure message, the disabled button, the all-day label, the location, the count shown and hidden, the done and not-done icon, and every `eventTime` branch, including the new whole-day `null`. Not covered: the fallback to the primary color when an event's calendar is not in the list, and a blank location.
- `TodayWithEditor.kt`: now covered by TodayWithEditorTest: the editor opening, the reload when the editor closes, and the birthday contact slot and back. Not covered: the default `contact` lambda (`BirthdayContact` needs Hilt) and the 19 editor callback references beyond close.
- `TodayScreen.kt`: the `hasExtras` changes and EmptyDay are covered by TodayExtrasTest and TodayScreenTest. Not covered: the Hilt branch that uses `TodayWithEditor`.
- `CockpitShell.kt`: checked only by reading it.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `gtd/today/TodayExtrasViewModel.kt:97` (`checkFailed` kept when the list is older than a check) | CR-1 fix: a list requested before a failed check must not hide the failure | Test added: `a habit list requested before a failed check keeps the failed check message` |
| `gtd/today/TodayExtrasViewModel.kt:134` (`endDate` not after `startDate`) | Malformed all-day data | Not tested. This is defensive code for data the server does not produce. Low risk. |
| `gtd/today/TodayWithEditor.kt:29` (default `contact` = `BirthdayContact`) | AC1: a birthday opens its contact in the app | Not tested. It needs a Hilt view model. The seam is tested through the injected slot, and the default is a one-line delegation. |
| `gtd/today/TodayWithEditor.kt:59-80` (editor callbacks other than close) | Editing inside the editor | Not tested here. These are method references to `EventEditorViewModel`, which the calendar tests cover. Drift risk is CR-4, out of scope. |
| `gtd/today/TodayExtras.kt:72` (fallback when no color is found) | AC1 calendar color | Not tested. The color is drawn, not exposed in semantics. Low risk. |
| `gtd/today/TodayScreen.kt` (Hilt branch using `TodayWithEditor`) | Today inside the Hilt activity | Not tested in unit tests. The shell tests use the placeholder path. |
| `ui/CockpitShell.kt` (openNext passed to TodayScreen) | AC3 opens Next | Checked by reading only. TodayExtrasTest covers the `openNext` callback in `TodayContent`. |

## Unable to test

- Running the round 2 test, the full Android suite, `scripts/check.sh` and coverage: sub-agents cannot run Gradle or Bash. The parent must run them.
- The calendar color of an event: it is drawn and not exposed in semantics. No follow-up needed.
- The default `BirthdayContact` slot and the Hilt branch of `TodayScreen`: both need Hilt, which has no unit test harness. A device or e2e check would cover them. This is optional follow-up.

## Summary

Tests added (1 in this round). No production defects found. The fixes are covered by the fixer's tests: the parent ran them on HEAD a34f77661 and all passed, with TodayExtrasTest 20/20, TodayWithEditorTest 2/2, the other today tests passing, and lint reporting 0 errors. The one remaining gap was the CR-1 branch that keeps the failed-check message when the reload is older than the failed check, and it now has a test. The parent must run that test, the full Android suite and `scripts/check.sh`. Task-scoped coverage could not be measured here, so the figures above are estimates from reading the code.
