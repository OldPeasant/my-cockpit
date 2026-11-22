# Test Report

Date: 2026-11-22

Task: and-cal-longpress-create - Android calendar: long press in day/week/month creates an event there (round 2)

Scope: `git diff 50be99c2684402efa63e0966fd46ddae733bd1c4..9f5fb197b4043d49fdf21c5af654185e55eebd44 -- android`
(branch `main`, HEAD `9f5fb197b`, working tree clean). Task commits given by the parent: `3267c0af9` (implementation),
`1106e5f64` (round-1 tests), `b2cc45e05` (fixes TW-1, CR-1 to CR-4), `9f5fb197b` (one round-2 test from an
interrupted earlier round-2 run).

Requirements:
- `tasks/and-cal-longpress-create.md` acceptance criteria and recorded assumptions
- `reviews/and-cal-longpress-create/fixes.md` (TW-1, CR-1 to CR-4)
- `AGENTS.md`, `docs/ARCHITECTURE.md` "Coverage"

## Test files changed

Round 2 (this run): none. The only round-2 test change is the one already committed in `9f5fb197b`:
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarLongPressInteractionTest.kt`

Task test files overall:
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarLongPressInteractionTest.kt` (21 tests)
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarLongPressTest.kt` (5 tests)
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/CalendarWithEditorLongPressTest.kt` (3 tests)
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModelCreateAtTest.kt` (8 tests)

## Tests added or updated

Count: 1 in round 2 (`a long press calls the create callback of the latest composition, not the first one`,
committed in `9f5fb197b`); no further tests were needed.

## Fix-to-test mapping (round 2 check)

| Finding | Fix | Test that covers it |
|---|---|---|
| TW-1 cancelled gesture after recognition created the event | `CalendarLongPress.kt`: consumed up change in the release loop calls `onCancel` | `a recognised long press whose gesture is cancelled removes the ghost and creates nothing`, `a recognised long press on the all-day strip whose gesture is cancelled creates nothing` (round 1, failed before the fix) |
| CR-1 grid / all-day custom action not focusable | content description + unlabelled `onLongClick` on day column and all-day cell | `the week grid columns and the all-day strip can be focused by a screen reader and long clicked` |
| CR-2 all-day ghost grew the strip | ghost drawn with `matchParentSize()` over the entries | `the all-day ghost is drawn over the entries, so the strip keeps its height while pressed` |
| CR-3 stale `create` captured by `pointerInput` | `rememberUpdatedState(callbacks.create)` in `DayColumn` and `AllDayStrip` | `a long press calls the create callback of the latest composition, not the first one` (grid and all-day strip, recomposed with a new callback while the day key stays the same) |
| CR-4 month day announced "New event" twice | `onLongClickLabel = null`, custom action kept | `a month day offers New event once, as a custom action, and as its long click` |

Every fix has a test that targets it. The accessibility actions reading the updated `create` (part of CR-3) are not
tested with a recomposed callback; they share the same `rememberUpdatedState` value as the gestures, so no extra test
was added (small, justified gap).

## Requirement coverage

Commands: none could be run by this test writer in round 2 (see "Focused test execution"). The Result column below
is from the earlier focused run of the same test classes found in `android/app/build/test-results/testDebugUnitTest/`
(suite timestamps 2026-11-22T11:20:39Z to 11:21:16Z, after the fix commit `b2cc45e05` at 11:18:49Z, containing the
round-2 test). That run was not started by this writer and its exact input snapshot cannot be proven; the parent must
rerun the focused command before recording evidence.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. Day/week grid long press: start rounded down to 15 min, 1 hour, also on an existing event; tap opens event | `CalendarLongPressTest` (5), `a long press in the day grid shows a ghost ...`, `a long press in the week grid creates the event on the day of the pressed column`, `a long press on an existing event creates a new event there instead of opening it, a tap still opens it`, `EventEditorViewModelCreateAtTest` newForm tests | focused command below | Pass in earlier run (not rerun here) |
| 2. Haptic feedback and ghost | `a long press in the day grid shows a ghost ...`, `a long press on the all-day strip shows a ghost ...`, both cancel tests | focused command below | Pass in earlier run (not rerun here) |
| 3. All-day strip long press -> all-day event | `a long press on the all-day strip shows a ghost and creates an all-day event on that day`, `a long press on the all-day strip of the day view creates an all-day event`, `the all-day ghost is drawn over the entries ...`, `CalendarWithEditorLongPressTest` all-day test | focused command below | Pass in earlier run (not rerun here) |
| 4. Month long press with + defaults; tap opens day | `a long press on a month day creates a timed event with the default start, a tap still opens the day`, `CalendarWithEditorLongPressTest` month tests | focused command below | Pass in earlier run (not rerun here) |
| 5. Calendar chosen like +; `create` optional start/all-day; + unchanged | `EventEditorViewModelCreateAtTest` create tests, `create without a start time or all day behaves like the add button`, `newForm without a start time or all day is unchanged`, latest-callback test | focused command below | Pass in earlier run (not rerun here) |
| 6. "New event" accessibility custom action on grid, strip, month day | `each day column of the week grid offers New event at the default start`, `the all-day strip offers New event as an all-day event`, `the week grid columns and the all-day strip can be focused by a screen reader and long clicked`, `a month day offers New event once, ...` | focused command below | Pass in earlier run (not rerun here) |
| 7. No interference with swipe / scroll | `a vertical scroll of the grid creates nothing and scrolls`, `a finger that moved beyond the touch slop ...`, `a second finger before the timeout is a pinch ...`, `a horizontal swipe on the grid pages and creates nothing`, `once recognised, moving the finger neither scrolls nor pages ...`, `a horizontal swipe on the month pages and creates nothing`, `a tap on the empty grid creates nothing`, plus `CalendarSwipeInteractionTest` (17) | focused command below | Pass in earlier run (not rerun here) |
| 8. Strings in English and German | resources; German string is used by Compose tests running in the default locale only | none | Not testable by unit tests beyond resource presence; checked by `lintDebug` (parent) |
| 9. ktlintCheck, testDebugUnitTest, lintDebug, check.sh pass | whole suites | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`, `sh scripts/check.sh` | Not run: Gradle is not permitted in this sub-agent's sandbox; the parent runs them |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' --tests 'ch.sonensei.cockpit.calendar.edit.*' --console=plain`

Result:

Not run by this writer: every attempt to start `./gradlew` was refused by the sandbox permission check ("This
command requires approval"). Diagnostic only: the existing results of an earlier focused run (55 classes under
`calendar.agenda` and `calendar.edit`, suite timestamps 11:20:39Z to 11:21:16Z) show 0 failures and 0 errors in every
class, including `CalendarLongPressInteractionTest` 21 tests, `CalendarLongPressTest` 5,
`CalendarWithEditorLongPressTest` 3, `EventEditorViewModelCreateAtTest` 8. The fixer reported the same suites passing
(577 tests) and `ktlintCheck lintDebug` passing on `b2cc45e05`.

## Full test execution

Command:

`cd android && ./gradlew testDebugUnitTest`

Result:

Not run (Gradle not permitted here). The fixer reported 5714 tests, 0 failures on `b2cc45e05`.

## .taskcheck

Result:

Not run (Gradle not permitted here; `sh scripts/check.sh` runs Gradle). The parent runs it before `done`.

## Bugs found

None in round 2. TW-1 from round 1 is fixed in `b2cc45e05`; review of the fix (`CalendarLongPress.kt`, release loop)
found no new defect: a consumed up change calls `onCancel`, an unconsumed up calls `onRelease`, a missing change
calls `onCancel`.

## Coverage

Status: unavailable for round 2. Coverage could not be measured because Gradle cannot be run by this writer. The only
existing JaCoCo report (`android/app/build/reports/coverage/test/debug/report.xml`, written 2026-11-22 12:06:26 +0100,
sha256 9167c560492b91d1de1be800b6cecb4d10af67455ae3e966213f50cda4bc45b0) is the round-1 run and describes the pre-fix
revision; its numbers are kept below for reference only and are stale for the final code. Follow-up for the parent:
`cd android && ./gradlew createDebugUnitTestCoverageReport --console=plain` on HEAD `9f5fb197b` and read the five files
below from `report.xml`.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: round 1 measured 159/161 (98.8%) by intersecting JaCoCo XML line elements with the added lines
of the implementation diff; not measured for the fixed code. Changed-branch coverage not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-cal-longpress-create`, base
  `50be99c2684402efa63e0966fd46ddae733bd1c4`, HEAD `9f5fb197b4043d49fdf21c5af654185e55eebd44`, commits `3267c0af9`,
  `1106e5f64`, `b2cc45e05`, `9f5fb197b` as supplied by the parent and confirmed by `git log 50be99c2..HEAD -- android`.
  Round-1 numbers below were measured on the implementation before the fixes (commit then named `4fae7d05`, rebased to
  `3267c0af9`).
