# Code Review

Date: 2026-11-14

Task: and-nav-new-bottombar - Android: New replaces Inbox in the bottom bar (Inbox moves to More)

Scope: committed task changes `git diff 5e380566b..f5d6690c4`. Earlier rounds:
- Round 1 reviewed 5e380566b..b0bdab0d3.
- Round 2 reviewed 195a0dfeb, which added `CockpitShellSelectionTest.kt` and changed no production code.
- Round 3 reviewed ce37b058c (selected tab without the restricted `currentBackStack` API) and raised CR-4.

This round (round 4) is a delta review of two commits:
- 87f7bff0b: test-writer additions, namely the new `CockpitShellSelectionRestoreTest.kt` and four extra chain cases in `CockpitShellSelectionTest.kt`. No production code changed.
- f5d6690c4: the fix for CR-4 in `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt`, plus `fixes.md`.

The working tree was clean. This review is by reading code only. Gradle was not run, because the test-writer runs it in parallel. The fixer reports that `ktlintCheck lintDebug` and `testDebugUnitTest --tests 'ch.sonensei.cockpit.ui.*'` pass on f5d6690c4 (CockpitShellSelectionRestoreTest 3/3, CockpitShellSelectionTest 21/21).

Requirements:
- tasks/and-nav-new-bottombar.md (acceptance criteria, notes, recorded assumption)
- AGENTS.md project notes (conventions, touches, lint)
- reviews/and-nav-new-bottombar/fixes.md
- Existing shell/navigation code and tests (CockpitShell.kt, CockpitDestination.kt, CockpitShellSelectionTest.kt, CockpitShellSelectionRestoreTest.kt)
- Navigation Compose 2.9.6 (android/gradle/libs.versions.toml): semantics of `previousBackStackEntry`, `NavBackStackEntry.savedStateHandle`, and saving and restoring back stack entry state

Git range:
- Branch: main
- HEAD: f5d6690c4c18eb79a2bdad30d1987073eec237e6
- Base/start: 5e380566bfd26ba3f0683b7d903ac60dcc029eb8
- Commits reviewed: 7 in total; this round covers 87f7bff0b and f5d6690c4

Summary: 0 P0, 0 P1, 0 P2, 2 P3 open (CR-1 and CR-4 resolved; CR-2 and CR-3 open and optional)

## Acceptance criteria check

1. Bottom bar order is Dashboard, New, Calendar, Mail, More. Met (enum order in `CockpitDestination.kt`). This round did not change it.
2. Inbox is first on More, before Today, and New is no longer on More. Met. This round did not change it.
3. Selection: New is highlighted on New, and More is highlighted on the Inbox. Screens stacked on a tab keep that tab highlighted. This covers a task opened from New, and nested chains such as a mail or task opened from a task on New. Since f5d6690c4, this also holds after the activity is recreated (see the CR-4 verification below). A mail opened from New stays inside the New route.
4. Unchanged behaviour (Dashboard Inbox card, routes `inbox` and `new`, reminder mapping): not affected by this round.
5. Tests are updated. This round adds a recreation test class and four chain cases. The fixer reports they pass. This review did not run Gradle.

## Delta review of f5d6690c4 (CR-4 fix)

What the new code does. `CockpitShell.kt:103` uses `current` when the route is a cockpit destination. Otherwise it calls `NavHostController.underlyingDestination(entry)` (`CockpitShell.kt:296-304`). That function resolves in this order:
1. It returns the route stored on the entry under `UNDERLYING_ROUTE_KEY`.
2. Otherwise it takes the route of `previousBackStackEntry`. If that entry is stacked too, it takes the route stored on that entry.
3. It writes a non-null result to `entry.savedStateHandle`. A null result is never stored.

The remembered map is gone.

Checked:
- CR-4 scenario. Take the back stack dashboard, new, task, mail item, and recreate the activity. `rememberNavController` restores the entries with the same ids and their `savedStateHandle` contents. The task entry was composed when it was on top, so it holds `new`, and the mail item resolves through it to New. On a configuration change, the entries' ViewModel-backed handles survive anyway. After process death, Navigation restores the handle from the entry's saved state. Resolved.
- Cached null. A null result is no longer written, so a later recomposition can still resolve the entry. The earlier "stuck with no highlight" failure is gone.
- Map growth. There is no composition-level map. The stored value lives and dies with its entry: it is dropped on pop, and it is saved and restored with `popUpTo(saveState)` and `restoreState` tab switches, because a `NavBackStackEntryState` includes the entry's saved state. So restored tab stacks keep their chain values. `aTaskOpenedFromATaskOnNewIsRestoredWithNewSelectedAndBackWalksDownWithNewSelected` covers this.
- `savedStateHandle` access. `entry` is the current entry from `currentBackStackEntryAsState()`, and `below` comes from the live back stack. Both are on the back stack, so their saved state registry is attached and they are not destroyed. Reading or writing the handle of current and previous entries from composition is the documented Navigation result-passing pattern.
- Writing during composition. The write is a plain `SavedStateHandle.set` of a String. It is not snapshot state, so it does not trigger a recomposition or a recomposition loop. It happens once per entry (later reads return early), and it is deterministic and idempotent. A discarded composition would leave a value that is still correct. This is acceptable, and it is not reported.
- Stale state frame. Suppose the state value lags the live stack, for example when two stacked pushes happen before a recomposition. Then `previousBackStackEntry` may be the entry itself. Its stored value is returned first. If it has none yet, the result is null and nothing is stored, so a wrong value cannot be cached. When the state catches up, the entry resolves correctly. Stored values come first and are never overwritten. An entry's underlying destination is fixed for its lifetime, so a stored value cannot go stale.
- Same task on two tabs. Different entries have different ids and handles, so each resolves to its own tab. `theSameTaskOpenedOnTwoTabsKeepsTheTabItLiesOnSelected` covers this.
- Key collision. `cockpit.shell.underlyingRoute` is private to the shell. A grep finds no other reader or writer, and screens use their own ViewModel handles, not the entry handle under this key.
- Lint and conventions. No restricted API and no suppression. A private constant and a private extension function, documented. No new strings or dependencies. The change stays inside the task's `touches`.

