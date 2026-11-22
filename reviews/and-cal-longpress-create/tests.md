# Test Report

Date: 2026-11-22

Task: and-cal-longpress-create (Android calendar: long press in day/week/month creates an event there)

Scope: task base `8c484d190a8b2bff5a2ed3c26e3efd89bcf5bf0b`, implementation commit `4fae7d052413902daa52471c83b0dc6fcdffe143`
(`git diff 8c484d19..4fae7d05`), supplied by the parent worker. Measured HEAD `550c6ec42cda00ecbad4081538f0ba614f41b30d`
(adds only `reviews/and-cal-longpress-create/review.md`; `git diff 4fae7d05 HEAD -- android/app/src/main` is empty).
Branch `main`. Uncommitted inputs: only the four new, untracked test files listed below.

Requirements:
- `tasks/and-cal-longpress-create.md` acceptance criteria and its two recorded assumptions (strip always shown; grid
  editor opens on release, accessibility action on the grid uses the default start)
- `AGENTS.md`, `docs/ARCHITECTURE.md` ("Coverage"), parent worker instructions
- KDoc contract of `Modifier.longPressAt` (`onCancel` "when the gesture is taken away")

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarLongPressTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarLongPressInteractionTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModelCreateAtTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/CalendarWithEditorLongPressTest.kt` (new)

No existing test was changed.

## Tests added or updated

Count: 34 (5 + 18 + 8 + 3)

## Requirement coverage

Focused command (from `android/`): `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.CalendarLongPress*' --tests 'ch.sonensei.cockpit.calendar.edit.EventEditorViewModelCreateAtTest' --tests 'ch.sonensei.cockpit.calendar.edit.CalendarWithEditorLongPressTest'` (Robolectric, JVM unit tests).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Grid press rounded down to 15 min, clamped to the day | `CalendarLongPressTest`: `a press is rounded down to the quarter hour it falls into`, `exactly on a quarter hour starts there, just before it starts a quarter earlier`, `the top of the column is midnight and a press above it is clamped to midnight`, `the last quarter of the day is the latest start, also for a press below the column`, `the rounding follows the density of the grid` | focused command | Pass (5/5) |
| Day view grid long press opens new event at rounded time | `a long press in the day grid shows a ghost of the rounded slot and creates the event there when lifted` | focused command | Pass |
| Week view grid long press on the pressed column's day | `a long press in the week grid creates the event on the day of the pressed column` | focused command | Pass |
| Long press on an existing event creates; a tap still opens it | `a long press on an existing event creates a new event there instead of opening it, a tap still opens it` | focused command | Pass |
| Haptic feedback and ghost block while held (1 hour high, at the slot) | day grid test above (fake `LocalHapticFeedback`, ghost bounds), week grid test, both all-day strip tests | focused command | Pass |
| All-day strip long press opens an all-day event (strip shown without entries) | `a long press on the all-day strip shows a ghost and creates an all-day event on that day`, `a long press on the all-day strip of the day view creates an all-day event`, `CalendarWithEditorLongPressTest`: `a long press on the all-day strip of the day view opens a new all-day event on that day` | focused command | Pass |
| Month long press opens timed event with + button defaults; tap opens the day | `a long press on a month day creates a timed event with the default start, a tap still opens the day`, `CalendarWithEditorLongPressTest`: `a long press on a month day opens a new event there with the add button's defaults` (today: 11:00), `a long press on another month day starts at the default start` (09:00) | focused command | Pass |
| Calendar chosen like the + button; `create` optional start / all-day; + button unchanged | `EventEditorViewModelCreateAtTest` (8 tests: newForm default unchanged, with start time, start 23:45 ending next day, all day, VM create with start / all day / hidden-only fallback / no arguments) and the editor integration tests (feed and hidden calendar before Work: Work is chosen) | focused command | Pass (8/8, 3/3) |
| "New event" accessibility custom action on grid columns, all-day strip, month days | `each day column of the week grid offers New event at the default start`, `the all-day strip offers New event as an all-day event`, `a month day offers New event, and its long click is labelled the same` | focused command | Pass |
| Long press does not interfere with swipe / vertical scroll | `a vertical scroll of the grid creates nothing and scrolls`, `a finger that moved beyond the touch slop and then rests is a scroll, not a long press`, `a horizontal swipe on the grid pages and creates nothing`, `a horizontal swipe on the month pages and creates nothing`, `once recognised, moving the finger neither scrolls nor pages and the pressed slot is created`, `a second finger before the timeout is a pinch, not a long press`, `a tap on the empty grid creates nothing` | focused command | Pass |
| A gesture taken away after recognition (ACTION_CANCEL) creates nothing (`longPressAt` KDoc: `onCancel`; assumption: editor opens when the finger is lifted) | `a recognised long press whose gesture is cancelled removes the ghost and creates nothing`, `a recognised long press on the all-day strip whose gesture is cancelled creates nothing` | `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.CalendarLongPressInteractionTest'` | Fail (TW-1) |
| New strings in English and German | not a test; `calendar_new_event_here` present in `res/values/strings_calendar_agenda.xml` ("New event") and `res/values-de/` ("Neuer Termin"), checked by grep; the custom action tests run with `en-rUS` and find "New event" | grep | Pass (inspection) |
| ktlint / testDebugUnitTest / lintDebug and `sh scripts/check.sh` pass | ktlint run on the new tests; full unit suite run via coverage (before the TW-1 tests); lintDebug and check.sh not run by the test writer | see below | Partly run |

