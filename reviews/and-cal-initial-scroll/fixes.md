# Fix Report

Date: 2026-11-22

Task: and-cal-initial-scroll - Android calendar: day/week grid opens scrolled to the earliest entry

Scope: current task, finding CR-1 from `reviews/and-cal-initial-scroll/review.md`

## Fixed findings

### CR-1 — Re-creating the grid (rotation, coming back to the tab) discards the restored scroll position

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarGridScrollTest.kt`

Problem:
In `TimeGrid`, `positionedFor` was a plain `remember`, while the scroll state (`rememberScrollState`) is saveable. After a saved-state restore of the same, already loaded period, `positionedFor` started as null and the effect scrolled to the computed hour, overwriting the restored user position.

Fix:
`positionedFor` is now `rememberSaveable` holding a String key (view name plus the ISO first and last dates, built by a new `GridPeriod.key` property), matching the existing `positionedFrom` pattern of the agenda in the same file. The scroll state was already saveable via `rememberScrollState`. A regression test (`a grid recreated for the same period (rotation, back to the tab) keeps where the user scrolled to`) uses `StateRestorationTester`: it opens the day at 14:00, swipes the grid, emulates a saved-instance-state restore and asserts the user position is kept.

Validation:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests '*CalendarGridScrollTest*' --tests '*CalendarScreensTest*'` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests '*CalendarGridScrollTest*' --tests '*CalendarScreensTest*'` — Pass (ktlintFormat made no further changes visible in the diff)
- A negative check (running the new test against the old plain `remember`) was not run: the command was denied in this environment.

Full test suite:
- Not run (parent runs `.taskcheck`).

Lint/type-check/build:
- `ktlintCheck` — Pass; Kotlin compile of main and unit tests — Pass (as part of the command above)

`.taskcheck`:
- Not run (left to the parent worker)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
