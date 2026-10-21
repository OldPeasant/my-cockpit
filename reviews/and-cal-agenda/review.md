# Code Review

Date: 2026-10-21

Task: and-cal-agenda - Android calendar agenda and day/week (re-review of the final code after the fixer pass)

Scope: `git diff 13770a5c..HEAD -- android/` (task commits da3eaf15, e71e8e0a, 36178a06, 33f6c93d, merged at 53493021). Files: `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/{CalendarModels,CalendarRepository,CalendarScreen,CalendarViewModel}.kt`, `android/app/src/main/res/values/strings_calendar_agenda.xml`, and the 10 test files under `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/`. Committed changes only; working tree clean.

Requirements:
- tasks/and-cal-agenda.md acceptance criteria (agenda list with infinite scroll by range, day/week views, calendar visibility; `testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*'` passes, covering range loading and grouping by day, Robolectric/Compose UI tests for screens) and its recorded assumption (agenda offline cache deferred to and-cal-cache)
- SPEC.md §6 (calendar visibility), §9.2 item 4, §9.3
- docs/ARCHITECTURE.md (Android MVVM), AGENTS.md project notes
- Server contract: `CalendarService.update` (PATCH with If-Match, version check), GET calendars / occurrences
- Earlier review and fix report: reviews/and-cal-agenda/review.md (previous revision), reviews/and-cal-agenda/fixes.md

Git range:
- Branch: main
- HEAD: 5349302191c928702983534c47f698c071385370
- Base/start: 13770a5c0be6b453abb8cf6187b233354e8c0242
- Commits reviewed: 4 task commits (plus merge commit 53493021 and board commit 04e836da, which adds tasks/and-cal-cache.md only)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Status of the earlier findings

- CR-1 (overlapping visibility toggles, stale calendar lists): resolved. `setVisible` ignores a calendar with a pending write (`CalendarViewModel.kt:172`) and its chip is disabled (`CalendarScreen.kt:268`); fetched lists go through `withCalendars` (`CalendarViewModel.kt:223`), which keeps the chosen visibility of pending calendars and never replaces a calendar holding a newer version; a failure reverts to the newest server state seen (`serverCalendars`). Covered by four ViewModel tests and one Compose test.
- CR-2 (no offline agenda cache, SPEC §9.3): deferred to task and-cal-cache by the assumption recorded in tasks/and-cal-agenda.md; the task file tasks/and-cal-cache.md exists. Not counted here.
- CR-3 (untested failure/supersession paths): resolved. Tests exist for a failed `loadMore`, refresh on a new day, and a slow agenda load superseded by a view switch (`CalendarViewModelEdgeCasesTest.kt:205, 258, 624`).
- CR-4 (static now line): resolved. `NowLine` holds the time in state updated each minute by a `LaunchedEffect`, uses the lambda `offset`, and is tested with a movable clock.

Verified as correct on the final code: one `loadJob` for range loads, cancelled by every new load, so older results (also failing ones) cannot overwrite newer ones; `loading`/`loadingMore` end on every path; a failed first load keeps `loaded = false`, shows the banner, and retry calls `refresh`; `loadMore` is guarded against double triggering, running loads and non-agenda views; the auto-trigger only fires for a scrollable list and does not loop on an empty or failing chunk (distinctUntilChanged); ranges are split at 366 days, de-duplicated by occurrence key, cancelled occurrences dropped; PATCH sends only `visible` with `If-Match: "<version>"`, and the server's `update` checks and bumps the version; grouping by local day handles multi-day, exclusive-midnight and DST cases. The Hilt-holder placeholder check follows the convention of the GTD screens; `CalendarsApi` is bound once. No secrets, no logging of event contents.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | "Today" goes stale when the app is resumed on a later day**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt:115`
    - Category: Correctness
    - Problem: `state.today` is only recomputed in `CalendarViewModel.refresh()` (`CalendarViewModel.kt:97`), and the screen calls `refresh()` only from `LaunchedEffect(Unit)`, i.e. when `CalendarRoute` enters composition (or on retry). If the app is left on the Calendar tab and comes back from the background after midnight, the composition still exists, so nothing refreshes: the agenda still starts at yesterday and marks it "Today", the Today button (`goToToday`) and `select(AGENDA)` go to yesterday, and the now line (`CalendarScreen.kt:716`, `day == state.today`) is drawn on yesterday's column at today's time. Events changed on other devices also do not show up on resume, although the comment at line 114 says the screen is reloaded "each time the screen is shown".
    - Why it matters: a stale today marker and a Today button that does not go to today are visible errors in a calendar; the case is rare but normal (phone left on the calendar overnight).
    - Suggested fix: call `viewModel.refresh()` on resume, e.g. `LifecycleResumeEffect(viewModel) { viewModel.refresh(); onPauseOrDispose {} }` (already used in `reminders/ReminderPermissionPrompt.kt:141`) instead of / in addition to `LaunchedEffect(Unit)`; optionally have `goToToday` recompute `today` from the clock. Add a ViewModel or Compose test for a resume after midnight.

## Review conclusion

No material issues found.

All four earlier findings are resolved or (CR-2) deferred by a recorded assumption to an existing follow-up task. The acceptance criteria are implemented: agenda with range-based infinite scroll, day and week views, server-stored calendar visibility with safe handling of overlapping writes, and unit, ViewModel and Robolectric/Compose screen tests covering range loading and grouping by day (the parent reported `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*'` -> BUILD SUCCESSFUL, 88 tests, 0 failures; not re-run by the reviewer). The one P3 finding is optional and may be fixed or accepted by the parent worker.
