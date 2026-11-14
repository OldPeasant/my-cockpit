# Code Review

Date: 2026-11-14

Task: and-nav-new-bottombar - Android: New replaces Inbox in the bottom bar (Inbox moves to More)

Scope: committed task changes `git diff 5e380566b..ce37b058c` (commits 24ba5b930, b0bdab0d3, 195a0dfeb, ce37b058c). Round 1 reviewed 5e380566b..b0bdab0d3. Round 2 reviewed 195a0dfeb, which added `CockpitShellSelectionTest.kt` and changed no production code. This round (round 3) reviews ce37b058c. That commit changes only `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt` (and this report). Lint (RestrictedApi) had rejected `navController.currentBackStack`, so the selected tab is now derived from `current`. For a stacked entry, it comes from `navController.previousBackStackEntry`, cached per entry id in a remembered map. The working tree was clean. This review is by reading code only. Gradle was not run, because the test-writer runs it in parallel. The parent worker reports that `ktlintCheck lintDebug` and the ui/newitems/inbox/dashboard unit tests pass on this code.

Requirements:
- tasks/and-nav-new-bottombar.md (acceptance criteria, notes, recorded assumption)
- AGENTS.md project notes (conventions, touches, lint)
- Existing shell/navigation code and tests (CockpitShell.kt, CockpitDestination.kt, MoreScreen.kt, ReminderOpenRequests.kt, DashboardScreen.kt, CockpitShellNavigationTest.kt, CockpitShellSelectionTest.kt, MailOriginShellTest.kt)
- Navigation Compose 2.9.6 (android/gradle/libs.versions.toml) semantics of popUpTo/saveState/restoreState, `previousBackStackEntry` and entry ids
- android/app/src/main/AndroidManifest.xml (MainActivity declares no `configChanges`, so a rotation recreates the activity and its composition)

Git range:
- Branch: main
- HEAD: ce37b058ca39f8267cae62eda499338d81e5d4e0
- Base/start: 5e380566bfd26ba3f0683b7d903ac60dcc029eb8
- Commits reviewed: 4

Summary: 0 P0, 0 P1, 1 P2, 2 P3 (CR-1 resolved by 195a0dfeb; CR-4 new in ce37b058c)

## Acceptance criteria check

1. Bottom bar order is Dashboard, New, Calendar, Mail, More. New keeps its existing label (`R.string.nav_new`) and icon (`NewReleases`). Met by the enum reorder in `CockpitDestination.kt:40-46`. `bottomBar` is derived from enum order. Tapping New goes through `navigateToTopLevel`. Covered by `CockpitDestinationTest`, `CockpitShellTest.bottomBarSwitchesBetweenAllMainDestinations` (which also asserts New is selected), `NewScreenInAppTest`, `MailBackToOriginInAppTest` and `CockpitShellSelectionTest.routesInboxAndNewStillOpenTheirScreens`.
2. Inbox is first on More, before Today, and New is no longer on More. Met (`CockpitDestination.kt:49`; `MoreScreen` renders `moreItems` in enum order). Inbox still maps to `InboxScreen` with processing (`InboxGraphTest` reaches `InboxTags.PROCESS` via More). `CockpitDestinationTest.moreListsTheSecondaryDestinationsInTheSpecifiedOrder` asserts the exact list, so New's absence is covered. `CockpitShellSelectionTest` opens Inbox through the real More list.
3. Selection:
   - On New, New is highlighted.
   - On the Inbox, More is highlighted.
   - A task opened from New: `TaskOpener` pushes `TaskRoutes.detail` on top of `new`. Since ce37b058c, `CockpitShell.kt:103-109` resolves the stacked entry to the destination directly below it (`previousBackStackEntry` is `new`), so New stays highlighted. Nested stacked screens (an item or task opened from a task on New) inherit the cached value of the stacked entry below them. This works while the map holds that entry. See CR-4 for when it does not.
   - A mail opened from New is shown inside the New screen (the route stays `new`), so New stays highlighted.

   `CockpitShellSelectionTest` pins these cases. Each case asserts that exactly one tab is selected. The parent reports these tests pass at ce37b058c.