## Focused test execution

Command (from `android/`):

`./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.CalendarLongPress*' --tests 'ch.sonensei.cockpit.calendar.edit.EventEditorViewModelCreateAtTest' --tests 'ch.sonensei.cockpit.calendar.edit.CalendarWithEditorLongPressTest'`

Result:

Pass for the first 32 tests (31 in the first run, the multi-touch test added after). After the two cancel tests were
added: `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.CalendarLongPressInteractionTest'`
-> 18 tests completed, 2 failed (both TW-1). The failing tests are kept deliberately.

`./gradlew ktlintCheck` -> pass (after `./gradlew ktlintTestSourceSetFormat` reformatted the new test files).

## Full test execution

Command:

`./gradlew createDebugUnitTestCoverageReport --console=plain` (from `android/`, runs the whole `testDebugUnitTest`)

Result:

Pass: 5710 tests, 0 failures, 0 errors, 0 skipped (2026-11-22, 11:02:30Z to 11:06:26Z, exit 0). This run included
the 32 passing new tests and predates the two TW-1 tests. With them, the Android unit suite currently fails on exactly
those two tests.

## .taskcheck

Result:

Not run: `sh scripts/check.sh` would fail on the two intentionally kept TW-1 tests; the parent runs it after the fix.
`lintDebug` was not run either (no production or resource change by the test writer).

## Bugs found

### A cancelled gesture after a recognised long press creates the event as if the finger was lifted

Finding ID:

`TW-1`

Test:

`CalendarLongPressInteractionTest.a recognised long press whose gesture is cancelled removes the ghost and creates nothing`
and `CalendarLongPressInteractionTest.a recognised long press on the all-day strip whose gesture is cancelled creates nothing`

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarLongPress.kt:69` (the `if (!change.pressed) { onRelease() }` branch after recognition)

Requirement:

The editor opens when the finger is lifted (recorded assumption); `longPressAt` documents `onCancel` "when the gesture
is taken away". A gesture the system takes away (ACTION_CANCEL: an edge back gesture, the notification shade, a dialog
or window focus change) is not a release and should not open the editor.

Expected behavior:

After the long press is recognised (ghost shown), an ACTION_CANCEL removes the ghost and `CalendarCallbacks.create` is
not called.

Actual behavior:

Compose turns ACTION_CANCEL into a synthetic event in which every pointer is up (and consumed); the loop sees
`!change.pressed` and calls `onRelease`, so `create(day, 8:30, false)` (grid) or `create(day, null, true)` (strip) is
called and the editor opens. The ghost is removed. The `onCancel` branch (`change == null`, lines 65-66) is never
reached by any test input. A possible fix: in the Initial pass nothing consumes before this modifier, so an up change
that is already consumed (or otherwise marked as a cancellation) should go to `onCancel` instead of `onRelease`.

Severity:

Medium (an unwanted new-event editor opens after a system gesture; no data is saved without the user saving)

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Coverage

Status: verified for the snapshot before the two TW-1 tests were added; stale for the final test set (the two later
tests fail, so a new coverage run would exit nonzero). The parent must rerun coverage after the TW-1 fix anyway,
because it changes production code.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured separately by intersecting JaCoCo XML line elements (lines with instructions) of each
file with the added lines of `git diff -U0 8c484d19..4fae7d05` (a line counts as covered when `ci > 0`): 159/161
(98.8%). Changed-branch coverage not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-cal-longpress-create`, base
  `8c484d190a8b2bff5a2ed3c26e3efd89bcf5bf0b`, implementation `4fae7d052413902daa52471c83b0dc6fcdffe143` (given by the parent,
  only task commit since the base besides the review report), measured HEAD `550c6ec42cda00ecbad4081538f0ba614f41b30d`
  with identical `android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `550c6ec4` plus untracked test files with sha256
  at the coverage run: `CalendarLongPressInteractionTest.kt` df047f716d7852dab8196643af507d3a799539029dee979be0b27022fe382d56
  (16 tests), `CalendarLongPressTest.kt` b7ddacf4ed7792d1c7a2275cdff5b4d20d5c46eb3686b4934bd75976de4c7d5c,
  `EventEditorViewModelCreateAtTest.kt` c173f74a68237d9e6d7636a215744cace93b6289bea0c352df832bba32213681,
  `CalendarWithEditorLongPressTest.kt` d90aef1c5e2a57e84ab90dfaf4fee614e64da42ab34830f7dade96a419691d69. Final
  `CalendarLongPressInteractionTest.kt` (18 tests, adds the two TW-1 tests) 5c3ffe2fd49976d1d9517c5bc9ea8fa428204cd67e4339e552bca20cd395084d.
- Exact included files: `calendar/agenda/CalendarLongPress.kt`, `calendar/agenda/CalendarOverlayMonth.kt`,
  `calendar/agenda/CalendarScreen.kt`, `calendar/edit/CalendarWithEditor.kt`, `calendar/edit/EventEditorViewModel.kt`
  (under `android/app/src/main/java/ch/sonensei/cockpit/`). Excluded: `res/values/strings_calendar_agenda.xml` and
  `res/values-de/strings_calendar_agenda.xml` (resources, not executable). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`,
  `./gradlew createDebugUnitTestCoverageReport --console=plain` in `android/`, whole debug unit suite (Gradle 9.8.1).
