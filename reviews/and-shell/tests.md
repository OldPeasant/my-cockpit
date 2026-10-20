# Test Report

Date: 2026-10-20

Task: and-shell (Android app shell: theme, navigation, placeholders). This is a re-validation of revision 3b7f86649f3d02d909a37faf491339b3cf837d85 (3b7f866, "and-shell: keep tab state when switching to More (CR-1)"). It replaces the earlier report written at 448f750.

Scope: the task-owned commits from base 12e99dabc244ec4a629b9b123d0e21974486a462 to HEAD 3b7f86649f3d02d909a37faf491339b3cf837d85 on `main`:
- f4d5745 (implementation)
- abd1b6b (tests)
- 062c0e3 (review report)
- 3b7f866 (CR-1 fix: `CockpitShell.navigateToTopLevel` now always uses `saveState = true`, plus 2 regression tests)

The diff from base to HEAD also contains other agents' work that was merged into main (server `core/web`, `api/`, reviews of p0-api-conventions, TFP tooling). That work is excluded. Task ownership comes from the parent worker and the commit subjects, and the files match the task's `touches` plus the one-line manifest change recorded as an assumption. Before the run, the only uncommitted change was my edit to `CockpitShellNavigationTest.kt`.

Requirements:
- `tasks/and-shell.md` acceptance criteria and its recorded assumptions
- `reviews/and-shell/review.md` (CR-1) and `reviews/and-shell/fixes.md`
- `docs/ARCHITECTURE.md` ("Coverage") and the `AGENTS.md` project notes
- Parent worker instructions

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/ui/CockpitShellNavigationTest.kt` (2 tests added in this pass; not committed)

## Tests added or updated

Count: 2 added in this pass.

**Review of the fixer's regression tests (commit 3b7f866):**
- `tabKeepsItsSavedStateAcrossAVisitToMore` is meaningful. It covers Inbox, Calendar and Mail, each going tab, then More, then back to the tab. It checks that the tab gets back the same back-stack entry id (so the same `rememberSaveable` scope) and the value saved in its `SavedStateHandle`. With the code before the fix (`saveState = destination != More`), the tab is popped without its state being saved when the user goes to More. Restoring it then creates a new entry with a different id, so both assertions would fail. Today is left out on purpose: it is the start destination and is never popped.
- `moreIsNotRestoredAfterBeingLeft` checks that More gets a new entry each time it is selected (`restoreState = false` for More). It passes both before and after the fix, so it is a guard for the "More always opens the list" assumption rather than a CR-1 regression test. It is still useful.

**Gaps found and filled:**
- `tabKeepsItsSavedStateAcrossAVisitToAMoreSubScreen`: Inbox, then More, then Notes, then Inbox. Checks that Inbox keeps its entry id and its saved state, and that only Today is below it. The fixer's tests only left More from the More list, never from a sub-screen. Leaving from a sub-screen pops two entries (More and Notes) under More's saved state.
- `moreListReopenedAfterASubScreenHasOnlyTodayBelowIt`: More, then Habits, then Inbox, then More. Checks that the current route is `more`, that only `today` is below it, and that system back goes to Today with nothing below it. This proves that because More's state is now saved, no stale Habits entry is restored underneath the More list.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 `CockpitApp` is `@HiltAndroidApp` and hosts the app | `MainActivityLaunchTest.runsInsideTheHiltCockpitApp`, `showsTheShellOnTodayAndNavigatesFromTheBottomBar` | `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.ui.*' --rerun-tasks` | Pass |
| AC1 Material 3 theme: dynamic color on API 31+, fallback palette before, dark mode follows the system | `CockpitThemeTest` (9 tests, sdk 30/31/34 and the night qualifier) | same | Pass |
| AC1 bottom bar holds Today, Inbox, Calendar, Mail, More | `CockpitDestinationTest.bottomBarHoldsTodayInboxCalendarMailMoreInThatOrder`, `startDestinationIsTodayInTheBottomBar`; `CockpitShellTest.bottomBarSwitchesBetweenAllMainDestinations`, `startsOnTodayWithTodaySelected` | same | Pass |
| AC1 More lists the 12 secondary destinations in order, and each opens its screen with back to the list | `CockpitDestinationTest.moreListsTheSecondaryDestinationsInTheSpecifiedOrder`, `everyDestinationIsInExactlyOneSection`; `CockpitShellTest.moreListsEverySecondaryDestination`; `CockpitShellNavigationTest.everyMoreItemOpensItsOwnScreenWithBackToTheList`, `systemBackFromAMoreScreenReturnsToTheMoreList`, `leavingMoreFromASubScreenAndComingBackShowsTheMoreList` | same | Pass |
| AC1 bottom-bar navigation keeps each tab's state, also across visits to More (CR-1) | `CockpitShellNavigationTest.tabKeepsItsSavedStateAcrossAVisitToMore`, `tabKeepsItsSavedStateAcrossAVisitToAMoreSubScreen` (new), `moreIsNotRestoredAfterBeingLeft`, `moreListReopenedAfterASubScreenHasOnlyTodayBelowIt` (new) | same | Pass |
| AC2 one package per feature with a placeholder `<Feature>Screen` | `CockpitShellTest.bottomBarSwitchesBetweenAllMainDestinations`, `CockpitShellNavigationTest.everyMoreItemOpensItsOwnScreenWithBackToTheList` (all 16 screens render through the shell) | same | Pass. The `android/README.md` listing is documentation and is not unit-testable. It was checked by reading the README in the previous pass and has not changed since. |
| AC3 all strings in `res/values/strings.xml` | `CockpitShellNavigationTest.everyDestinationLabelResolvesToADistinctNonBlankString` | same | Pass. The fix commit adds no UI strings. |
| AC4 `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.ui.*'` passes, covering navigation between bottom-bar destinations | all of the above, plus `switchingTabsKeepsOnlyTodayBelowTheCurrentTab`, `reselectingTheCurrentTabDoesNotStackItTwice`, `reselectingTodayLeavesNothingBelowIt`, `systemBackFromATabReturnsToToday`, `bottomBarScreensHaveNoBackButton` | same | Pass: 43 tests, 0 failures, 0 errors, 0 skipped |