4. Unchanged behaviour:
   - The Dashboard Inbox card calls `onOpen(Inbox)`. That now pushes Inbox on top of Dashboard (More section) instead of switching tabs. Back and the Back arrow return to Dashboard.
   - Routes `inbox` and `new` are unchanged, and no Android deep-link intent filters exist for them. The reminder mapping never targets Inbox or New.
   - The start destination's saved state is no longer restored (b0bdab0d3). `DashboardInAppTest` and the four `dashboardTab...` tests in `CockpitShellSelectionTest` cover this.
5. Tests are updated (`CockpitDestinationTest`, `CockpitShellTest`, `CockpitShellNavigationTest`, in-app tests, `CockpitShellSelectionTest`). This review did not run Gradle. The parent worker records the runs.

Other checks:
- The change stays inside the task's `touches`.
- No new strings. No lint suppressions: ce37b058c removes the restricted API instead of suppressing the warning, which follows the project lint convention.
- `previousBackStackEntry` and `NavBackStackEntry.id` are public, non-restricted APIs.
- The Review wizard's "push Mail on top of Review" (and-review-flow assumption) still highlights Mail, because `current` is non-null there and the map is not consulted.

## Delta review of ce37b058c (selected tab without `currentBackStack`)

What the new code does. If `current` is a cockpit destination, it is used directly. Otherwise, for the current entry, `getOrPut(entry.id)` computes the value once. It takes the route destination of `navController.previousBackStackEntry`, or, if that entry is stacked too, the value cached for it. The value is then remembered for the lifetime of the composition.

Checked cases:
- Single stacked screen (task or search over New, Dashboard, Calendar, Mail, a More screen): correct. `previousBackStackEntry` is read from the live back stack when the composition sees the new entry. For a single push, that is the entry the screen was opened from. This also holds after rotation or process recreation, because a cache miss recomputes from the live stack.
- Nested stacked screens pushed one per frame (search, then a search item; task, then a linked mail, then another task): correct while the composition lives. Each intermediate entry is composed and cached before the next push. `aMailOrTaskOpenedFromATaskOnNewKeepsNewSelected` covers this (its `open` helper waits for idle after each push).
- Back through nested stacked screens: correct. The entry revealed by Back keeps its id and its cached value.
- Restored tab stacks (`restoreState`, e.g. `newTabRestoresTheTaskLeftOpenOnIt`): correct. Navigation restores entries from `NavBackStackEntryState` with their original ids, so a cached value is found. For a single stacked level, a miss recomputes correctly from the restored stack. An entry's underlying destination never changes during its lifetime: a stack only grows or shrinks at the top, and a save or restore keeps the order. So a cached value cannot go stale.
- Reminder path (`open(destination)` then `navigate(TaskRoutes.detail)` in one coroutine step): the composition sees only the task, and its previous entry is the destination. Correct for one stacked level.
- Process recreation and configuration change with nested stacked screens: incorrect. See CR-4.
- Map growth: one entry per stacked entry ever composed in this composition. Entries are never removed when they leave the back stack. In practice this is a few dozen short strings per session, and it is dropped with the composition (every rotation). The memory impact is negligible and not reported on its own. The fix suggested in CR-4 also removes it.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [x] **CR-1 | Back-stack-derived tab selection for stacked screens has no committed test** (resolved in 195a0dfeb)
    - File: `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt:103`
    - Category: Tests
    - Problem: At b0bdab0d3, no committed test opened a task or search on top of New, or on top of any other screen, and asserted the selected tab. That rule directly implements the criterion 3 clause "on a task ... opened from [New] New is highlighted".
    - Why it matters: A regression, such as going back to `current` or scanning the stack in the wrong direction, would have passed the whole suite.
    - Suggested fix: Add a Robolectric shell test that asserts the selected tab for a task or search over New, and for a task over a More screen.
    - Status: Resolved. `CockpitShellSelectionTest.kt` (195a0dfeb) covers these cases with assertions that exactly one tab is selected. It still pins the ce37b058c implementation: reverting to `bottomBarItemFor(current)` or dropping the cache lookup for nested entries would fail `aTaskOpenedOnTopOfNewKeepsNewSelected` or `aMailOrTaskOpenedFromATaskOnNewKeepsNewSelected`.

