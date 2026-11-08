# Code Review

Date: 2026-11-08

Task: and-cal-overlay-month - Android calendar: task overlay and month view

Scope: `git diff 84de6fcd6ab5e67b6641d20f623d0b418742fb6f...b3f4f32579907399eafad139efe1cec554f68690 -- android/` (the whole task at its final code revision; the last code commit is 993c56bb, and b3f4f325 only adds `reviews/`)

Requirements:
- `tasks/and-cal-overlay-month.md` acceptance criteria 1-4 and the recorded assumptions #1-#5: all marks go in the all-day strip; scheduled tasks come from `list=SCHEDULED`, and tasks released today come from `scheduled=today`/`releasedOn`; the toggle is saved per device, off by default, and also applies to the agenda and month views; the month view shows up to 3 titles plus "+n"; the toggles are in an overflow menu, and Today is an icon button
- `SPEC.md` §6.3
- `AGENTS.md`, `docs/ARCHITECTURE.md` conventions
- `reviews/and-cal-overlay-month/fixes.md`, `tests.md` (final), and the previous `review.md` (round 4)

Git range:
- Branch: main
- HEAD: b3f4f32579907399eafad139efe1cec554f68690
- Base/start: 84de6fcd6ab5e67b6641d20f623d0b418742fb6f
- Commits reviewed: 13 (8d9b4d68 through b3f4f325). Only committed code was reviewed. The working tree was clean.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

New in this round (commit 993c56bb, tests only):
- `CalendarOverlayMonthScreensTest`: `when today moves on with the same tasks, the day view drops yesterday's released-today mark` uses the existing `assertReleasedMarkFollowsToday` helper with `CalendarView.DAY`. The day view keeps the anchor (21 Oct) and the range [21, 22). Only `today` moves to 22 Oct. `taskMarks` then has no released-today mark (today is outside the range), and the `TimeGrid` `remember` key on `state.today` (`CalendarScreen.kt:735`) recomputes the marks. The test checks the behavior that was missing for the day view, and it completes the AGENDA/DAY/WEEK/MONTH set.
- `CalendarViewModelOverlayTest`: `a refresh on the next day in the same week asks for the new today's tasks and drops yesterday's released mark`. `MovableClock.withZone` returns a copy with the current `now`. This works because the view model calls `clock.withZone(zone)` again on each `refresh()` (`CalendarViewModel.kt:154`), so it always reads the moved time. The fake source still returns the task released on the 21st when asked on the 22nd. The test therefore checks the client-side `releasedOn == today` guard in `taskMarks` (`TaskOverlay.kt:70`), not only the server filter. It also checks that the week range stays the same and that the source receives the new `today` (`tasks.todays.last()`). The assertions are about behavior, and the test data is fictitious. tests.md reports that both tests ran and passed (JUnit XML of 19:41Z). I could not run tests in this review session.

Whole task re-checked against the criteria:
- Criterion 1: The overlay marks appear in the all-day strip of the day and week views (the day view adds kind and time) and as rows under their day in the agenda, also on days without events (`agendaItems`). The toggle is a checkbox item in the overflow menu with role and toggle-state semantics. It is saved in SharedPreferences and off by default. Tapping a mark opens the task through `LocalTaskOpener`, with its snapshot as read-only fallback. Async ordering: a replace load cancels the previous replace job. Every answer is dropped when the overlay is off or the range has moved past it. Append loads start only after `to` has been extended. A range change clears `tasks` and `tasksFailed`. A failure shows a retry line whose retry loads the current range again. The repository splits ranges into calls of at most 366 days, has a page cap, maps 5xx/IO to Offline, and keeps HTTP failure messages to the status code only.
- Criterion 2: `monthGrid` covers whole weeks (4 to 6). Each cell lists events, then tasks, as many as fit (at most 3), plus "+n" that is never cut off. Tapping a cell calls `openDay`. Prev/next moves by `plusMonths`. Room is kept for the FAB. The cell content description includes the date and the number of entries.
- Criterion 3: The 15 new strings and the `calendar_month_items` plural exist in both `res/values/` and `res/values-de/`.
- Criterion 4: Tests cover marks, the repository, the view model and the Compose screens. tests.md reports `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` with exit 0 (4584 tests). It also reports that `sh scripts/check.sh` fails only in the server step (Testcontainers cannot find Docker) and the web step (Node.js v20.9.0 is below Angular's minimum). The `tfp` and `android` steps pass. These failures come from the environment, not from this task. `python tasks.py verify and-cal-overlay-month` shows no evidence recorded yet for #1-#4. Before `done`, the parent worker must record the evidence, and must record the `sh scripts/check.sh` part as a skip with its reason instead of claiming it passed.
- Conventions: all changes stay inside the task's `touches` (`calendar/agenda` main and test, and the two string files). There are no new dependencies, no API or schema changes, no logging of task content, and lint is not suppressed.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. The two tests added in 993c56bb are correct and add regression coverage for the round 3 CR-1 behavior (the day view, and a refresh on the next day through the view model).