## Focused test execution

Command:

`cd android && ./gradlew ktlintFormat testDebugUnitTest --tests 'ch.sonensei.cockpit.ui.*'`, then `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.ui.*' --rerun-tasks` after `ktlintFormat` had wrapped one long assertion line in the new test.

Result:

Pass (BUILD SUCCESSFUL). Results per suite:

| Suite | Tests |
|---|---:|
| CockpitShellNavigationTest | 13 |
| CockpitShellTest | 6 |
| CockpitThemeTest | 9 |
| CockpitDestinationTest | 11 |
| MainActivityLaunchTest | 2 |
| PlaceholderScreenTest | 2 |

Total: 43 tests, with 0 failures, 0 errors and 0 skipped.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Pass (BUILD SUCCESSFUL in 25s). All 7 suites are green: 46 tests in total, including the 3 in TestLibrariesSmokeTest, with 0 failures and 0 skipped. ktlint and Android lint are clean. Server and web were not run because the task changes no files there.

## .taskcheck

Result:

Not run. The helper sandbox required approval for `sh scripts/check.sh`, and the request was not granted. The Android part of it (`ktlintCheck testDebugUnitTest lintDebug`) was run separately and passed. The parent worker must run `.taskcheck`.

## Bugs found

None. The CR-1 fix behaves as described in `fixes.md`, and no production defect was found.

## Coverage

Status: verified (whole-file coverage, fresh run on HEAD 3b7f866 plus the 2 new tests).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured only for the CR-1 fix commit. JaCoCo line records for `CockpitShell.kt:108-113` (the `navigateToTopLevel` body, including the changed line 109) show `mi=0` on every line, and line 111 has 2/2 branches covered. So the changed executable line (1 of 1) is covered. For the earlier commits, every in-scope Kotlin file except `MainActivity.kt` and `PlaceholderScreen.kt` is new, so whole-file coverage equals changed-line coverage for those files. Changed-line coverage of `MainActivity.kt` and `PlaceholderScreen.kt` was not measured separately.

