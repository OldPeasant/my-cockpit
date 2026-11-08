# Code Review

Date: 2026-11-08

Task: and-cal-overlay-month - Android calendar: task overlay and month view

Scope: re-review of the final code, `git diff e9ebce194ebba9853c0208589a890dc8c9741f24..7b1d6cc4 -- android` (commits 644a3015 implementation, 7160313a tests, 1bd7da5f more tests, 7b1d6cc4 review fixes). This covers committed changes only. The current HEAD 6bba43d7 has the same `android/` content as 7b1d6cc4 (`git diff 7b1d6cc4 6bba43d7 -- android` is empty).

Requirements:
- tasks/and-cal-overlay-month.md (acceptance criteria and recorded assumptions, including the CR-1/CR-2 assumption of 18:24)
- Previous review CR-1..CR-3 and reviews/and-cal-overlay-month/fixes.md
- SPEC.md §6.3, docs/PARITY.md (calendar row), docs/ARCHITECTURE.md, AGENTS.md project notes
- Server contract: server/src/main/kotlin/ch/sonensei/cockpit/gtd/api/TaskController.kt (`scheduled=today` filter), gtd/domain/TaskService.kt (`scheduledToday` predicate, `releaseDue`)

Git range:
- Branch: main
- HEAD: 6bba43d7ba0b8acea18d73febc70d59a9363f603 (android content identical to 7b1d6cc4)
- Base/start: e9ebce194ebba9853c0208589a890dc8c9741f24
- Commits reviewed: 4

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Status of the previous findings

- **CR-1 (scheduled-today tasks missing): resolved.** When today is in [from, to), `TaskOverlayRepository.tasks` (`TaskOverlay.kt:135-139`) also pages `GET /tasks?scheduled=today` and keeps the tasks with `releasedOn == today`. I checked this against the server: `TaskService` line 475 onward returns NEXT tasks with `releasedOn = today()`, plus SCHEDULED tasks with a date up to today. `toOverlayTask` maps `releasedOn`. `taskMarks` (`TaskOverlay.kt:68-72`) adds a SCHEDULED mark on today only when there is no in-range `scheduledDate`, so a task never gets two marks. The view model passes `state.value.today`, which is in the synced zone. The default `NO_DATE` cannot match a range. Tests cover the marks, the repository (third request, `releasedOn` filter, completed tasks dropped, no extra call when today is outside the range) and the view model (the week with today has the mark, the next week has none). This is recorded as an assumption in the task file.
- **CR-2 (toolbar too narrow on 360dp): resolved.** Today is now an `IconButton` with the existing `calendar_today` content description. The overlay and calendars toggles moved into a `MoreVert` overflow menu. Each menu item keeps its old tag and has `Role.Checkbox` plus `toggleableState`. The trailing `Checkbox` has `onCheckedChange = null`, so there is no second toggle node. Choosing an item closes the menu. The fixed toolbar width is now 2 x 48dp, which leaves about 61dp per segment on a 360dp phone. That is above the 58dp minimum width of a segmented button. Labels ellipsize instead of clipping. The new string `calendar_more_options` exists in EN and DE, and the string test covers it. The screen tests open the menu, then check the hidden-until-open behavior, the on/off state, the labels and that the menu closes. This is recorded as an assumption. As before, the width comes from layout arithmetic and was not rendered on a device (see notes).
- **CR-3 (append started before the range update): resolved.** `loadMore` (`CalendarViewModel.kt:262-263`) now calls `loadTasks(from, to, append = true)` after the `state.update` that extends `to`, still only for `Ok`. The regression test sets Main to an inline executor dispatcher, which reproduces a source that does not suspend. `@After` resets Main. The fixer reports that this test fails with the old ordering.

## Notes (not findings)

- New code checked for new issues: the `TaskOverlaySource` signature change is applied at every implementer (`None`, the repository, the test fakes). The extra request only runs when today is shown: the day, the week or the month containing today, or the first agenda chunk. A failure of that request fails the whole overlay load the same way the other two requests do, so error handling stays consistent. No logging was added, and the exception messages still carry only the status.
- I could not run Gradle in this review session (permission denied), so I did not re-run the test or lint results myself. The fixer reports `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passing. The parent worker should rely on its own final run.
- No layout of the 360dp toolbar has been rendered on a device or in a screenshot. The parent worker should not claim device verification for it.
- `python tasks.py verify and-cal-overlay-month` still shows all four criteria unchecked, and `reviews/and-cal-overlay-month/tests.md` does not exist yet. The parent worker must run test-writer, record evidence for every criterion and write `quality.json` before `done`. Criterion 4 also names `sh scripts/check.sh`, which the task notes say fails here in the server tests (no Docker). That has to be verified, or skipped with the reason, not claimed.

## Review conclusion

No material issues found.
