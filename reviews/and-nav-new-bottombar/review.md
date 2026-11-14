# Code Review

Date: 2026-11-14

Task: and-nav-new-bottombar - Android: New replaces Inbox in the bottom bar (Inbox moves to More)

Scope: committed task changes `git diff 5e380566b..195a0dfeb` (commits 24ba5b930, b0bdab0d3, 195a0dfeb). Round 1 reviewed 5e380566b..b0bdab0d3. This delta round reviewed 195a0dfeb, which adds `android/app/src/test/java/ch/sonensei/cockpit/ui/CockpitShellSelectionTest.kt` and the review/test reports. It changes no production code (`git diff b0bdab0d3 195a0dfeb -- android/app/src/main` is empty). The working tree was clean. Review by reading code only. Gradle was not run in this review because another Gradle run was in progress. The test-writer reports 17/17 passing for the new class and 5510 tests with 0 failures for the full Android unit suite. The sha256 of the committed test file matches the hash that `tests.md` records for the file it ran.

Requirements:
- tasks/and-nav-new-bottombar.md (acceptance criteria, notes, recorded assumption)
- AGENTS.md project notes (conventions, touches, lint)
- Existing shell/navigation code and tests (CockpitShell.kt, CockpitDestination.kt, MoreScreen.kt, ReminderOpenRequests.kt, DashboardScreen.kt, CockpitShellNavigationTest.kt, MailOriginShellTest.kt)
- Navigation Compose 2.9.6 (android/gradle/libs.versions.toml) semantics of popUpTo/saveState/restoreState and the public `currentBackStack` StateFlow

Git range:
- Branch: main
- HEAD: 195a0dfeb
- Base/start: 5e380566bfd26ba3f0683b7d903ac60dcc029eb8
- Commits reviewed: 3

Summary: 0 P0, 0 P1, 0 P2, 2 P3 (CR-1 resolved by 195a0dfeb)

## Acceptance criteria check

1. Bottom bar order is Dashboard, New, Calendar, Mail, More, and New keeps its existing label (`R.string.nav_new`) and icon (`NewReleases`). Met by the enum reorder in `CockpitDestination.kt:40-46`. `bottomBar` is derived from enum order. Tapping New goes through `navigateToTopLevel`. Covered by `CockpitDestinationTest`, `CockpitShellTest.bottomBarSwitchesBetweenAllMainDestinations` (which also asserts New is selected), `NewScreenInAppTest`, `MailBackToOriginInAppTest` and `CockpitShellSelectionTest.routesInboxAndNewStillOpenTheirScreens`.
2. Inbox is first on More, before Today, and New is no longer on More. Met (`CockpitDestination.kt:49`; `MoreScreen` renders `moreItems` in enum order). Inbox still maps to `InboxScreen` with processing (`InboxGraphTest` reaches `InboxTags.PROCESS` via More). `CockpitDestinationTest.moreListsTheSecondaryDestinationsInTheSpecifiedOrder` asserts the exact list, so New's absence is covered. `CockpitShellSelectionTest` opens Inbox through the real More list.
3. Selection:
   - On New, New is highlighted.
   - On the Inbox, More is highlighted.
   - A task opened from New: `TaskOpener` pushes `TaskRoutes.detail` on top of `new`. The back-stack scan (`CockpitShell.kt:103-105`) finds `new` as the nearest destination, so New stays highlighted.
   - A mail opened from New is shown inside the New screen (the route stays `new`), so New stays highlighted.

   Since 195a0dfeb, `CockpitShellSelectionTest` pins all of these cases. It checks a task, search, and an item or task opened from a task over New. It also checks a task over the Dashboard, and a task over the Inbox (which keeps More highlighted). Each case asserts that exactly one tab is selected.
4. Unchanged behaviour:
   - The Dashboard Inbox card calls `onOpen(Inbox)`. That now pushes Inbox on top of Dashboard (More section) instead of switching tabs. Back and the Back arrow return to Dashboard.
   - Routes `inbox` and `new` are unchanged, and no Android deep-link intent filters exist for them. The reminder mapping never targets Inbox or New.
   - The start destination's saved state is no longer restored (b0bdab0d3). Without that change, the Dashboard tab would have brought back an Inbox opened from the card. `DashboardInAppTest` covers this, and so do the four new `dashboardTab...` tests in `CockpitShellSelectionTest` (card then Dashboard tab, and card, then another tab, then Dashboard tab, for both Inbox and Today).
5. Tests are updated (`CockpitDestinationTest`, `CockpitShellTest`, `CockpitShellNavigationTest`, in-app tests, new `CockpitShellSelectionTest`). A grep found no test that still taps an "Inbox" tab or opens New from the More list. This review did not run Gradle. The parent worker records the test-writer's runs.

Other checks:
- The change stays inside the task's `touches`: the `ui` main and test folders, plus test files of other features whose only change is how they navigate.
- No new strings. Lint conventions are followed (no suppressions).
- `NavController.currentBackStack` is a public API in Navigation 2.9.6.
- The root graph entry has a null route, which `fromRoute` maps to null, so the scan skips it correctly.
- The Review wizard's "push Mail on top of Review" (and-review-flow assumption) still highlights Mail, as before.

## Delta review of CockpitShellSelectionTest (195a0dfeb)