Evidence:
- **Task and scope:** and-shell. Base 12e99dabc244ec4a629b9b123d0e21974486a462. Measured HEAD 3b7f86649f3d02d909a37faf491339b3cf837d85. The task-owned commits are f4d5745, abd1b6b, 062c0e3 and 3b7f866 (from the parent and `git show --name-status`).
- **Snapshot:** HEAD 3b7f866, with no uncommitted production changes. The sha256 values of the test files were identical before and after the coverage run:

  | Test file (uncommitted change?) | sha256 |
  |---|---|
  | CockpitShellNavigationTest.kt (uncommitted) | 1c0d0ce778dba9de18cd50b81ff3e606177579ed20d0270608ede772dced5614 |
  | CockpitShellTest.kt | 5d4c670b51e8ada7f2a678b98abf609d1e20d05bfbb2ba1b19e7de399da452be |
  | MainActivityLaunchTest.kt | 8cc909fe7d574ef957de51631c1bf1956a0576dd517e890da219c0369a8034d6 |
  | PlaceholderScreenTest.kt | 26752f58fd4dd4ef696ce42cf117ff241f95d5009420e8815cfba5b41374d89e |
  | navigation/CockpitDestinationTest.kt | 7b8d6815fcc1dea18388edc0e4e5c50b70113f62476ccff04ac4192cbd3611c5 |
  | theme/CockpitThemeTest.kt | f41d3841ef7c6b0099857a11cad0cff9f7cc34393743f4c553a99b7881ca881b |

- **Included files (24, all measured):** `CockpitApp.kt`, `MainActivity.kt`, `ui/CockpitShell.kt`, `ui/MoreScreen.kt`, `ui/PlaceholderScreen.kt`, `ui/navigation/CockpitDestination.kt`, `ui/theme/Color.kt`, `ui/theme/Theme.kt`, and the 16 placeholder screens:
  - `bookmarks/BookmarksScreen.kt`
  - `calendar/agenda/CalendarScreen.kt`
  - `contacts/ContactsScreen.kt`
  - `files/FilesScreen.kt`
  - `gtd/inbox/InboxScreen.kt`
  - `gtd/lists/SomedayScreen.kt`
  - `gtd/lists/WaitingScreen.kt`
  - `gtd/next/NextScreen.kt`
  - `gtd/projects/ProjectsScreen.kt`
  - `gtd/today/TodayScreen.kt`
  - `habits/HabitsScreen.kt`
  - `journal/JournalScreen.kt`
  - `mail/list/MailScreen.kt`
  - `notes/NotesScreen.kt`
  - `review/ReviewScreen.kt`
  - `settings/SettingsScreen.kt`
- **Excluded files:**
  - `AndroidManifest.xml` and `res/values/strings.xml`: resources, not executable.
  - Hilt/Dagger-generated Java: generated code.
  - `android/README.md`: documentation.
  - Non-Android files from other tasks merged into main.
  - No files were renamed or deleted, and no in-scope file was missing from the report.
