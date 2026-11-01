# Code Review

Date: 2026-11-01

Task: and-dashboard - Android: Dashboard as start screen

Scope: `git diff 5a0b432aa..ea2c26853 -- android/` (task commits 04cd32ebb..ea2c26853 on main). This is the final round. The android/ tree at 66a3ecd04 is identical to 051c5e610, the code the previous review covered (checked with `git diff --stat 051c5e610 66a3ecd04 -- android/`, which shows no changes). The only code changes since then are c371dc0fc (fix for the previous CR-1) and ea2c26853 (a background-stop test). I re-read both changes in full, plus the whole task diff against the criteria. I reviewed committed changes only. The single uncommitted file, `reviews/and-dashboard/tests.md`, is outside the review scope.

Requirements:
- tasks/and-dashboard.md: acceptance criteria 1 to 6 and the four ASSUMPTION notes (5-item bottom bar with Today moved to More, reminder fallback to Dashboard, reload when the screen is shown and on pull to refresh only, German label "Übersicht")
- SPEC.md §8.7 (Dashboard widgets), §9.2 (Android architecture)
- docs/ARCHITECTURE.md and the AGENTS.md project notes
- Reference implementation: web/src/app/features/dashboard
- Previous review (66a3ecd04:reviews/and-dashboard/review.md) and reviews/and-dashboard/fixes.md

Git range:
- Branch: main
- HEAD: ea2c268532aec034131e08cce1024a6d4deb4845
- Base/start: 5a0b432aab72f5493a2947849d8e91b513866327
- Commits reviewed: 10 (04cd32ebb, 3a621c727, 1a6ab8f0c, b223b7cfb, 7ca6c8406, 63b6a3ce2, 54ad70211, 66a3ecd04 (reviews only), c371dc0fc, ea2c26853)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Previous finding, verified as fixed

- Previous CR-1 (P2, the start screen did not reload when the app returned to the foreground): fixed. `DashboardScreen.kt:79-82` now uses `LifecycleResumeEffect(viewModel) { viewModel.refresh(); onPauseOrDispose {} }`, the same pattern as `CalendarRoute`. The effect follows the NavBackStackEntry lifecycle, which is capped by the activity lifecycle. Each case therefore loads exactly once:
    - first show: one load;
    - return from another tab (the Dashboard is composed again): one load;
    - return from a More screen opened on top (the entry is resumed again): one load;
    - activity paused or stopped, then resumed: one load.
  `refresh()` cancels the running job before it starts a new one, so a resume during a load, or during a pull to refresh, cannot cause stale overwrites. The new job resets `refreshing` at its start. The `LaunchedEffect` import is still in use (scroll-to-top effect, line 138).
- Tests for the fix: `DashboardInAppTest` has two new tests.
    - `the dashboard loads again when the app comes back to it` (line 203): the activity moves to STARTED, which causes no reload. It then moves back to RESUMED, and layout, Inbox, Today and Weekly Review are each loaded exactly twice.
    - `after the app was in the background the cards show what changed meanwhile` (line 229): the activity moves to CREATED, which stops it and causes no load. The repository then returns new data. After RESUMED, the card shows "5 tasks to process" instead of "3 tasks to process".
  Both tests check what the user sees through the real Hilt activity and shell. The existing counting tests (start = 1 load, pull = 2, tab round trip = 2) still match the new effect. The parent reports that `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.dashboard.*'` on ea2c26853 gave BUILD SUCCESSFUL, with DashboardInAppTest 9/9. I could not run Gradle myself, as instructed.

## Notes (verified, no finding)

- Criterion 1: all eight §8.7 widgets are rendered (`WidgetBody`, `DashboardScreen.kt:206-241`). They load from the endpoints the web dashboard uses, and the server is unchanged. Each card opens the matching screen through `WidgetId.destination` (lines 97-108) and `NavHostController.open`: bottom-bar targets switch tabs, and More targets open on top.
- Criterion 2: the layout is read from the web's stored `dashboardWidgets` setting and never written. Unknown, repeated and hidden entries are handled as in the web `parseLayout`. If no layout is stored or it cannot be loaded, the default §8.7 order is used. When the order changes, the list scrolls back to the top (lines 137-143).
- Criterion 3: `CockpitDestination.start = Dashboard`, and Dashboard is the first of the five bottom-bar items. Today moved to the top of the More list (`CockpitDestination.kt`), and the reminder fallback goes to `start`, as the assumptions say. Shell, login and reminder tests changed only to follow the new start tab. None were weakened.
- Criterion 4: each widget loads in its own child coroutine (`DashboardViewModel.refresh`), and errors are mapped per widget. A failed reload keeps the last data under the card's error line. Pull to refresh reloads the layout and all shown widgets, and `refreshing` is cleared when the job ends. A newer refresh cancels the older one.
- Criterion 5: `values/strings_dashboard.xml` and `values-de/strings_dashboard.xml` have the same keys, including the plurals and `nav_dashboard` ("Dashboard" / "Übersicht"). `DashboardStringsTest` asserts that the keys match.
- Criterion 6: there are tests for layout parsing, repository queries and error mapping, ViewModel behavior, Compose UI (content, navigation, errors, locale, scroll) and the in-app behavior: start screen, routing, reload on show, on pull and on return to the foreground. The full `ktlintCheck testDebugUnitTest lintDebug` run on the final code is still in progress by the parent. That result has to be recorded as the evidence for criterion 6.
- Conventions: the changes stay inside the task's `touches`. The task adds no logging, no new dependencies and no server or API changes. The placeholder fallback outside a Hilt activity follows the pattern of other screens.

## Review conclusion

No material issues found.