- Source/test snapshot identifier (including uncommitted inputs): HEAD `9f5fb197b`, clean tree. Test file sha256:
  `CalendarLongPressInteractionTest.kt` 291949051b861c69555ef58f50502afb5857af9f2bf8a31cefed78949f10ebbc,
  `CalendarLongPressTest.kt` b7ddacf4ed7792d1c7a2275cdff5b4d20d5c46eb3686b4934bd75976de4c7d5c,
  `CalendarWithEditorLongPressTest.kt` d90aef1c5e2a57e84ab90dfaf4fee614e64da42ab34830f7dade96a419691d69,
  `EventEditorViewModelCreateAtTest.kt` c173f74a68237d9e6d7636a215744cace93b6289bea0c352df832bba32213681.
- Exact included files (under `android/app/src/main/java/ch/sonensei/cockpit/`): `calendar/agenda/CalendarLongPress.kt`,
  `calendar/agenda/CalendarOverlayMonth.kt`, `calendar/agenda/CalendarScreen.kt`, `calendar/edit/CalendarWithEditor.kt`,
  `calendar/edit/EventEditorViewModel.kt`. Excluded: `res/values/strings_calendar_agenda.xml`,
  `res/values-de/strings_calendar_agenda.xml` (resources, not executable). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`,
  `./gradlew createDebugUnitTestCoverageReport --console=plain` in `android/`, whole debug unit suite (round 1 only).
- Run start/completion time and exit status: round 1 2026-11-22T11:02:30Z to 11:06:26Z, exit 0. No round-2 run.
- Generated report paths and report hashes/run identifier: see Status (round-1 report only).
- Evidence outputs are fresh and inputs stayed unchanged: no; the round-1 report predates the fixes.
- Final run after latest test additions and production fixes: no, Gradle not permitted for this writer.

Round-1 values (pre-fix, stale), covered/total (percentage); "Statements" are JaCoCo instructions.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `calendar/agenda/CalendarLongPress.kt` | 231/239 (96.7%) | 15/18 (83.3%) | 5/5 (100.0%) | 30/32 (93.8%) |
| `calendar/agenda/CalendarOverlayMonth.kt` | 2177/2198 (99.0%) | 46/54 (85.2%) | 29/30 (96.7%) | 227/229 (99.1%) |
| `calendar/agenda/CalendarScreen.kt` | 8974/9089 (98.7%) | 277/302 (91.7%) | 177/196 (90.3%) | 773/779 (99.2%) |
| `calendar/edit/CalendarWithEditor.kt` | 466/491 (94.9%) | 18/20 (90.0%) | 11/12 (91.7%) | 35/35 (100.0%) |
| `calendar/edit/EventEditorViewModel.kt` | 4870/4992 (97.6%) | 406/468 (86.8%) | 102/103 (99.0%) | 479/479 (100.0%) |
| Aggregate (summed counters, all 5 files measured, none missing) | 16718/17009 (98.3%) | 762/862 (88.4%) | 324/346 (93.6%) | 1544/1554 (99.4%) |

Gaps (round-1 locations; line numbers may have shifted with the fixes):

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CalendarLongPress.kt` release loop, `change == null` -> `onCancel()` | gesture taken away after recognition | No test input reaches it: Compose reports a cancel as a consumed up change, which the TW-1 fix now handles and the two cancel tests cover. Justified gap. |
| `CalendarLongPress.kt` timeout loop `change == null` branch | same unreachable case | Multi-touch, slop, tap and lift conditions are tested. Justified gap. |
| `CalendarLongPress.kt` new `cancelled` branch (TW-1 fix) | cancel after recognition | Covered by the two cancel tests (they failed before the fix); not confirmed by a coverage run. |
| `CalendarOverlayMonth.kt` partly missed `combinedClickable` / `semantics` branches | compiler-generated | Long click, tap, custom action tested. Non-material. |
| `CalendarScreen.kt` default `onCreate` of `CalendarRoute` | default parameter | Only `CalendarWithEditor` passes it, which is tested. Non-material. |
| `CalendarScreen.kt` accessibility actions with a recomposed `create` (CR-3) | stale callback via accessibility | Uses the same `rememberUpdatedState` value as the gestures tested by the latest-callback test. Small, justified gap. |

## Unable to test

- Gradle (focused tests, ktlintCheck, coverage, full suite, check.sh) could not be run by this writer in round 2: the
  sandbox refuses `./gradlew`. Follow-up: the parent runs the focused command, `ktlintCheck`, the coverage command and
  `.taskcheck` on HEAD `9f5fb197b`.
- Real haptic output on a device: only the `HapticFeedbackType.LongPress` request is tested (fake `LocalHapticFeedback`).
- TalkBack on a device or emulator: semantics (content description, long-click action, single "New event" custom
  action) are asserted, not the real announcement.
- The exact platform long-press timeout: the tests hold for 1000 ms, longer than the platform timeout.

## Summary

No additional tests were necessary for the reviewed scope; no production defects found. Every round-1 finding (TW-1, CR-1 to
CR-4) has a targeted test, no further tests were needed. The focused tests, ktlintCheck and a fresh coverage run
could not be executed by this writer (Gradle not permitted in the sandbox) and must be run by the parent; the
coverage numbers above are the stale round-1 numbers.
