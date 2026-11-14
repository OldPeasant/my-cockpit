# Code Review

Date: 2026-11-14

Task: and-nav-new-bottombar - Android: New replaces Inbox in the bottom bar (Inbox moves to More)

Scope: committed task changes `git diff 5e380566b..HEAD` (commits 24ba5b930, b0bdab0d3). Uncommitted files in the working tree (an untracked `android/app/src/test/java/ch/sonensei/cockpit/ui/CockpitShellSelectionTest.kt` written by the parallel test-writer) are outside this scope and were not reviewed. Review by reading code only; Gradle was not run (a test-writer was using Gradle in this clone at the same time).

Requirements:
- tasks/and-nav-new-bottombar.md (acceptance criteria, notes, recorded assumption)
- AGENTS.md project notes (conventions, touches, lint)
- Existing shell/navigation code and tests (CockpitShell.kt, CockpitDestination.kt, MoreScreen.kt, ReminderOpenRequests.kt, DashboardScreen.kt)
- Navigation Compose 2.9.6 (android/gradle/libs.versions.toml) semantics of popUpTo/saveState/restoreState and the public `currentBackStack` StateFlow

Git range:
- Branch: main
- HEAD: b0bdab0d3
- Base/start: 5e380566bfd26ba3f0683b7d903ac60dcc029eb8
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 1 P2, 2 P3

## Acceptance criteria check

1. Bottom bar order Dashboard, New, Calendar, Mail, More with New's existing label (`R.string.nav_new`) and icon (`NewReleases`): met by the enum reorder in `CockpitDestination.kt:40-46`; `bottomBar` is derived from enum order. Tapping New goes through `navigateToTopLevel`. Covered by `CockpitDestinationTest`, `CockpitShellTest.bottomBarSwitchesBetweenAllMainDestinations` (also asserts New selected), `NewScreenInAppTest`, `MailBackToOriginInAppTest`.
2. Inbox first on More, before Today, New no longer on More: met (`CockpitDestination.kt:49`; `MoreScreen` renders `moreItems` in enum order). Inbox still maps to `InboxScreen` with processing (`InboxGraphTest` reaches `InboxTags.PROCESS` via More). `CockpitDestinationTest.moreListsTheSecondaryDestinationsInTheSpecifiedOrder` asserts the exact list, so New's absence is covered.
3. Selection: on New, New is highlighted (tested). On the Inbox, More is highlighted (`bottomBarItemFor` unit test, `DashboardInAppTest`). A task opened from New: `TaskOpener` pushes `TaskRoutes.detail` on top of `new`, and the new back-stack scan (`CockpitShell.kt:103-105`) finds `new` as the nearest destination, so New stays highlighted. A mail opened from New is shown inside the New screen (route stays `new`), so New stays highlighted. Logic is correct; the stacked-screen case has no committed test (CR-1).
4. Unchanged behaviour: the Dashboard Inbox card calls `onOpen(Inbox)`, which now pushes Inbox on top of Dashboard (More section) instead of switching tabs; Back/Back arrow returns to Dashboard. Routes `inbox` and `new` are unchanged, and there are no Android deep-link intent filters for them. The reminder mapping never targets Inbox or New. The second commit correctly stops restoring saved state for the start destination: with a non-inclusive `popUpTo(start) { saveState = true }`, Navigation maps the start destination id to the popped stack (e.g. the Inbox opened from the card), so `restoreState = true` on Dashboard would have brought the Inbox back. `DashboardInAppTest` covers this case.
5. Tests updated (`CockpitDestinationTest`, `CockpitShellTest`, `CockpitShellNavigationTest`, in-app tests). No remaining test taps an "Inbox" tab or opens New from the More list (checked by grep). Gradle runs were not checked in this review; the parent worker has to record them.

Other checks: the change stays inside the task's `touches` (`ui` main and test folders, plus test files of other features that only change how they navigate). No new strings. Lint conventions are followed (no suppressions). `NavController.currentBackStack` is a public API in Navigation 2.9.6. The root graph entry has a null route, which `fromRoute` maps to null, so it is skipped correctly. The Review wizard's "push Mail on top of Review" (and-review-flow assumption) still highlights Mail, as before.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Back-stack-derived tab selection for stacked screens has no committed test**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt:103`
    - Category: Tests
    - Problem: The new rule (a stacked screen such as task detail or search highlights the tab of the nearest destination below it) is the only new logic in the shell. It directly implements the criterion 3 clause "on a task ... opened from [New] New is highlighted". No committed test opens a task (or search) on top of New, or on top of any other screen, and asserts the selected tab. Existing tests only cover the cases where the current route is itself a destination, which the old `bottomBarItemFor(current)` already handled.
    - Why it matters: A regression, such as going back to `current` or scanning the stack in the wrong direction (from the bottom it would always pick Dashboard), would pass the whole suite. The same change also alters behaviour on every other stacked screen: search over the Dashboard now highlights Dashboard, and a task over Next highlights More, where before no tab was selected. None of this is pinned.
    - Suggested fix: Add a shell test (Robolectric, like `CockpitShellNavigationTest`) that taps New, navigates to `TaskRoutes.detail(someId)` (or opens search), and asserts `tab("New").assertIsSelected()` and the other tabs not selected. Add one case over a More screen (e.g. Next) asserting More is selected. The untracked `CockpitShellSelectionTest.kt` in the working tree may already do this; if it does, committing it resolves this finding.

## P3 Low

- [ ] **CR-2 | Tapping the now-highlighted tab on a stacked screen restores the stacked screen instead of returning to the tab's root**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt:308`
    - Category: Correctness
    - Problem: On a task detail or search opened from New (or Calendar/Mail), New is now shown selected. Tapping it runs `navigateToTopLevel(New)`: `popUpTo(start) { saveState = true }` saves `[new, task]` under New's id, then `restoreState = true` restores that same stack, so the stacked screen stays in view. For Dashboard (since b0bdab0d3) and More, the same tap goes to the root screen. Before this task the restore behaviour was the same, but the tab was not shown selected, so the mismatch was less visible.
    - Why it matters: Tapping the selected tab is a common way to get back to that tab's root (Material's reselect convention). The shell now treats this tap differently for Dashboard/More than for New/Calendar/Mail. This is a minor UX inconsistency, not a criterion failure: the criteria do not define reselect behaviour.
    - Suggested fix: Optional. In the bottom-bar `onClick`, when `destination == selected` and the current entry is a stacked screen, pop back to that destination (`popBackStack(destination.route, inclusive = false)`) instead of `navigateToTopLevel`. Alternatively, record the current behaviour as an accepted assumption.

- [ ] **CR-3 | android/README.md still describes the old bottom bar**
    - File: `android/README.md:17`
    - Category: Quality
    - Problem: README line 18 says the bottom bar holds "Today, Inbox, Calendar, Mail and More", and the destinations table lists Inbox as "bottom bar" (line 30) without a New row. After this task the bottom bar is Dashboard, New, Calendar, Mail, More. The text was already out of date before this task (Today, Dashboard), but this task makes the Inbox row wrong as well.
    - Why it matters: Developer docs describing the navigation contradict the code.
    - Suggested fix: Update the sentence and the Section column (Dashboard/New bottom bar, Inbox/Today More). The file is outside the task's `touches`, so the parent worker may instead leave it for a docs follow-up.

## Review conclusion

No material issues found. The implementation meets the acceptance criteria by code inspection. CR-1 is a test gap worth closing (possibly by committing the parallel test-writer's selection test) before completing the task. CR-2 and CR-3 are optional.
