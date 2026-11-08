# Code Review

Date: 2026-11-08

Task: and-cal-overlay-month - Android calendar: task overlay and month view

Scope: `git diff 5566785171fec9656083bc55f4ef3b86d1cfa655...392b2b729f0b9aa22dbf25db1c9c7af0f6f48357 -- android/` (the whole task, final code revision after the round 2 fixes)

Requirements:
- `tasks/and-cal-overlay-month.md` acceptance criteria 1-4 and the recorded assumptions (all marks in the all-day strip; scheduled tasks from `list=SCHEDULED` plus released-today tasks from `scheduled=today`; toggle remembered per device, off by default, also in agenda and month; month view with up to 3 titles plus "+n"; toggles in an overflow menu)
- `SPEC.md` §6.3
- `AGENTS.md`, `docs/ARCHITECTURE.md` conventions
- `reviews/and-cal-overlay-month/fixes.md` "Round 2", the previous `review.md` (round 2 findings CR-1..CR-3)

Git range:
- Branch: main
- HEAD: 392b2b729f0b9aa22dbf25db1c9c7af0f6f48357
- Base/start: 5566785171fec9656083bc55f4ef3b86d1cfa655
- Commits reviewed: 9 (a8757d4b, 3cbb6c42, 2e699be5, fa7f1666, 6e73c2bf, 623a39d6, 5b7f888d, 69436d20, 392b2b72); committed code only, working-tree test files from a concurrent test-writer were not reviewed

Summary: 0 P0, 0 P1, 0 P2, 1 P3

Round 2 fixes verified:
- CR-1 (FAB covers the last week's cells): `MonthGrid` keeps `MONTH_FAB_SPACE` (72dp = 56dp FAB + 16dp margin, matching `CalendarWithEditor`) free at its bottom (`CalendarOverlayMonth.kt:233`). `CalendarMonthLayoutTest` checks at 360x640dp with a 6-week month that the last-row Saturday/Sunday cells end above the FAB and open the day on tap. Fixed.
- CR-2 ("+n" clipped): `MonthDay` is a `BoxWithConstraints` that computes the title lines that fit under the 22dp day circle, including the 1dp `spacedBy` gap per line, and `monthTitlesShown` keeps one line for "+n" when not all entries fit. The arithmetic is correct for the edge cases (all fit, one line short, zero/negative lines, unbounded height). The 392b2b72 fallback avoids `TextUnit.Unspecified.toDp()` throwing. The layout test asserts "+n" has a height above zero and lies inside the cell. Fixed.
- CR-3 (tasks error stays after a range change, no retry): `show()` sets `tasksFailed = it.tasksFailed && sameRange`. `retryTasks()` runs a replace load, which cancels a running one and returns early while the overlay is off. `TasksFailedLine` has a Retry button with an EN/DE content description, wired through `CalendarCallbacks.retryTasks` in `CalendarRoute`. View model and Compose tests cover it. Fixed.

Whole implementation checked against the criteria:
- Criterion 1: day/week show marks in the all-day strip (kind and time in the day view), the agenda lists them under their day, also on days without events. The toggle is in the overflow menu with checkbox semantics, persisted in SharedPreferences and off by default. A tap goes through `LocalTaskOpener` with the snapshot. Stale answers are dropped: a replace cancels the earlier replace job, and every answer is checked against the overlay flag and the current [from, to). Appends run after `to` is extended.
- Criterion 2: the month view covers whole weeks (`monthGrid`, 4-6 weeks), lists events then tasks, shows "+n", and opens the day view on tap. Moving goes by month, using `plusMonths`, which also works from the 31st.
- Criterion 3: all 15 new strings and the plural exist in `values/` and `values-de/`, and a test checks both locales.
- Criterion 4: tests cover marks, the repository (requests, filtering, released-today), the view model (races, append, retry, persistence) and the Compose screens (EN/DE, 360dp). This review did not run them again. The commands and results are in fixes.md, and the parent worker records them as evidence.
- Changes stay inside the task's `touches`. Logs contain no task content. HTTP failure messages carry only the status code.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | The memoized task marks ignore `today`, so a released-today mark can stay on yesterday after midnight**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarOverlayMonth.kt:224`, `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt` (`Agenda` and `TimeGrid`: `remember(state.overlay, state.tasks, state.from, state.to) { state.taskMarks }`)
    - Category: Correctness
    - Problem: `CalendarUiState.taskMarks` depends on `today` (`taskMarks(tasks, from, to, today)` puts the released-today SCHEDULED mark on `today`), but `today` is not a `remember` key. Example: the day, week or month view stays open across midnight, and on resume `refresh()` moves `today` while the range stays the same. If the reloaded task list is equal to the old one, the keys do not change and the marks are not recomputed. That happens when a task released yesterday is still returned because its due or tickler date is in range. Its SCHEDULED mark then stays on yesterday until the list changes.
    - Why it matters: Rare, and the result is only a stale mark, but the cached value is computed from an input the cache does not track.
    - Suggested fix: Add `state.today` to the three `remember` keys, or drop the `remember` (computing the marks is cheap).

## Review conclusion

No material issues found. The round 2 findings CR-1..CR-3 are fixed correctly. The one P3 finding is optional cleanup and does not block completion.
