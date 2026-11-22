# Code Review

Date: 2026-11-22

Task: and-cal-initial-scroll - Android calendar: day/week grid opens scrolled to the earliest entry

Scope: task commit e778a5aa7 on top of base dca69126a (`git diff dca69126 e778a5aa`), focused on the TimeGrid scroll positioning in `CalendarScreen.kt` and its interaction with `CalendarViewModel.show`/`refresh`/`load`/`applySettings`.

Requirements:
- tasks/and-cal-initial-scroll.md acceptance criteria
- AGENTS.md project notes (conventions)
- Existing conventions in `CalendarScreen.kt` (agenda scroll handling)

Git range:
- Branch: main
- HEAD: e778a5aa737dbedf38bcca55b2ca39e35e8ae2c4
- Base/start: dca69126a29356e109ff02d28ca028f9c90da221
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 0 P2, 1 P3

Verification notes (no finding):
- Criterion 1: `firstVisibleHour` returns `earliest / 60` over the shown days; `scrollTo(hourPx * hour)` is clamped by the scroll state to `maxValue` (late entries scroll to the end). Covered by tests for 14:20, near midnight, and the week.
- Criterion 2: filters `allDay` and `continuesBefore`; an overnight entry still counts on its own start day, which matches the criterion.
- Criterion 3: `today in entriesByDay.keys` is correct because the map's keys are exactly `state.days`; else 07:00.
- Criterion 4: `show()` clears `loaded`/`cachedAt` atomically with the range change whenever the range differs, so `shown` goes false, `positionedFor` resets, and the grid is positioned when the first data (network `loaded` or cache `cachedAt`) arrives. `refresh()`, `setVisible` reloads and `applySettings` with the same range keep `loaded`, so `positionedFor == period` and the position is kept. A failed load of a new period never sets `shown`, so no stale positioning. `GridPeriod` includes the view, so a day/week switch repositions even though the same `TimeGrid` call site stays composed. Old-period occurrences cannot leak into the new period because `show()` clears them when the range differs.
- Gradle was not run for this review (per parent instruction); test evidence was read, not executed.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Re-creating the grid (rotation, coming back to the tab) discards the restored scroll position**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt:791`
    - Category: Correctness
    - Problem: `scroll` comes from `rememberScrollState` (saveable, restored after a configuration change or when the navigation entry is shown again), but `positionedFor` is a plain `remember`. When `TimeGrid` is composed again with the same, already loaded period, `positionedFor` starts as `null`, `state.shown` is true, and the effect scrolls to the computed hour, overwriting the position the user had. The resume-triggered `refresh()` in `CalendarRoute` is a reload of the same period, which the criterion says must not move the scroll.
    - Why it matters: rotating the phone or switching tabs and back jumps the grid away from where the user scrolled. The agenda in the same file explicitly keeps its position in these cases ("an agenda shown again with its days (rotation, back to the tab) keeps the position it had"), so the grid behaves inconsistently with the existing convention.
    - Suggested fix: keep the last positioned period in saveable state (e.g. `rememberSaveable` with the view name and the two dates as a saver), so a re-created grid for the same period keeps the restored scroll; optionally add a Compose test using `StateRestorationTester`.

## Review conclusion

No material issues found.