- **Provider, command and test selection:** JaCoCo via AGP `enableUnitTestCoverage` (AGP 9.4.1, Gradle 9.8.1). Command `./gradlew clean createDebugUnitTestCoverageReport`, run in `android/` against the full debug unit-test suite.
- **Run time and exit status:** started 2026-10-20T11:21:41+02:00, finished 2026-10-20T11:22:07+02:00, BUILD SUCCESSFUL (exit 0). `testDebugUnitTest` and `createDebugUnitTestCoverageReport` both ran (not up-to-date).
- **Report:** `android/app/build/reports/coverage/test/debug/report.xml` (mtime 2026-10-20 11:22:07), sha256 0cef7d1e8665e67eaeb93800a009fb96ea0ff1a33c3a8ec96a6153fb871df7e3.
- **Freshness:** yes. `clean` removed the old build output first, HEAD was unchanged, and the test hashes were the same before and after the run.
- **Final run after latest test additions and production fixes:** yes. This run comes after the CR-1 fix and after both new tests.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. Statements are JaCoCo INSTRUCTION counters.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/CockpitApp.kt` | 3/3 (100%) | N/A | 1/1 (100%) | 1/1 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/MainActivity.kt` | 43/43 (100%) | N/A | 5/5 (100%) | 5/5 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt` | 888/901 (98.6%) | 38/40 (95.0%) | 22/22 (100%) | 71/72 (98.6%) |
| `android/app/src/main/java/ch/sonensei/cockpit/ui/MoreScreen.kt` | 128/132 (97.0%) | 2/4 (50.0%) | 6/6 (100%) | 12/12 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/ui/PlaceholderScreen.kt` | 174/206 (84.5%) | 2/2 (100%) | 1/4 (25.0%) | 23/26 (88.5%) |
| `android/app/src/main/java/ch/sonensei/cockpit/ui/navigation/CockpitDestination.kt` | 306/306 (100%) | 9/9 (100%) | 12/12 (100%) | 32/32 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/ui/theme/Color.kt` | 290/290 (100%) | N/A | 3/3 (100%) | 78/78 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/ui/theme/Theme.kt` | 82/95 (86.3%) | 13/18 (72.2%) | 1/1 (100%) | 13/13 (100%) |
| 16 placeholder feature screen files (`InboxScreen.kt` etc.), identical counters | 22/25 (88.0%) each, 352/400 total | 1/2 (50.0%) each, 16/32 total | 1/1 each, 16/16 total | 1/1 each, 16/16 total |
| **Aggregate (24 files, summed counters)** | **2266/2376 (95.4%)** | **80/105 (76.2%)** | **67/70 (95.7%)** | **251/255 (98.4%)** |

The aggregate covers all 24 in-scope Kotlin files. Excluded: the manifest, strings.xml and the generated Hilt Java. No in-scope file is unmeasured.

Compared with the earlier report, `CockpitShell.kt` has 6 fewer instructions and 2 fewer branches, because the fix removed the `destination != More` condition from `saveState`. Its uncovered locations are unchanged.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ui/CockpitShell.kt:51` (1 branch), `:121` (1 branch), `:139` (1 line) | Compose-generated code: the default-parameter mask for `navController`, the `when` restart group, and the recomposition-scope glue at the end of `DestinationContent`. All 17 `when` arms are reached by tests | Generated code, so no test added. |
| `ui/PlaceholderScreen.kt:61-63` (3 lines, 3 methods) | `@Preview` function, used only by the IDE | Not material. |
| Line 11 of each of the 16 placeholder feature screen files (1 of 2 branches) | Compose `$default` mask for the `modifier` default parameter. The shell always passes a modifier | Compose-compiler-generated. |
| `ui/theme/Theme.kt:17,21` (5 branches) | Compose `$changed`/`$default` skip groups. All four logical paths (dynamic or fallback, light or dark) and both system-default modes are tested | Generated code. |
| `ui/MoreScreen.kt:18-20` (2 branches) | Compose default-modifier and skip groups. Clicking every item is tested | Generated code. |

## Unable to test

- `.taskcheck`: the helper sandbox did not grant permission to run it. The parent worker must run it.
- AC2 "listed in `android/README.md`": this is documentation, not something a unit test can check. The README has not changed since the previous pass, where it was checked by reading it.
- Real-device behavior (wallpaper-derived dynamic colors on a physical Android 12+ device, edge-to-edge insets): this needs a device or emulator. Robolectric covers only the theme selection logic.

## Summary

Tests added and all validation passed. The fixer's CR-1 regression test (`tabKeepsItsSavedStateAcrossAVisitToMore`) would fail on the code before the fix. I added two tests for leaving More from a sub-screen, which the fixer's tests did not cover. Task-scoped coverage is sufficient on revision 3b7f866, and no production defects were found. The parent must still run `.taskcheck` and commit the updated test file and this report.
