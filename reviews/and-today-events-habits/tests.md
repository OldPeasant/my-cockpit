# Test Report

Date: 2026-11-08

Task: and-today-events-habits - Android Today shows events and habits

Scope: `git diff 0f47c5801e7f1e8b591d3c9cf3418b207dceaa66..HEAD`. Task commits cb4ec48ec (implementation) and 1556df62f (tests). The parent supplied the base and the commit list. The test-writer edits below are uncommitted. The test-writer could not run Bash or git, so it took the file list from the parent and from the task `touches`, and checked it by reading the files.

Requirements:
- tasks/and-today-events-habits.md (acceptance criteria and the two recorded assumptions)
- AGENTS.md project notes (tests, ktlint style, fictitious data)
- The habit check semantics in `habits/HabitsRepository.kt` (`Habit.nextCount`, from and-habits-manage)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/gtd/today/TodayExtrasTest.kt`

## Tests added or updated

Count: 4 added, all in `TodayExtrasTest`. The 10 existing tests are unchanged.

- `a second tap while the check is being sent is ignored`: the view model's `checking` guard. A double tap sends one check. The button stays busy until the result arrives, and then the returned habit is shown. Uses a `CompletableDeferred`, not real delays.
- `a successful check clears the message of an earlier failed one`: the first check is refused (Failed 400) and sets `checkFailed`. The next check succeeds and clears it.
- `archived habits are left out and no events show while the calendars cannot be loaded`: tests the `!habit.archived` filter, plus the branch where `calendars()` fails (Offline) and no events are set.
- `a done habit offers undo without a count and an event shows its location`: a Compose test. A done habit with target 1 has the content description "Undo Stretch" and shows no "1/1" count. The event location "Room 2" is rendered.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: Today lists today's events (time, title, calendar color; a tap opens the editor) | `load lists today's visible events all-day first and only the habits due today`, `event times leave out the side outside today`, `events show their time and title, and a tap opens the event`, `a done habit offers undo without a count and an event shows its location` (new), `archived habits are left out and no events show while the calendars cannot be loaded` (new) | parent: `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.today.*'` | Pass for the 10 existing tests (run by the parent). Not run for the new tests: sub-agents cannot run Gradle, so the parent must run them. The calendar color (a drawn dot) is not asserted. The editor or birthday contact opening in `TodayWithEditor` has no test (see Unable to test). |
| AC2: habits due today with a check button (increment up to target, then back to none) | `a check sends the next count and shows the habit the server returns`, `a failed check keeps the habit and says so`, `habits due today show their count and the check button checks them`, `a habit being checked has its button disabled and a failed check shows a message`, plus the new `a second tap while the check is being sent is ignored`, `a successful check clears the message of an earlier failed one`, `a done habit offers undo without a count and an event shows its location` | same | Pass for the existing tests. Not run for the new tests (parent must run them). The view model hands the habit to `HabitsRepository.check`, which owns the `nextCount` rule from and-habits-manage, so this task reuses that rule rather than adding its own. |
| AC3: an empty Today offers "Pick a next action" (opens Next) | `an empty day offers to pick a next action, also with events`, `the empty day action shows without events or habits` | same | Pass (run by the parent). The `openNext` wiring in `ui/CockpitShell.kt` is only checked by reading it. |
| AC4: new strings in English and German | checked by reading `res/values/strings_next_today.xml` and `res/values-de/strings_next_today.xml`. All 9 new `today_*` keys are in both files with matching format arguments. | read only | Pass (by reading). Android `lintDebug` (MissingTranslation) is the executable check, and the parent must run it. |
| AC5: tests cover the new behavior; ktlintCheck, testDebugUnitTest, lintDebug and check.sh pass | all of the above | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`, `sh scripts/check.sh` | Not run by the test-writer (no Gradle or shell access). The parent must run them. |

## Focused test execution

Command:

`cd android && ./gradlew ktlintFormat testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.today.*'` (run by the parent before these additions)

Result:

Pass, as the parent reported: TodayExtrasTest 10/10, TodayScreenTest 15, TodayRouteTest 2, TodayViewModelTest 28. The 4 new tests have not been run yet. The parent must run `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.today.TodayExtrasTest'`.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Not run by the test-writer: the sub-agent cannot use Gradle or Bash. The parent must run it.

## .taskcheck

Result:

Not run by the test-writer (no shell access). The parent must run `sh scripts/check.sh`.

## Bugs found

No confirmed production defect. The new tests are written to pass against the current code.

Three low-severity observations from reading the code. None has a failing test, and none is a confirmed requirement violation. The parent decides whether any of them is in scope:

- `TodayExtrasViewModel.load()` and `check()` can race. Suppose a load starts (for example when the editor closes), then a check finishes, then the load's older habits list arrives. The list overwrites the checked habit with its state from before the check, until the next load. This is unlikely because loads run only when Today opens or the editor closes.
- `checkFailed` is cleared only by the next check, not by a reload. So the failure message stays after a later successful load.
- `eventTime` shows "00:00–00:00" for a timed event that runs exactly from today 00:00 to tomorrow 00:00. It covers all of today, so the KDoc suggests `null` ("All day"). This is cosmetic.

