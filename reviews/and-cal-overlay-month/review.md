# Code Review

Date: 2026-11-08

Task: and-cal-overlay-month - Android calendar: task overlay and month view

Scope: `git diff 3b4cb2ec530cb038373b5db388e09ea546ed9fdd...9ed341a7d394475e1b92a2fd1792ccd3e10f43e2 -- android/` (final revision, after the fixes for the earlier CR-1..CR-3)

Requirements:
- `tasks/and-cal-overlay-month.md` acceptance criteria 1-4 and the recorded assumptions (all marks in the all-day strip; scheduled tasks from `list=SCHEDULED` plus released-today tasks from `scheduled=today`; toggle remembered per device, off by default, also in agenda and month; month view with up to 3 titles plus "+n"; toggles in an overflow menu)
- `SPEC.md` §6.3
- `AGENTS.md`, `docs/ARCHITECTURE.md` conventions
- `reviews/and-cal-overlay-month/fixes.md`, `reviews/and-cal-overlay-month/tests.md`

Git range:
- Branch: main
- HEAD: 9ed341a7d394475e1b92a2fd1792ccd3e10f43e2
- Base/start: 3b4cb2ec530cb038373b5db388e09ea546ed9fdd
- Commits reviewed: 6 task commits (e7f8bb88, 44225ae4, 9e7957bb, 9d543564, 8272f99a, 9ed341a7) plus merge 591648b3; no uncommitted changes in scope

Summary: 0 P0, 0 P1, 1 P2, 2 P3

Verified as correct: the earlier CR-1 (released-today tasks get a SCHEDULED mark on today, with no duplicate when `scheduledDate` is still in range), CR-2 (toggles in an overflow menu with checkbox semantics, Today as an icon button) and CR-3 (`loadTasks(append = true)` runs after `to` is extended). The overlay load drops stale answers: a range change cancels the replace job, and both replace and append answers are checked against the overlay flag and the current range. Tapping a task goes through the shell's `LocalTaskOpener` (provided in `CockpitShell`) with the snapshot. `TasksApi` is provided by `GtdDataModule`. All new strings exist in `values/` and `values-de/`. Changes stay inside the task's `touches`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | The event FAB covers the bottom-right month cells, so those days cannot be opened**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarOverlayMonth.kt:199` (with `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/CalendarWithEditor.kt:84-90`)
    - Category: Correctness
    - Problem: `CalendarWithEditor` draws a 56dp `FloatingActionButton` with 16dp padding at `BottomEnd` over `CalendarRoute`. The agenda and time grid scroll, so content can move out from under it. `MonthGrid` fills the height (`fillMaxSize`, weeks with `weight(1f)`) and does not scroll. On a 360dp phone each cell is about 51dp wide, so the FAB area (x about 288-344dp, the bottom 72dp) covers most of the last week's two rightmost cells. With a Monday week start these are Saturday and Sunday. A tap there creates an event (on today) instead of opening the day, and the cell's titles are hidden. This affects in-month days too, for example 30-31 May 2026.
    - Why it matters: Criterion 2 requires that tapping a day opens the day view. On a normal phone this fails for one or two days in many months, and their entries are covered.
    - Suggested fix: Leave room for the FAB in the month view, for example bottom padding of about 72dp on `MonthGrid` when the editor FAB is shown, or a smaller or relocated FAB in the month view. Add a test that the last-row weekend cells are displayed and can be clicked with the FAB present.

## P3 Low

- [ ] **CR-2 | The "+n" line can be clipped away in 5-6 week months**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarOverlayMonth.kt:243-281`
    - Category: Correctness
    - Problem: A full cell needs about 93dp: 2dp padding, a 22dp day circle, 3 titles of about 16dp (labelSmall line height) with 1dp spacing, and the "+n" line of about 16dp. On a 412x915dp phone the space left after the status bar, top app bar, navigation bar, calendar toolbar, range header and weekday row is roughly 500dp, which gives about 83dp per row in a 6-week month. The Column does not scroll, so the last child, the "+n" indicator, gets no height and disappears first. The tests check it with `assertTextContains` on the unmerged tree, which also passes for a zero-height node. This is layout arithmetic only and was not rendered on a device.
    - Why it matters: The assumption says a day shows "+n" when it has more entries. When that line is clipped, a busy day looks complete. Tapping the day still opens the day view, so nothing becomes unreachable.
    - Suggested fix: Decide how many titles fit from the cell height (for example with `BoxWithConstraints`) and always keep room for "+n". Alternatively, show "+n" in place of the last title that fits. A test with `assertIsDisplayed` at phone size would catch this.

- [ ] **CR-3 | The tasks error line stays after a range change and has no retry**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarViewModel.kt:360-371`, `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarOverlayMonth.kt:154-161`
    - Category: Quality
    - Problem: `show()` clears `tasks` when the range changes but keeps `tasksFailed`. The "The tasks could not be loaded." line therefore stays on the new range until its load finishes, against the KDoc of `tasksFailed` ("for the shown range"). `TasksFailedLine` has no retry action. The user can only recover by turning the overlay off and on, moving to another range, or leaving and resuming the screen. The events `Message` does have a retry. The test report noted the first part as an observation.
    - Why it matters: The error is shown for a range that may load fine, and there is no direct way to retry after a failure.
    - Suggested fix: Reset `tasksFailed = false` in `show()` when `sameRange` is false, or when a replace load starts in `loadTasks`. Add a retry action to `TasksFailedLine` that calls `refresh` or reloads the tasks.

## Review conclusion

Material issues found; fix before completing the task. CR-1 breaks criterion 2 ("tap a day opens the day view") for the bottom-right cells on phones. CR-2 and CR-3 are minor and can be fixed or recorded as assumptions.
