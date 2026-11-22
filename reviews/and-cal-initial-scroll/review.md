# Code Review

Date: 2026-11-22

Task: and-cal-initial-scroll - Android calendar: day/week grid opens scrolled to the earliest entry

Scope: full task diff on the final code, `git diff dca69126a..c915f691b` (round 2), excluding `tasks/` and `reviews/`. Changed files: `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt` and the new `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarGridScrollTest.kt`. Also confirms the round-1 finding CR-1 is resolved.

Requirements:
- tasks/and-cal-initial-scroll.md acceptance criteria and recorded assumptions
- AGENTS.md project notes (conventions)
- Existing conventions in `CalendarScreen.kt` (agenda `positionedFrom` scroll handling)
- reviews/and-cal-initial-scroll/fixes.md (fix for round-1 CR-1)

Git range:
- Branch: main
- HEAD: c915f691b1f84b5d33d4c1dba101e6bfea10767f
- Base/start: dca69126a29356e109ff02d28ca028f9c90da221
- Commits reviewed: 3 (68aae48ec implementation, 065c27383 round-1 reports and tests, c915f691b CR-1 fix)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Verification notes (no finding):
- Round-1 CR-1 is resolved. `positionedFor` (`CalendarScreen.kt:796`) is now a `rememberSaveable` String holding `GridPeriod.key` (view name plus ISO first and last dates), the same pattern as the agenda's `positionedFrom` (`CalendarScreen.kt:510`). After a saved-state restore for the same loaded period, `positionedFor == period`, so the effect does not overwrite the restored `rememberScrollState` value. The new test `a grid recreated for the same period (rotation, back to the tab) keeps where the user scrolled to` uses `StateRestorationTester` and checks this. Switching to the agenda and back still disposes the grid's saveable state (no saveable holder around it), so the grid is positioned again, which counts as a view switch under criterion 4.
- Criterion 1: `firstVisibleHour` returns `earliest / 60` across the shown days. `scrollTo` is clamped to `maxValue`. Tests cover 14:20, near midnight (end of grid) and the week.
- Criterion 2: entries with `allDay` and `continuesBefore` are filtered out. Tests cover an overnight entry on the next day, the previous week, and an all-day entry.
- Criterion 3: the keys of `entriesByDay` are the shown days, so `today in keys` works. The current hour is floored, which matches the recorded assumption. Otherwise 07:00. Tests cover both.
- Criterion 4: the effect runs again on `period`, `state.shown` and `entriesByDay`. It positions only when `shown` and the period differs from the last positioned one, so a refresh of the same period keeps the user position. Positioning on cache data (`cachedAt`) followed by network data for the same day does not move it again. The view is part of the period key, so a day/week switch positions again. Tests cover these flows.
- Criterion 5: tests were added. Gradle was not run for this review, as the parent asked. Test results come from fixes.md and the parent's evidence.
- No security-relevant surface, nothing logged, and no new dependencies.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.