## Coverage

Status: unavailable. The test-writer sub-agent cannot run Gradle (or any shell command) here, so no coverage tool ran. No numbers from earlier runs or for the whole repository are used in place of a measurement.

Metric: whole-file coverage of task-changed production files (not measured).
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-today-events-habits, base 0f47c5801e7f1e8b591d3c9cf3418b207dceaa66, HEAD 1556df62f. The commits (cb4ec48ec, 1556df62f) and the file list came from the parent and match the task `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 1556df62f plus the uncommitted edit of `TodayExtrasTest.kt` described above. No hash was computed because there was no shell access.
- Exact included files: `gtd/today/TodayExtrasViewModel.kt`, `gtd/today/TodayExtras.kt`, `gtd/today/TodayWithEditor.kt`, `gtd/today/TodayScreen.kt`, `ui/CockpitShell.kt` (one line). Excluded: the string resources, which contain no executable code. No files were renamed or deleted.
- Provider/version, command, working directory, and test selection: none ran. The project's coverage command (docs/ARCHITECTURE.md "Coverage") has to be run by the parent.
- Run start/completion time and exit status: N/A.
- Generated report paths and report hashes/run identifier: N/A.
- Evidence outputs are fresh and inputs stayed unchanged: N/A.
- Final run after latest test additions and production fixes: no. There was no run at all.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `gtd/today/TodayExtrasViewModel.kt` | N/A (not measured) | N/A | N/A | N/A |
| `gtd/today/TodayExtras.kt` | N/A (not measured) | N/A | N/A | N/A |
| `gtd/today/TodayWithEditor.kt` | N/A (not measured) | N/A | N/A | N/A |
| `gtd/today/TodayScreen.kt` | N/A (not measured) | N/A | N/A | N/A |
| `ui/CockpitShell.kt` | N/A (not measured) | N/A | N/A | N/A |

Estimate from reading the code (not a measurement):

- `TodayExtrasViewModel.kt`: every branch is probably covered once the 4 new tests are included. That means load success, occurrences failing, calendars failing, the hidden-calendar filter, the archived and not-due filters, and check Ok, failed, guarded and failure-cleared.
- `TodayExtras.kt`: covered are the empty and non-empty event and habit sections, the failure message, the disabled button, the all-day label, the location, the count shown and hidden, the done and not-done icon, and every `eventTime` branch. Not covered: the fallback to the primary color when an event's calendar is not in the list, and a blank location.
- `TodayScreen.kt`: the `hasExtras` changes to the loading, error and list branches, and EmptyDay, are covered by TodayExtrasTest and TodayScreenTest. Not covered: `TodayScreen` taking the Hilt branch.
- `TodayWithEditor.kt`: probably not covered at all.
- `CockpitShell.kt`: the one-line change is covered only through the shell navigation tests' placeholder path, if at all.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `gtd/today/TodayWithEditor.kt:22-79` | AC1: a tap opens the event editor, a birthday opens its contact, and events and habits reload when the editor closes | No test added. It needs three Hilt view models (`hiltViewModel()` defaults) and the calendar editor. The existing TodayRouteTest pattern does not set this up, and adding it is more than a focused test. Gap reported for follow-up or a manual check. |
| `gtd/today/TodayScreen.kt:55-56` | Today uses `TodayWithEditor` inside a Hilt activity | Not tested. The Hilt activity path is not exercised in unit tests (the shell tests use the placeholder). |
| `gtd/today/TodayExtras.kt:72-73` (fallback when no color is found) | AC1 calendar color | Not tested. The color is drawn, not exposed in semantics. Low risk. |
| `ui/CockpitShell.kt` (openNext passed to TodayScreen) | AC3 opens Next | Checked by reading only. TodayExtrasTest covers the `openNext` callback in `TodayContent`. |

## Unable to test

- Running any test, lint or coverage: sub-agents are blocked from Gradle and Bash. The parent must run the 4 new tests, the full Android suite with lint, and `scripts/check.sh`.
- The `TodayWithEditor` wiring (editor or birthday contact opens, reload when the editor closes): it depends on Hilt and the calendar editor view model, and there is no existing test harness for it. Follow-up: a manual or e2e check on a device, or a later task to give it a testable seam.
- The calendar color of an event: it is drawn and not exposed in semantics.

## Summary

Tests were added (4 new tests in TodayExtrasTest). No production defects were confirmed, and three low-severity observations are listed for the parent to judge. The new tests and all of the validation have not been run here, because the test-writer cannot run Gradle, so the parent must run them before sign-off. Task-scoped coverage could not be measured. The `TodayWithEditor` wiring (AC1 editor opening and reload) has no tests and needs follow-up or a manual check.
