# Fix Report

Date: 2026-11-23

Task: and-gtd-tickler-chip (Android Tickler chip)

Scope: current task, findings CR-1 and CR-2 from reviews/and-gtd-tickler-chip/review.md

## Fixed findings

### CR-2 — Stale tickler "required" problem after switching away from Tickler and back

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskDetailViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskDetailViewModelRelistTest.kt`

Problem:
`changed(TaskField.TICKLER, a, b)` compared only `ticklerDate`. After a save with the Tickler chip chosen and no
date (TICKLER Required), picking another chip sets `tickler = false` but leaves `ticklerDate = null`, so the
problem stayed in `state.problems` and showed again as soon as Tickler was picked again, before any save.

Fix:
The TICKLER case now reads `a.ticklerDate != b.ticklerDate || a.tickler != b.tickler`, so changing the tickler
choice clears the field's old problem, like the SCHEDULED and WAITING_FOR cases already do with the list.
Regression test `leaving the Tickler chip clears its required problem, choosing it again shows none before saving`
in TaskDetailViewModelRelistTest: save with Tickler and no date gives TICKLER Required; picking Next clears it;
picking Tickler again shows no problem and nothing was sent.

Validation:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.*'` — first
  run: ktlintFormat reformatted the new test, the same run's ktlintTestSourceSetCheck still reported the pre-format
  chain style; rerun of `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.*'` — Pass
  (BUILD SUCCESSFUL, 1279 tests in the result files, 0 failures, 0 errors, 0 skipped; the new test is among them)

### CR-1 — Inbox tickler prompt and Next ViewModel wiring are untested

Files changed:
- none (already covered by tests the test-writer added)

Problem:
The review found no test for the Inbox Tickler prompt title and hint, and no NextViewModel test for the
"new today" order and for filters and presets still applying to released-today tasks.

Fix:
No code change needed; verified coverage now exists:
- `InboxScreenTest` `the tickler prompt names the Tickler and explains it, the schedule prompt has no such hint`
  asserts the title "Tickler: hidden until" and the hint text, and that the Schedule prompt has no hint.
- `NextViewModelNewTodayTest` (fixed clock, mocked repository) checks `uiState.tasks` order with released-today
  tasks first, that a context filter and an energy filter still hide a non-matching released-today task, that a
  preset still applies, that the order is normal from the next day, and that "today" is the local date.

Validation:
- Same gtd test run as above — Pass (both classes ran with no failures)

## Not fixed

None.

## Validation summary

Focused tests:
- `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.*'` (in android/) — Pass

Full test suite:
- Not run (focused gtd package run as requested by the parent worker)

Lint/type-check/build:
- `./gradlew ktlintFormat ktlintCheck` (in android/) — Pass after format

`.taskcheck`:
- Not run (left to the parent worker)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