- Covers CR-1: yes. Two plausible regressions would now fail:
  - Reverting to `bottomBarItemFor(current)` leaves no tab selected on task detail or search. `aTaskOpenedOnTopOfNewKeepsNewSelected`, `searchOpenedFromNewKeepsNewSelectedAndBackReturnsToNew` and `inboxOpenedFromMoreSelectsMoreAndATaskOnItKeepsMoreSelected` would fail.
  - Scanning the stack from the bottom always yields Dashboard. The New and Inbox cases would fail.

  The More-screen case asked for in CR-1 is covered by the task over the Inbox. Coverage is broader than requested: Back paths, the Dashboard-tab regression from b0bdab0d3, and New's own saved stack.
- Correctness:
  - The test uses the real `CockpitShellFrame` with stub content and the real `MoreScreen`.
  - Task detail, search and search items are rendered by the shell's own NavHost, as in production. Composing the real `TaskDetailScreen` in a Robolectric shell test has precedent (`MailOriginShellTest`).
  - The Dashboard stub buttons call the same `onOpen` that the real cards receive, so they follow the same path.
  - `tab()` matches `hasText(label) and isSelectable()`, like `CockpitShellTest` and `CockpitShellNavigationTest`. The top bar title and the More rows are not selectable, so the matcher is unambiguous.
  - `assertOnlySelected` checks all five tabs, so a second highlighted tab would also fail.
  - Test ids are fictitious UUIDv7-shaped constants.
- Maintainability:
  - The test is readable, with small helpers, grouped sections and a class KDoc that explains the stubs. Every import is used.
  - The test pushes stacked screens through `navController.navigate(route)` rather than through `TaskOpener`. That is acceptable because `TaskOpener` itself only calls `navigate(TaskRoutes.detail(id))`.
  - Some cases repeat existing ones (for example, `DashboardInAppTest` already covers the Dashboard-tab regression). The repeats are cheap and run against the plain frame, so they are not reported.
  - A few back-stack assertions compare against literal routes (`"dashboard"`, `"new"`, `"calendar"`) where other assertions use `CockpitDestination.X.route`. This is a cosmetic inconsistency with no engineering impact, so it is not reported.
- Note on CR-2: `newTabRestoresTheTaskLeftOpenOnIt` pins that New restores its saved stack when you come back from another tab. That is standard Material tab behaviour and does not decide CR-2, which concerns tapping the already selected tab. CR-2 stays open as an optional item.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [x] **CR-1 | Back-stack-derived tab selection for stacked screens has no committed test** (resolved in 195a0dfeb)
    - File: `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt:103`
    - Category: Tests
    - Problem: The new rule is the only new logic in the shell: a stacked screen such as task detail or search highlights the tab of the nearest destination below it. It directly implements the criterion 3 clause "on a task ... opened from [New] New is highlighted". At b0bdab0d3, no committed test opened a task or search on top of New, or on top of any other screen, and asserted the selected tab.
    - Why it matters: A regression, such as going back to `current` or scanning the stack in the wrong direction, would have passed the whole suite. The behaviour on other stacked screens (search over the Dashboard, a task over a More screen) was not pinned either.
    - Suggested fix: Add a Robolectric shell test that asserts the selected tab for a task or search over New, and for a task over a More screen.
    - Status: Resolved. `android/app/src/test/java/ch/sonensei/cockpit/ui/CockpitShellSelectionTest.kt` (commit 195a0dfeb) covers these cases with assertions that exactly one tab is selected (see "Delta review" above). The test-writer reports 17/17 passing. The committed file hash matches the tested file.

## P3 Low

- [ ] **CR-2 | Tapping the now-highlighted tab on a stacked screen restores the stacked screen instead of returning to the tab's root** (open, optional)
    - File: `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt:308`
    - Category: Correctness
    - Problem: On a task detail or search opened from New (or Calendar/Mail), New is now shown selected. Tapping it runs `navigateToTopLevel(New)`. `popUpTo(start) { saveState = true }` saves `[new, task]` under New's id, then `restoreState = true` restores that same stack, so the stacked screen stays in view. For Dashboard (since b0bdab0d3) and More, the same tap goes to the root screen. The restore behaviour was the same before this task, but the tab was not shown selected, so the mismatch was less visible.
    - Why it matters: Tapping the selected tab is a common way to get back to that tab's root (Material's reselect convention). The shell now handles this tap differently for Dashboard/More than for New/Calendar/Mail. This is a minor UX inconsistency, not a criterion failure: the criteria do not define reselect behaviour.
    - Suggested fix: Optional. In the bottom-bar `onClick`, when `destination == selected` and the current entry is a stacked screen, pop back to that destination (`popBackStack(destination.route, inclusive = false)`) instead of calling `navigateToTopLevel`. Alternatively, record the current behaviour as an accepted assumption.
    - Status: Unchanged at 195a0dfeb.

- [ ] **CR-3 | android/README.md still describes the old bottom bar** (open, optional)
    - File: `android/README.md:17`
    - Category: Quality
    - Problem: README line 18 says the bottom bar holds "Today, Inbox, Calendar, Mail and More". The destinations table lists Inbox as "bottom bar" (line 30) and has no New row. After this task the bottom bar is Dashboard, New, Calendar, Mail, More. The text was already out of date before this task (Today instead of Dashboard), but this task also makes the Inbox row wrong.
    - Why it matters: Developer docs that describe the navigation contradict the code.
    - Suggested fix: Update the sentence and the Section column (Dashboard and New in the bottom bar, Inbox and Today under More). The file is outside the task's `touches`, so the parent worker may leave it for a docs follow-up instead.
    - Status: Unchanged at 195a0dfeb.

## Review conclusion

No material issues found. By code inspection, the implementation at 195a0dfeb meets the acceptance criteria. CR-1 is resolved by the committed `CockpitShellSelectionTest`, which is correct and maintainable. CR-2 and CR-3 remain optional low-priority items.
