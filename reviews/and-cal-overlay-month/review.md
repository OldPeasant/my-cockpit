# Code Review

Date: 2026-11-08

Task: and-cal-overlay-month - Android calendar: task overlay and month view

Scope: `git diff a840af87d1ab4d03e75c071c01a7efdf269c6dff...fc8f54f67e5513761c7b2fdb1439c202969cbac3 -- android/` (the whole task, final code revision after the round 3 fix)

Requirements:
- `tasks/and-cal-overlay-month.md` acceptance criteria 1-4 and the recorded assumptions: all marks go in the all-day strip; scheduled tasks come from `list=SCHEDULED` plus released-today tasks from `scheduled=today`; the toggle is remembered per device, off by default, and also applies to the agenda and month views; the month view shows up to 3 titles plus "+n"; the toggles are in an overflow menu
- `SPEC.md` §6.3
- `AGENTS.md`, `docs/ARCHITECTURE.md` conventions
- `reviews/and-cal-overlay-month/fixes.md` "Round 3", the previous `review.md` (round 3 finding CR-1)

Git range:
- Branch: main
- HEAD: fc8f54f67e5513761c7b2fdb1439c202969cbac3
- Base/start: a840af87d1ab4d03e75c071c01a7efdf269c6dff
- Commits reviewed: 11 (69e823af through fc8f54f6). Only committed code was reviewed. Uncommitted test files from a concurrent test-writer were not reviewed.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Round 3 fix verified:
- CR-1 (the cached task marks ignored `today`): `state.today` is now a `remember` key for `state.taskMarks` in `MonthGrid` (`CalendarOverlayMonth.kt:224`), `Agenda` and `TimeGrid` (`CalendarScreen.kt:481`, `CalendarScreen.kt:735`). Values computed from the marks (`agendaItems`, the month `itemsByDay`) are keyed on `marks`, so they update too. These are the only places in `src/main` that cache `taskMarks`. The new tests in `CalendarOverlayMonthScreensTest` (`assertReleasedMarkFollowsToday` for AGENDA, WEEK, MONTH) change only `today` and check that the released-today mark disappears. fixes.md reports that the tests fail without the fix and pass with it. Since 392b2b72 the production code changed only in these three lines. Fixed.

Whole implementation re-checked against the criteria:
- Criterion 1: Day and week views show the marks in the all-day strip. The day view adds the kind and time. The agenda lists the marks under their day, also on days without events. The toggle is a checkbox item in the overflow menu, saved in SharedPreferences and off by default. Tapping a mark opens the task through `LocalTaskOpener`, with its snapshot. Outdated answers are dropped: a replace load cancels the previous replace job, and every answer is checked against the overlay flag and the current [from, to). Append loads start only after `to` has been extended. A range change clears `tasks` and `tasksFailed`. Retry reloads the current range.
- Criterion 2: `monthGrid` covers whole weeks (4 to 6). Each day lists its events, then its tasks, as many titles as fit, plus "+n". Tapping a day opens the day view. Moving goes by `plusMonths`, which also works from the 31st. Space is kept free for the FAB.
- Criterion 3: The 15 new strings and the plural exist in both `values/` and `values-de/`.
- Criterion 4: Tests cover the marks, the repository (requests, paging, filtering, released-today), the view model (races, append, retry, persistence) and the Compose screens (EN/DE, 360dp, month layout, today rollover). This review did not run them. fixes.md "Round 3" reports `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` as BUILD SUCCESSFUL. `python tasks.py verify and-cal-overlay-month` shows no evidence recorded yet for #1-#4. The parent worker must record it before `done`. For `sh scripts/check.sh`, earlier runs failed in the server Testcontainers suite because Docker is not available on this host. That part must either be run or be skipped with a reason, not claimed.
- All changes stay inside the task's `touches`. Logs contain no task content. HTTP failure messages carry only the status code.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. The round 3 finding CR-1 is fixed correctly and covered by regression tests.