- [ ] **CR-4 | Nested stacked screens lose the highlighted tab after rotation or process recreation** (new in ce37b058c, open)
    - File: `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt:103-109`
    - Category: Correctness
    - Problem: `underlyingOf` is a plain `remember` map. `MainActivity` declares no `configChanges`, so a rotation, a dark-mode or locale switch, or process death recreates the composition with an empty map. Meanwhile the NavController (via `rememberNavController`, which is saveable) restores the full back stack with the same entry ids. Take a nested stack, for example `[dashboard, new, task, mail item]` (the `aMailOrTaskOpenedFromATaskOnNewKeepsNewSelected` flow) or `[dashboard, new, search, search item]`. After recreation, the top entry misses the map. Its `previousBackStackEntry` is the stacked `task` or `search` entry, so `fromRoute` returns null and `underlyingOf[it.id]` is empty. The result is null, and `getOrPut` caches that null for the top entry. `bottomBarItemFor(null)` then selects no tab, and it stays that way for this entry until it is popped. The same miss happens if two stacked screens are pushed before one recomposition, for example a reminder that restores a tab stack ending in a stacked screen and then pushes a task on top. At 195a0dfeb the full back-stack scan handled all of these cases, so this is a regression introduced by the lint fix.
    - Why it matters: Criterion 3 requires that a screen opened from New keeps New highlighted. The tested nested case (a mail or task opened from a task on New) silently loses its highlight after a common event such as a rotation. The cached null also hides the problem from any later recomposition.
    - Suggested fix: Store the resolved tab with the entry rather than in composition memory, for example in `entry.savedStateHandle` (a route string under a shell-private key). Write it the first time the stacked entry is seen, and read it for a stacked previous entry. `savedStateHandle` survives configuration changes and process death together with the back stack, and it is discarded when the entry is popped, which also ends the map growth. A `rememberSaveable` map keyed by entry id would be a smaller alternative. Then add a `StateRestorationTester` (or activity `recreate()`) case to `CockpitShellSelectionTest` that recreates on a nested stacked screen over New and asserts New is the only selected tab.

## P3 Low

- [ ] **CR-2 | Tapping the now-highlighted tab on a stacked screen restores the stacked screen instead of returning to the tab's root** (open, optional)
    - File: `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt:177` (`onClick`), `:313-317` (`navigateToTopLevel`)
    - Category: Correctness
    - Problem: On a task detail or search opened from New (or Calendar/Mail), New is now shown selected. Tapping it runs `navigateToTopLevel(New)`. `popUpTo(start) { saveState = true }` saves `[new, task]` under New's id, then `restoreState = true` restores that same stack, so the stacked screen stays in view. For Dashboard (since b0bdab0d3) and More, the same tap goes to the root screen.
    - Why it matters: Tapping the selected tab is a common way to get back to that tab's root (Material's reselect convention). The shell now handles this tap differently for Dashboard/More than for New/Calendar/Mail. This is a minor UX inconsistency, not a criterion failure: the criteria do not define reselect behaviour.
    - Suggested fix: Optional. In the bottom-bar `onClick`, when `destination == selected` and the current entry is a stacked screen, pop back to that destination (`popBackStack(destination.route, inclusive = false)`) instead of calling `navigateToTopLevel`. Alternatively, record the current behaviour as an accepted assumption.
    - Status: Unchanged at ce37b058c (only the line numbers moved).

- [ ] **CR-3 | android/README.md still describes the old bottom bar** (open, optional)
    - File: `android/README.md:17`
    - Category: Quality
    - Problem: README line 18 says the bottom bar holds "Today, Inbox, Calendar, Mail and More". The destinations table lists Inbox as "bottom bar" (line 30) and has no New row. After this task the bottom bar is Dashboard, New, Calendar, Mail, More.
    - Why it matters: Developer docs that describe the navigation contradict the code.
    - Suggested fix: Update the sentence and the Section column (Dashboard and New in the bottom bar, Inbox and Today under More). The file is outside the task's `touches`, so the parent worker may leave it for a docs follow-up instead.
    - Status: Unchanged at ce37b058c.

## Review conclusion

Material issues found; fix before completing the task. Within one composition, the ce37b058c replacement for the restricted `currentBackStack` API selects the right tab for single and nested stacked screens, for Back, and for restored tab stacks, and the existing selection tests still pin it. But its plain `remember` cache loses nested stacked screens on rotation or process recreation and caches a null, so no tab stays highlighted (CR-4, P2). This is a regression from 195a0dfeb. Storing the resolved tab in the entry's `savedStateHandle` fixes it and the map growth together, and a recreation test should be added. CR-1 stays resolved. CR-2 and CR-3 remain optional low-priority items.
