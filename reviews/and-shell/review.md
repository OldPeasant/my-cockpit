# Code Review

Date: 2026-10-20

Task: and-shell (Android app shell: theme, navigation, placeholders)

Scope: `git diff 12e99dabc244ec4a629b9b123d0e21974486a462..1a190a6cc3d726bd02a4ce825b83910def86dcc1`, limited to the task commits f4d5745 (implementation), abd1b6b (tests), 062c0e3 (first review), 3b7f866 (CR-1 fix) and 1a190a6 (re-test). The range also holds other agents' work merged into main (server `core/web`, `api/`, p0-api-conventions reviews, TFP tooling); that work is outside this task and was not reviewed. Reviewed revision: 1a190a6cc3d726bd02a4ce825b83910def86dcc1.

Requirements:
- `tasks/and-shell.md` acceptance criteria and its three recorded assumptions
- `SPEC.md` (Android stack, tests)
- `docs/ARCHITECTURE.md` (Android conventions, externalized strings, lint, Coverage)
- `AGENTS.md` project notes and conventions
- Previous review (`reviews/and-shell/review.md` at 062c0e3, CR-1), `reviews/and-shell/fixes.md`, `reviews/and-shell/tests.md`
- Parent worker instructions

Git range:
- Branch: main
- HEAD: 1a190a6cc3d726bd02a4ce825b83910def86dcc1
- Base/start: 12e99dabc244ec4a629b9b123d0e21974486a462
- Commits reviewed: 5 (task commits)
- Uncommitted changes: none

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## CR-1 re-check (previous review)

Resolved. `CockpitShell.kt:109` now uses `popUpTo(start) { saveState = true }` unconditionally, and `restoreState = destination != More` (line 111) is unchanged. So the tab being left is always saved, and More always opens on its list. I traced the cases by hand:
- tab, then More, then tab: the tab is saved and restored.
- More sub-screen, then tab, then More: More plus its sub-screen are saved but not restored, so a fresh More list opens with only Today below it.
- Reselecting the current tab: it is saved and restored in place.

None of these leave stale entries. The KDoc (lines 100-106) describes the behavior correctly. These regression tests cover it:
- `tabKeepsItsSavedStateAcrossAVisitToMore` covers Inbox, Calendar and Mail, checking the entry id and a `SavedStateHandle` value.
- `tabKeepsItsSavedStateAcrossAVisitToAMoreSubScreen`
- `moreIsNotRestoredAfterBeingLeft`
- `moreListReopenedAfterASubScreenHasOnlyTodayBelowIt`

All four run through the production `CockpitShell(navController)` in `CockpitShellNavigationTest`. fixes.md reports that the first one fails against the old line.

## Acceptance criteria check

1. Met.
   - `CockpitApp` is `@HiltAndroidApp` and registered in the manifest (`android:name=".CockpitApp"`, assumption #3). `MainActivity` is `@AndroidEntryPoint`.
   - `CockpitTheme` uses Material 3 with dynamic color on API 31+, a fallback palette before that, and dark mode following the system.
   - The bottom bar holds Today, Inbox, Calendar, Mail and More. The More list holds the 12 required items in the required order (`CockpitDestination`).
2. Met. There is one placeholder `<Feature>Screen(modifier)` per destination, each in the package its feature task owns (assumption #1). All of them are listed in `android/README.md`.
3. Met. All UI text comes from `res/values/strings.xml`. A search of `app/src/main/java` for string literals found only routes and test tags, no user-facing text.
4. Met. I ran `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.ui.*' lintDebug` at HEAD. BUILD SUCCESSFUL, 43 tests, 0 failures, 0 errors, 0 skipped:

   | Suite | Tests |
   |---|---|
   | CockpitShellNavigationTest | 13 |
   | CockpitDestinationTest | 11 |
   | CockpitThemeTest | 9 |
   | CockpitShellTest | 6 |
   | MainActivityLaunchTest | 2 |
   | PlaceholderScreenTest | 2 |

   Navigation between all bottom-bar destinations is covered by `CockpitShellTest.bottomBarSwitchesBetweenAllMainDestinations` and the back-stack tests.

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