## Delta review of 87f7bff0b (tests)

- `CockpitShellSelectionRestoreTest` uses `StateRestorationTester.emulateSavedInstanceStateRestore()` around the real `CockpitShellFrame`. It covers a single task on New, and a mail item over a task on New, which is the exact CR-4 scenario. It also covers a task over a task on Calendar, then recreation, then Back. Each case asserts that exactly one tab is selected. The nested cases would fail against ce37b058c, where the remembered map is empty after restore, so they pin the fix. The tester emulates a configuration change (ViewModels survive). Process-death restore of the handle is Navigation library behaviour and is not exercised separately. That is a reasonable limit, not a finding.
- The four new `CockpitShellSelectionTest` cases cover:
  - Restored tab stacks with chained stacked entries, followed by Back.
  - The same task opened on two tabs.
  - A three-level chain: search, then item, then task.
  - The Inbox opened from the Dashboard card with a chain on top, followed by Dashboard reselect.

  They assert the back stack routes as well as the selection. They contain no weakened or skipped tests. Test ids are fictitious UUIDs.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [x] **CR-1 | Back-stack-derived tab selection for stacked screens has no committed test** (resolved in 195a0dfeb)
    - File: `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt:103`
    - Category: Tests
    - Problem: At b0bdab0d3, no committed test asserted the selected tab for a task or search opened on top of New or another screen.
    - Why it matters: A regression in criterion 3 would have passed the suite.
    - Suggested fix: Add shell tests asserting the selected tab for stacked screens.
    - Status: Resolved. `CockpitShellSelectionTest.kt` covers these cases, and 87f7bff0b extends it with chains. It still pins the f5d6690c4 implementation.

- [x] **CR-4 | Nested stacked screens lose the highlighted tab after rotation or process recreation** (new in ce37b058c, resolved in f5d6690c4)
    - File: `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt:103`, `:289-304`
    - Category: Correctness
    - Problem: At ce37b058c, the resolved tab was cached in a plain `remember` map. Recreation emptied the map while the NavController restored the back stack. The top entry of a nested stack (for example dashboard, new, task, mail item) then resolved to null, and the null was cached, so no tab was highlighted.
    - Why it matters: Criterion 3 says a screen opened from New keeps New highlighted. A rotation broke that for nested screens.
    - Suggested fix: Store the resolved tab in the entry's `savedStateHandle` and add a recreation test.
    - Status: Resolved. f5d6690c4 stores the route in `entry.savedStateHandle` under a shell-private key and never stores null. `CockpitShellSelectionRestoreTest.aMailOpenedFromATaskOnNewKeepsNewSelectedAfterRecreation` and `aTaskOpenedFromATaskOnCalendarKeepsCalendarSelectedAfterRecreationAndBack` cover the scenario.

## P3 Low

- [ ] **CR-2 | Tapping the now-highlighted tab on a stacked screen restores the stacked screen instead of returning to the tab's root** (open, optional)
    - File: `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt` (bottom-bar `onClick` and `navigateToTopLevel`)
    - Category: Correctness
    - Problem: On a task detail or search opened from New (or Calendar or Mail), New is shown selected. Tapping it runs `navigateToTopLevel(New)`. That saves and then restores the same stack, so the stacked screen stays in view. For Dashboard and More, the same tap goes to the root screen.
    - Why it matters: This is a minor inconsistency in reselect behaviour. The criteria do not define reselect behaviour, so it is not a criterion failure.
    - Suggested fix: Optional. When `destination == selected` and the current entry is stacked, pop back to that destination's route. Alternatively, record the current behaviour as an accepted assumption.
    - Status: Unchanged at f5d6690c4.

- [ ] **CR-3 | android/README.md still describes the old bottom bar** (open, optional)
    - File: `android/README.md:17`
    - Category: Quality
    - Problem: The README still lists the bottom bar as "Today, Inbox, Calendar, Mail and More". Its destinations table shows Inbox in the bottom bar and has no New row.
    - Why it matters: Developer docs that describe the navigation contradict the code.
    - Suggested fix: Update the sentence and the Section column. The file is outside the task's `touches`, so the parent worker may leave it for a docs follow-up.
    - Status: Unchanged at f5d6690c4.

## Review conclusion

No material issues found. f5d6690c4 resolves CR-4. The tab a stacked entry lies on is now stored in that entry's saved state, so it survives recreation and tab save and restore, and it is discarded on pop. A null is never stored. The new recreation tests pin the nested scenario. Accessing `savedStateHandle` on the current and previous entries is safe here. The one-time write during composition is idempotent and does not cause recomposition. No new issue was found. CR-1 and CR-4 are resolved. CR-2 and CR-3 remain optional low-priority items.
