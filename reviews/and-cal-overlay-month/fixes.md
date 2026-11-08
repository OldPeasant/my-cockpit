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

# Round 2

Date: 2026-11-08

Task: and-cal-overlay-month - Android calendar: task overlay and month view

Scope: the second-round findings CR-1, CR-2 and CR-3 in `reviews/and-cal-overlay-month/review.md` (review of 9ed341a7). Base commit 5566785171fec9656083bc55f4ef3b86d1cfa655. The parent worker committed a draft fix as 69436d20. The fixer checked that draft against each finding and added one hardening change (CR-2), which is left uncommitted in the working tree for the parent worker. All changes stay inside the task's `touches`.

## Fixed findings

### CR-1 — The event FAB covers the bottom-right month cells, so those days cannot be opened

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarOverlayMonth.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarMonthLayoutTest.kt` (new)

Problem:
The finding is valid. `CalendarWithEditor` always draws a 56dp FAB with a 16dp margin at `BottomEnd` over `CalendarRoute`. `MonthGrid` fills the height and does not scroll, so the FAB covered the last week's two rightmost cells.

Fix (draft 69436d20, checked and kept):
`MonthGrid` keeps `MONTH_FAB_SPACE = 72.dp` (56dp FAB plus 16dp margin) free at its bottom, so the grid ends where the FAB starts. The FAB is drawn in the same parent `Box` as the calendar and is always shown, so the space is always needed. The editor file (`calendar/edit`) is outside the task's touches and was not changed.

Tests:
- `CalendarMonthLayoutTest` "the floating button does not cover the last week's cells, so tapping them opens the day": 360x640dp, a 6-week month (November 2026, Monday start), the FAB placed as `CalendarWithEditor` places it. The last-row Saturday and Sunday cells are displayed, end above the FAB's top edge, and a tap opens the day.

Validation:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` — Pass

### CR-2 — The "+n" line can be clipped away in 5-6 week months

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarOverlayMonth.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/MonthTitlesShownTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarMonthLayoutTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarOverlayMonthScreensTest.kt` (the existing "three titles" test runs at 412x915dp, where three titles fit)

Problem:
The finding is valid. The cell column listed up to 3 titles without regard to its height, so the last child ("+n") lost its space first.

Fix (draft 69436d20, checked, plus one hardening change):
- `MonthDay` is a `BoxWithConstraints`. It computes how many lines fit under the 22dp day circle: (cell height - 22dp) / (max(labelSmall line height, 14dp event bar) + 1dp spacing). The line height is converted with the current density, so it follows the font scale.
- `monthTitlesShown(count, lines)` lists all entries when they fit (at most 3). Otherwise it lists at most min(3, lines - 1) and keeps one line for "+n". With no room it lists none.
- Fixer addition: `TextUnit.toDp()` throws for `TextUnit.Unspecified`. The app theme uses Material's default typography (labelSmall 16sp), so this does not happen today. The conversion now falls back to 16sp when a theme sets no line height, so a theme change cannot crash the month view.
- Checked edge cases: unbounded height falls back to 3 titles plus "+n". A cell with 0 lines shows only "+n", which is clipped. This only happens in cells too low for a single line, and a tap still opens the day with everything listed.

Tests:
- `MonthTitlesShownTest`: high cells (up to 3 titles), low cells (fewer titles, one line kept for "+n"), all entries fit (no "+n"), no room or negative lines.
- `CalendarMonthLayoutTest` "a busy day of a six-week month keeps its +n inside the cell…": 5 entries on the last day at 360x640dp. "+n" is displayed with a height above zero and inside the cell's bounds, the listed titles plus n add up to 5, and the cell description says "5 entries".

Validation:
- `cd android && ./gradlew ktlintFormat` then `./gradlew ktlintCheck testDebugUnitTest lintDebug` — Pass

### CR-3 — The tasks error line stays after a range change and has no retry

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarOverlayMonth.kt`
- `android/app/src/main/res/values/strings_calendar_agenda.xml`
- `android/app/src/main/res/values-de/strings_calendar_agenda.xml`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarViewModelOverlayTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarMonthLayoutTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarOverlayMonthScreensTest.kt` (new string in the EN/DE string check)

Problem:
The finding is valid. `show()` kept `tasksFailed` when the range changed, and `TasksFailedLine` had no retry.

Fix (draft 69436d20, checked and kept):
- `show()` sets `tasksFailed = it.tasksFailed && sameRange`, so a new range starts without the old error. Showing the same range again keeps the error.
- `CalendarViewModel.retryTasks()` reloads the shown range's tasks as a replace load. `loadTasks` returns early while the overlay is off, and it cancels a replace load that is still running. A failed append in the agenda is covered too, because the replace load reloads the whole [from, to).
- `CalendarCallbacks.retryTasks` (default no-op) is wired in `CalendarRoute`. `TasksFailedLine` shows a "Retry" `TextButton` (the existing `calendar_retry` string) with the content description `calendar_tasks_retry` (EN "Load the tasks again", DE "Aufgaben erneut laden") and the test tag `CalendarTags.TASKS_RETRY`.

Tests:
- View model: a new range clears the failure as soon as it is shown, before its own answer arrives; the same range shown again keeps the failure; retry reloads only the shown range and clears the failure; retry does nothing while the overlay is off.
- Compose: the retry button calls `retryTasks`; the German labels are shown.

Validation:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` — Pass