- Run start/completion time and exit status: 2026-11-22T11:02:30Z to 11:06:26Z, exit 0 (`testDebugUnitTest` executed, not
  up to date: 5710 tests, 0 failures).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`,
  sha256 9167c560492b91d1de1be800b6cecb4d10af67455ae3e966213f50cda4bc45b0, written 2026-11-22 12:06:26 +0100.
- Evidence outputs are fresh and inputs stayed unchanged: yes; the report was rewritten by this run, the test and
  production inputs were unchanged during it (hashes taken before, tests edited only after completion).
- Final run after latest test additions and production fixes: no. The two TW-1 tests were added after the run (they
  exercise the already covered `onRelease` path), and TW-1 is not fixed yet.

Values below use covered/total (percentage); "Statements" are JaCoCo instructions.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `calendar/agenda/CalendarLongPress.kt` | 231/239 (96.7%) | 15/18 (83.3%) | 5/5 (100.0%) | 30/32 (93.8%) |
| `calendar/agenda/CalendarOverlayMonth.kt` | 2177/2198 (99.0%) | 46/54 (85.2%) | 29/30 (96.7%) | 227/229 (99.1%) |
| `calendar/agenda/CalendarScreen.kt` | 8974/9089 (98.7%) | 277/302 (91.7%) | 177/196 (90.3%) | 773/779 (99.2%) |
| `calendar/edit/CalendarWithEditor.kt` | 466/491 (94.9%) | 18/20 (90.0%) | 11/12 (91.7%) | 35/35 (100.0%) |
| `calendar/edit/EventEditorViewModel.kt` | 4870/4992 (97.6%) | 406/468 (86.8%) | 102/103 (99.0%) | 479/479 (100.0%) |
| Aggregate (summed counters, all 5 files measured, none missing) | 16718/17009 (98.3%) | 762/862 (88.4%) | 324/346 (93.6%) | 1544/1554 (99.4%) |

Per-file changed executable lines covered: CalendarLongPress.kt 30/32, CalendarOverlayMonth.kt 26/26,
CalendarScreen.kt 85/85, CalendarWithEditor.kt 1/1, EventEditorViewModel.kt 17/17.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CalendarLongPress.kt:65-66` (`change == null` -> `onCancel()`) | gesture taken away after recognition | No test input reaches it: Compose reports a cancel as an up change, not a missing one. Cancel tests added; they expose TW-1. |
| `CalendarLongPress.kt:51, 55, 64` (partly missed branches) | `change == null` in the timeout loop and in the release loop, part of the multi-condition end check | Same unreachable `change == null` case; multi-touch, slop, tap and lift conditions are covered by tests. Justified gap. |
| `CalendarOverlayMonth.kt:299, 305` (partly missed branches) | Compose-generated branches of `combinedClickable` / `semantics` lambdas | Long click, tap and custom action are all tested; remaining branches are compiler-generated. Justified gap. |
| `CalendarOverlayMonth.kt:256`, `CalendarScreen.kt:149, 933, 1206` (partly missed branches) | default parameter / composable-generated branches (`onCreate` default lambda of `CalendarRoute`, `DayColumn` call) | `CalendarRoute` with the default `onCreate` is not called with a long press anywhere (only `CalendarWithEditor` passes it, which is tested). Non-material. |
| `CalendarScreen.kt:150, 289, 445, 543, 756, 778`, `CalendarOverlayMonth.kt:321, 405` | pre-existing code, not changed by the task | Outside the task's changes. |

## Unable to test

- Real haptic output on a device: tested only that `HapticFeedbackType.LongPress` is requested once (fake
  `LocalHapticFeedback`). No follow-up needed.
- The exact platform long-press timeout on devices: the tests hold for 1000 ms, longer than the 400 to 500 ms timeout.
- TalkBack announcing the custom action: the semantics action is present and invokes `create`; an on-device
  accessibility check was not run.
- `lintDebug` and `sh scripts/check.sh` were not run by the test writer (see .taskcheck).

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion (TW-1:
a cancelled gesture after a recognised long press opens the new-event editor).
