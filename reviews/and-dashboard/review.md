# Code Review

Date: 2026-11-01

Task: and-dashboard - Android: Dashboard as start screen

Scope: `git diff c27d4150f..051c5e610 -- android/` (task commits d11be6aad..051c5e610, final code after the fixes for the earlier review). Committed changes only; the working tree was clean. HEAD (463ff6fe9, a merge of `origin/wip/and-dashboard` plus claim/release commits) has no `android/` changes against 051c5e610. No server, web or API files changed in the range.

Requirements:
- tasks/and-dashboard.md: acceptance criteria 1 to 6 and the four ASSUMPTION notes (5-item bottom bar with Today moved to More, reminder fallback to Dashboard, reload on show and pull to refresh only, German label "Übersicht")
- SPEC.md §8.7 (Dashboard widgets), §9.2 (Android architecture)
- docs/ARCHITECTURE.md and the AGENTS.md project notes (lint, EN/DE strings, feature folders, no logging of secrets, server unchanged)
- Reference implementation: web/src/app/features/dashboard (dashboard-layout.ts, dashboard-data.ts, dashboard-page.html)
- reviews/and-dashboard/fixes.md (fixes for the earlier P3 findings)

Git range:
- Branch: main
- HEAD: 463ff6fe9cd8998e5542c49c2426d8e7ce4ddde2 (android/ identical to 051c5e610)
- Base/start: c27d4150f
- Commits reviewed: 7 (d11be6aad, c564dbd62, f9b86a4dc, 8a2283259, 5ead1a296, e025bcb00, 051c5e610)

Summary: 0 P0, 0 P1, 1 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | The start screen does not reload when the app returns to the foreground**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/dashboard/DashboardScreen.kt:77`
    - Category: Correctness
    - Problem: `DashboardRoute` reloads with `LaunchedEffect(Unit) { viewModel.refresh() }`. That runs only when the Dashboard enters composition (first show, return from another tab or screen, activity recreation). It does not run when the user brings the app back from the background, because the NavHost composition survives `onStop`/`onStart`. Other screens that show server data use `LifecycleResumeEffect(viewModel) { viewModel.refresh() }` for this reason (`calendar/agenda/CalendarScreen.kt:116-121`, whose comment says "Reloaded each time the screen is shown or the app comes back to it (also on a later day, so 'today' moves on)"; also MailScreen, NewScreen, SettingsScreen, TrashScreen).
    - Why it matters: The Dashboard is now the start screen, so returning to the app is the most common way to see it. After the app has been in the background (for example overnight), the Today card still shows yesterday's due/overdue tasks, events and habit counts, and the Inbox count shows the old number. The only way to update is a manual pull. The assumption "reload when the screen is shown" holds for in-app navigation but not for this case, and the behavior differs from the project convention.
    - Suggested fix: Replace the `LaunchedEffect(Unit)` with `LifecycleResumeEffect(viewModel) { viewModel.refresh(); onPauseOrDispose {} }`, as CalendarRoute does. `refresh()` already cancels a running load, so a resume during a load is safe. Add a test, for example in DashboardInAppTest, that moves the activity through `Lifecycle.State.STARTED` and back to `RESUMED` and asserts that the layout and widget calls are made again.

## P3 Low

None.

## Earlier findings (review of d3998a04-era code), verified as fixed

- Earlier CR-1 (fixed, non-localized upcoming date pattern): fixed. `DashboardScreen.kt:285-286` builds the pattern with `DateFormat.getBestDateTimePattern(locale, "EEEdMMM")` using the locale from `LocalConfiguration.current.locales[0]`, as CalendarScreen does. `DashboardScreenTest` checks the English ("Fri, Oct 23 · All day", line 297) and German ("Fr., 23. Okt. · Ganztägig", line 304, `@Config(qualifiers = "de")`) output with literal expected strings, so the test does not just repeat the production formula.
- Earlier CR-2 (unused `DashboardEvent.key`): fixed. `Events()` now wraps each row in `key(event.key)` (`DashboardScreen.kt:288`), so the field gives each row its identity.

## Notes (verified, no finding)

- Criterion 1: all eight §8.7 widgets load from the same endpoints and with the same parameters as `web/.../dashboard-data.ts`. That covers tasks overdue/today merged with duplicates removed, occurrences from local midnight for 1 day and from now for 7 local days with cancelled ones left out, non-archived habits with `dueToday`/`doneToday` counts, inbox tasks and unified-inbox mail with the "never 0+" rule, `WAITING`+overdue, active stalled projects, the weekly review status and open step, unread read-later bookmarks, and pinned notes, all with page size 50. Card targets (`WidgetId.destination`) match the web card links: Today, Inbox, Calendar, Waiting, Projects, Review, Bookmarks, Notes. `ReviewStep.valueOf` for the open step follows the existing pattern in `ReviewRepository.kt:126`, and an unknown step maps to a failed widget, which is tested.
- Criterion 2: `parseLayout` matches the web `parseLayout`. Order and `:hidden` are kept; unknown, repeated and non-string entries are skipped; missing widgets are appended as shown; a non-array value gives the default order. It reads `dashboardWidgets` from `GET settings` and never writes it. If the layout cannot be loaded, the order already shown stays. When a new order arrives, the list scrolls back to the top (`DashboardScreen.kt:132-138`), and a reload with the same order keeps the scroll position. Both cases are tested.
- Criterion 3: `CockpitDestination.start = Dashboard`, and Dashboard is the first of the five bottom-bar items. Today moved to the top of the More list, and unknown reminder types fall back to `start`, as assumed. Card clicks go through `NavHostController.open`: bottom-bar targets switch tabs with saved state, and More targets open on top. Existing shell, login and reminder tests were updated by renaming only; none were weakened.
- Criterion 4: each widget loads in its own child coroutine, and errors are mapped per widget (IO/5xx = offline; 4xx/serialization/`IllegalArgumentException`/`DateTimeException` = failed; `CancellationException` is rethrown). A failed reload keeps the last data under the error line. `refresh()` cancels the previous job, so an older load cannot overwrite a newer one (tested in "a newer refresh drops the answer of the older one"). `refreshing` is cleared at the end of every job that is not superseded.
- Criterion 5: `values/strings_dashboard.xml` and `values-de/strings_dashboard.xml` contain the same 30 strings and 3 plurals with matching placeholders, which DashboardStringsTest asserts. The German tab label is "Übersicht". The German widget names match the existing `nav_*` German labels.
- Criterion 6: there are tests for layout parsing, repository queries and error mapping, ViewModel ordering/isolation/refresh/race/indicator, Compose card content/navigation/errors/locale/scroll, and in-app start, routing and reload-on-show. I did not run Gradle, as instructed. tests.md records the previous full `ktlintCheck testDebugUnitTest lintDebug` pass (2725 tests). The parent must record its own final run as evidence.
- Conventions: the code stays inside the task's `touches`. It adds no logging, no new dependencies and no server changes. The Hilt-activity check with a placeholder fallback (`GeneratedComponentManagerHolder`) follows the pattern of other screens (Bookmarks, Notes, Calendar, Trash). The `Clock.systemDefaultZone()` secondary constructor matches other view models and repositories.

## Review conclusion

Material issues found; fix before completing the task. (One P2: the start screen does not reload when the app returns to the foreground. It is a small fix that follows an existing project pattern.)