## Not fixed

None.

## Validation summary

Focused tests (results of the full run below):
- `CalendarMonthLayoutTest` 4/4, `MonthTitlesShownTest` 3/3, `CalendarViewModelOverlayTest` 23/23, `CalendarOverlayMonthScreensTest` 23/23 — Pass

Full test suite:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` — BUILD SUCCESSFUL (4461 unit tests, 0 failures, 0 errors, 0 skipped)

Lint/type-check/build:
- ktlintCheck and lintDebug are included in the command above: Pass.

`.taskcheck`:
- Not run by the fixer. The task notes say `sh scripts/check.sh` fails in the server tests on this host because Docker is not available, which is unrelated to this task. The Android part of the check passes (see above).

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.

# Round 3

Date: 2026-11-08

Task: and-cal-overlay-month - Android calendar: task overlay and month view

Scope: the third-round finding CR-1 (P3) in `reviews/and-cal-overlay-month/review.md` (review of 392b2b72). The parent worker left a draft fix in the working tree. The fixer checked the draft against the finding, confirmed that the new tests catch the bug, and made no further code changes. The changes are uncommitted, for the parent worker to commit. All changes stay inside the task's `touches`.

## Fixed findings

### CR-1 — The memoized task marks ignore `today`, so a released-today mark can stay on yesterday after midnight

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarOverlayMonth.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarOverlayMonthScreensTest.kt`

Problem:
The finding is valid. `CalendarUiState.taskMarks` calls `taskMarks(tasks, from, to, today)`, but the three composables that cache it (`MonthGrid`, `Agenda`, `TimeGrid`) used only `overlay`, `tasks`, `from` and `to` as `remember` keys. When `today` changed while the range and the reloaded task list stayed equal, the marks were not computed again.

Fix (draft, checked and kept):
`state.today` was added to the `remember` keys in `MonthGrid` (`CalendarOverlayMonth.kt`), `Agenda` and `TimeGrid` (`CalendarScreen.kt`). A grep of `android/app/src/main` for `taskMarks` finds only these three cached reads, the property in `CalendarViewModel.kt` and the function in `TaskOverlay.kt`, so no other cache is missing the key. The values that depend on the marks (`agendaItems` and the month `itemsByDay`) are keyed on `marks`, so they follow too.

Tests:
- `CalendarOverlayMonthScreensTest`, `assertReleasedMarkFollowsToday` for AGENDA, WEEK and MONTH: the screen is shown with a task released today, then the state changes only `today` to the next day (same range, same task list). The task's mark must disappear.
- Checked that the tests catch the bug: with the three keys temporarily changed back to the old version, `./gradlew testDebugUnitTest --tests '*CalendarOverlayMonthScreensTest*today moves on*'` -> 3 tests completed, 3 failed (`assertDoesNotExist` found the mark still shown). With the fix restored -> BUILD SUCCESSFUL, 3/3 pass.

Validation:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `./gradlew testDebugUnitTest --tests '*CalendarOverlayMonthScreensTest*today moves on*'`: 3 failed without the fix, 3 passed with it.
- `CalendarOverlayMonthScreensTest` 27/27 — Pass (from the full run below)

Full test suite:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` — BUILD SUCCESSFUL (4469 unit tests, 0 failures, 0 errors, 0 skipped)

Lint/type-check/build:
- ktlintCheck: Pass. lintDebug: 0 errors, 0 warnings, 2 hints (`AutoboxingStateCreation` in `journal/JournalScreen.kt` and `notes/NoteEditorScreen.kt`, both from before this task and outside its touches).

`.taskcheck`:
- Not run by the fixer, for the same reason as in round 2 (the server part needs Docker, which is not available on this host). The Android part of the check passes (see above).

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
