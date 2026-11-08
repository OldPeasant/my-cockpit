# Fix Report

Date: 2026-11-08

Task: and-cal-overlay-month - Android calendar: task overlay and month view

Scope: the findings CR-1, CR-2 and CR-3 in `reviews/and-cal-overlay-month/review.md`. Changes stay inside the task's `touches`: `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda`, its test folder, and the two `strings_calendar_agenda.xml` files. Base commit 3a0ce0144d57f5777b06c50327ae32f3e99ab15c, task commits ab36036c, 165cee44 and 9f66cba9.

## Fixed findings

### CR-1 — A task scheduled for today never shows on today

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/TaskOverlay.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/TaskOverlayMarksTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/TaskOverlayRepositoryTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarViewModelOverlayTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarOverlayMonthScreensTest.kt` (only the `TaskOverlaySource` lambda arity)

Problem:
The finding is valid. `TaskController.list` runs the tickler before every read. `TaskService.releaseDue` then moves tasks scheduled for today or earlier to NEXT, clears `scheduledDate` and sets `releasedOn = today`. The overlay only used `list=SCHEDULED` filtered by `scheduledDate`, so tasks scheduled for today never got a mark.

Fix:
- `TaskOverlaySource.tasks` now also takes `today`. The view model passes `state.today`, which is in the user's synced time zone.
- When today is in [from, to), `TaskOverlayRepository` also reads `GET /tasks?scheduled=today` (an existing server filter: NEXT with `releasedOn = today`, plus SCHEDULED for today or earlier). It keeps the tasks with `releasedOn == today`. When today is not in the range, it makes no extra request.
- `OverlayTask` has a new `releasedOn` field, added as the last parameter so existing positional calls still work. `toOverlayTask` maps it.
- `taskMarks(tasks, from, to, today)` adds a SCHEDULED mark on today for a task with `releasedOn == today` when today is in the range. If the task still has an in-range `scheduledDate`, that date wins and no second mark is added. `CalendarUiState.taskMarks` passes `today`.
- New tests:
  - marks: a released task is marked on today, an earlier release is not, `scheduledDate` wins, and there is no mark when today is outside the range or unknown;
  - repository: the third request is `scheduled=today` with no `list` or `from`, results are filtered by `releasedOn`, and completed tasks are dropped;
  - view model: `today` is passed to the source, the week containing today shows the SCHEDULED mark, and the next week shows none.

Validation:
- `cd android && ./gradlew ktlintFormat testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*'` — Pass
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` — Pass

### CR-2 — The toolbar does not fit four segments plus Today and two toggles on 360dp

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt`
- `android/app/src/main/res/values/strings_calendar_agenda.xml`
- `android/app/src/main/res/values-de/strings_calendar_agenda.xml`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarOverlayMonthScreensTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreensTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreensCachedTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreensStatesTest.kt`

Problem:
The finding is valid, based on the layout arithmetic: about 43dp per segment on a 360dp phone, below the Material minimum of 58dp.

Fix:
- The "Today" text button is now an `IconButton` (`Icons.Outlined.Today`). Its content description is the existing `calendar_today` string, and it keeps the `CalendarTags.TODAY` tag.
- The overlay and calendars toggles moved into an overflow menu: a `MoreVert` `IconButton` with tag `CalendarTags.MENU` and the new content description `calendar_more_options` ("More options" / "Weitere Optionen").
- Each menu item is a `DropdownMenuItem` with an icon, a label and a trailing `Checkbox`. It keeps its old tag (`CalendarTags.OVERLAY`, `CalendarTags.CALENDARS`) and has `Role.Checkbox` plus `ToggleableState` semantics, so screen readers announce the state and tests can check it with `assertIsOn`/`assertIsOff`. Choosing an item closes the menu.
- The segment labels now get `TextOverflow.Ellipsis` and 4dp horizontal content padding.
- Fixed toolbar width is now 2 x 48dp. On 360dp that leaves about 244dp, or about 61dp per segment (about 53dp for the label), up from about 43dp. This is layout arithmetic only: I have not rendered it on a device or in a screenshot.
- The screen tests open the menu before using the toggles. The overlay test checks that the toggle is hidden until the menu opens, that its label and on/off state are correct, and that the menu closes after a choice. The German test checks both menu items. The string test covers `calendar_more_options` in English and German.

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*'` — Pass
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` — Pass

### CR-3 — Agenda task append starts before the extended range is in the state

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarViewModelOverlayTest.kt`

Problem:
The finding is valid. `loadMore` called `loadTasks(from, to, append = true)` before the `state.update` that sets `to`.

Fix:
- `loadTasks(..., append = true)` now runs after that `state.update`, still only for an `Ok` page.
- Regression test `appended tasks are kept when the source answers without suspending`: Main is set to a direct executor dispatcher, so launched coroutines run inline. I confirmed that this test fails with the old ordering ("expected [1, 2] but was [1]") and passes with the fix.

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.CalendarViewModelOverlayTest'` — Pass (and Fail with the old ordering, as intended)
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintFormat testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*'` — Pass

Full test suite:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` — BUILD SUCCESSFUL

Lint/type-check/build:
- Included in the command above: ktlintCheck and lintDebug pass.

`.taskcheck`:
- Not run by the fixer. The task notes say `sh scripts/check.sh` currently fails in the server tests because Docker is not available on this host, which is unrelated to this task. The Android part of the check passes (see above).

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
