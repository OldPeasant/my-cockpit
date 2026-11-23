# Code Review

Date: 2026-11-23

Task: and-test-calendar-clock - Android: calendar tests depend on the real time of day (fix clock)

Scope: `git diff 3a3055d49..e1cab946a` (test code under `android/app/src/test/java/ch/sonensei/cockpit/calendar`), as supplied by the parent worker. At review time the same two commits sit rebased on main as `4bd7898f6` and `8f9a8d4f6`; `git diff e1cab946a 8f9a8d4f6` differs only in `tasks/and-test-calendar-clock.md`, so the reviewed code is identical.

Requirements:
- `tasks/and-test-calendar-clock.md` acceptance criteria
- `AGENTS.md` project notes (test-only change, lint conventions)
- Production behavior in `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt` (`firstVisibleHour`, `LocalNowClock`, `NowLine`, `TimeGrid`)

Git range:
- Branch: main
- HEAD: 8f9a8d4f6493c1ce316f0899f0aa901c6558a900 (rebased copy of e1cab946a)
- Base/start: 3a3055d4978dfd8791f6fb81feb2b8eef588a02e
- Commits reviewed: 2 (82c8efece, e1cab946a)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## What was checked

- Production code unchanged: `git diff --stat 3a3055d49..e1cab946a -- android/app/src/main` is empty; all 10 changed files are under `android/app/src/test`.
- Source of "now": `LocalNowClock` is a `staticCompositionLocalOf` defaulting to `Clock.systemDefaultZone()` (CalendarScreen.kt:880). It is read in `TimeGrid` (line 914, passed to `firstVisibleHour` at line 924) and in `NowLine` (line 1300). Note that `firstVisibleHour` uses `now.hour` whenever `today` is one of the shown days and no timed entry exists, not only when entries are at the current hour, so every DAY/WEEK render that includes `today` depended on the real time before this change.
- Every `setContent` in the 10 changed classes wraps the content in `CompositionLocalProvider(LocalNowClock provides nowClock)` outside `CockpitTheme`, including:
  - the `StateRestorationTester` path in `CalendarSwipeInteractionTest.show` (one lambda shared by `restoration::setContent` and `composeRule::setContent`, so restore recompositions keep the fixed clock);
  - the `CalendarRoute` hosts in `CalendarOverlayMonthScreensTest` (two) and `CalendarRouteResumeTest`, nested outside the `LocalLifecycleOwner`/`LocalTaskOpener` providers;
  - the `CalendarWithEditor` hosts in `CalendarWithEditorTest`, `CalendarWithEditorDeleteTest`, `CalendarWithEditorLongPressTest`;
  - the second `setContent` in `CalendarLongPressInteractionTest` (line 415) and `assertReleasedMarkFollowsToday` in `CalendarOverlayMonthScreensTest` (line 322).
- Each `nowClock` is `Clock.fixed(2026-10-21T08:00:00Z, UTC)`, which matches each class's `today` (2026-10-21) and `zone` (UTC). The required `Clock`, `Instant` and `CompositionLocalProvider` imports are present in all changed files; `LocalNowClock` is imported in the three `edit` package tests (it is `internal`, same module).
- No grid-rendering calendar test missed: of the 17 test classes that call `CalendarContent`, `CalendarRoute`, `CalendarScreen` or `CalendarWithEditor`, 13 provide `LocalNowClock` (the 10 changed plus the existing `CalendarGridScrollTest`, `CalendarScreensTest`, `HiddenEntryChipsTest`). The other 4 (`CalendarAgendaPositionTest`, `CalendarAgendaStartTest`, `CalendarAgendaInfiniteScrollTest`, `CalendarMonthLayoutTest`) only render AGENDA or MONTH views, which do not read the clock. No test outside the `calendar` package calls these composables directly.
- Vertical-gesture test (`CalendarSwipeInteractionTest`, line 311): it now opens the DAY view on `today.plusDays(5)`, a day without timed entries (`server` stays empty), so `firstVisibleHour` returns `FIRST_VISIBLE_HOUR` (7) whatever "now" is and there is room to scroll down. The gesture, the "no move" check, the "anchor unchanged" check and the "grid scrolled down" check stay the same, so the test still checks what its name says (a mostly vertical diagonal gesture scrolls and keeps the period). This matches the sibling tests at lines 327 and 342, which already use `today.plusDays(5)` for the same reason. With the fixed 08:00 clock the original `today` version would also have passed. The day change also guards against a later clock change in this class, and the comment explains it.
- Criterion 3 (time independence) relies on the parent's runs with the clock at 08:00, 00:30 and 23:30 (105/105 each). Per instruction I did not run Gradle. Nothing in the code contradicts that evidence. Criteria 2 (list in `tests.md`) and 5 (full Android check) are still open on the board, which is expected at this stage and belongs to the parent and test-writer.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

Observation, not a finding: `CalendarWithEditor*Test` and `CalendarRouteResumeTest` now use two different "now" values, the view model's `clock` (10:20, or movable in `CalendarRouteResumeTest`) and the grid's `nowClock` (08:00). No assertion depends on the two agreeing, and keeping a separate `nowClock` made it easy to move it for the time-independence runs. Reusing the view-model clock would be a style choice only.

## Review conclusion

No material issues found.
