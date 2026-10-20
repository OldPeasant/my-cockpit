# Test Report

Date: 2026-10-20

Task: and-shell (Android app shell: theme, navigation, placeholders)

Scope: `git diff 12e99dabc244ec4a629b9b123d0e21974486a462..c20dbfc090ecc7b4d6f177556dcc9bf887901eca -- android` (base supplied by the parent worker; the single task-owned commit is c20dbfc "and-shell: Hilt app, Material 3 theme, bottom-bar navigation, feature placeholders"). Branch `main`, HEAD c20dbfc090ecc7b4d6f177556dcc9bf887901eca. The working tree had no uncommitted production changes. The only uncommitted inputs are the new test files listed below.

Requirements:
- `tasks/and-shell.md` acceptance criteria and recorded assumptions
- `docs/ARCHITECTURE.md` ("Coverage", "Code conventions"), `AGENTS.md` project notes
- Parent worker instructions

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/ui/navigation/CockpitDestinationTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/ui/theme/CockpitThemeTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/ui/CockpitShellNavigationTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/ui/MainActivityLaunchTest.kt` (new)

The existing `CockpitShellTest.kt` and `PlaceholderScreenTest.kt` were not changed.

## Tests added or updated

Count: 31 added (11 in CockpitDestinationTest, 9 in CockpitThemeTest, 9 in CockpitShellNavigationTest, 2 in MainActivityLaunchTest). The `ch.sonensei.cockpit.ui.*` filter now runs 39 tests, 8 of which existed before.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 `CockpitApp` is `@HiltAndroidApp` and hosts the app | `MainActivityLaunchTest.runsInsideTheHiltCockpitApp`, `showsTheShellOnTodayAndNavigatesFromTheBottomBar` (the real MainActivity, launched with the manifest's CockpitApp under Robolectric) | `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.ui.*'` | Pass |
| AC1 Material 3 theme: dynamic color on API 31+ | `CockpitThemeTest.fromAndroid12UsesDynamicLightColors` (sdk 31), `fromAndroid12UsesDynamicDarkColorsInDarkMode` (sdk 34), `dynamicColorCanBeTurnedOffOnAndroid12AndLater`, `dynamicColorOffInDarkModeUsesTheDarkFallbackPalette` | same | Pass |
| AC1 theme: fallback palette before API 31, dark mode following the system | `CockpitThemeTest.beforeAndroid12UsesTheLightFallbackPalette`, `beforeAndroid12UsesTheDarkFallbackPaletteInDarkMode`, `darkModeFollowsTheSystemByDefault` (night qualifier), `lightModeFollowsTheSystemByDefault`, `fallbackPalettesDifferBetweenLightAndDark` | same | Pass |
| AC1 bottom bar holds Today, Inbox, Calendar, Mail, More | `CockpitDestinationTest.bottomBarHoldsTodayInboxCalendarMailMoreInThatOrder`, `startDestinationIsTodayInTheBottomBar`; existing `CockpitShellTest.bottomBarSwitchesBetweenAllMainDestinations`, `startsOnTodayWithTodaySelected` | same | Pass |
| AC1 More lists Next, Projects, Waiting, Someday, Notes, Files, Contacts, Bookmarks, Journal, Habits, Review, Settings | `CockpitDestinationTest.moreListsTheSecondaryDestinationsInTheSpecifiedOrder`, `everyDestinationIsInExactlyOneSection`; existing `CockpitShellTest.moreListsEverySecondaryDestination` | same | Pass |
| AC1 every More item opens its own screen and returns to the list | `CockpitShellNavigationTest.everyMoreItemOpensItsOwnScreenWithBackToTheList` (all 12 items), `systemBackFromAMoreScreenReturnsToTheMoreList`, `leavingMoreFromASubScreenAndComingBackShowsTheMoreList` | same | Pass |
| AC1 route/selection helpers (`fromRoute`, `bottomBarItemFor`, unique routes) | `CockpitDestinationTest.routesAreUniqueAndNonBlank`, `labelResourcesAreUnique`, `fromRouteFindsEveryDestinationByItsRoute`, `fromRouteReturnsNullForNullEmptyUnknownOrDifferentlyCasedRoutes`, `bottomBarItemFor*` (3 tests) | same | Pass |
| AC2 one package per feature with a placeholder feature screen (e.g. `InboxScreen`) | `CockpitShellNavigationTest.everyMoreItemOpensItsOwnScreenWithBackToTheList`, `CockpitShellTest.bottomBarSwitchesBetweenAllMainDestinations` (each of the 16 screens renders through the shell); the package layout was checked against `android/README.md` by reading it | same | Pass. The README listing itself is documentation and is not testable by a unit test. |
| AC3 all strings in `res/values/strings.xml` | `CockpitShellNavigationTest.everyDestinationLabelResolvesToADistinctNonBlankString`; plus `grep -rnE 'Text\("\|contentDescription = "\|text = "\|label = "' android/app/src/main/java` found no hard-coded UI strings (exit 1) | test command above; grep | Pass (the grep is a static check, not a test) |
| AC4 `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.ui.*'` passes and covers navigation between bottom-bar destinations | all of the above, plus `CockpitShellNavigationTest.switchingTabsKeepsOnlyTodayBelowTheCurrentTab`, `reselectingTheCurrentTabDoesNotStackItTwice`, `reselectingTodayLeavesNothingBelowIt`, `systemBackFromATabReturnsToToday`, `bottomBarScreensHaveNoBackButton` | same | Pass: 39 tests, 0 failures, 0 skipped |

## Focused test execution

Command:

`cd android && ./gradlew ktlintFormat testDebugUnitTest --tests 'ch.sonensei.cockpit.ui.*'`

`ktlintFormat` changed no file, and `git status` showed only the new test files. The Robolectric SDK 30, 31 and 34 runtimes resolved, and every `@Config(sdk=...)` test ran (none were skipped).

Result:

Pass. Per-suite results: PlaceholderScreenTest 2, MainActivityLaunchTest 2, CockpitShellTest 6, CockpitThemeTest 9, CockpitDestinationTest 11, CockpitShellNavigationTest 9. Total 39, with 0 failures, 0 errors and 0 skipped.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Pass (BUILD SUCCESSFUL, all 7 test suites green, including TestLibrariesSmokeTest). Server and web suites were not run: the task changes no files there.

## .taskcheck

Result:

Not run: the sandbox denied permission to run `./.taskcheck` and `sh scripts/check.sh` in this helper session. Its Android part (`ktlintCheck testDebugUnitTest lintDebug`) was run separately and passed. The parent worker must run `.taskcheck`.

## Bugs found

None. No production defect was found.

## Coverage

Status: verified (whole-file coverage, fresh run after all test additions).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured. Every in-scope Kotlin file except `PlaceholderScreen.kt` and `MainActivity.kt` is new in this task, so for those files whole-file coverage equals changed-line coverage.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-shell; base 12e99dabc244ec4a629b9b123d0e21974486a462 (from the parent); HEAD c20dbfc090ecc7b4d6f177556dcc9bf887901eca; the task-owned commit c20dbfc was given by the parent and matches the task's `touches`. File list: `git diff --name-only 12e99da..HEAD -- android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD c20dbfc with no production changes. sha256 of the uncommitted tests: CockpitShellNavigationTest d6bd20f4d306…, MainActivityLaunchTest 8cc909fe7d57…, navigation/CockpitDestinationTest 7b8d6815fcc1…, theme/CockpitThemeTest f41d3841ef7c…. The unchanged CockpitShellTest is 5d4c670b51e8… and PlaceholderScreenTest is 26752f58fd4d…. The hashes were identical before and after the run.
- Exact included files: `CockpitApp.kt`, `MainActivity.kt`, `ui/CockpitShell.kt`, `ui/MoreScreen.kt`, `ui/PlaceholderScreen.kt`, `ui/navigation/CockpitDestination.kt`, `ui/theme/Color.kt`, `ui/theme/Theme.kt`, and the 16 placeholder screens (`bookmarks/BookmarksScreen.kt`, `calendar/agenda/CalendarScreen.kt`, `contacts/ContactsScreen.kt`, `files/FilesScreen.kt`, `gtd/inbox/InboxScreen.kt`, `gtd/lists/SomedayScreen.kt`, `gtd/lists/WaitingScreen.kt`, `gtd/next/NextScreen.kt`, `gtd/projects/ProjectsScreen.kt`, `gtd/today/TodayScreen.kt`, `habits/HabitsScreen.kt`, `journal/JournalScreen.kt`, `mail/list/MailScreen.kt`, `notes/NotesScreen.kt`, `review/ReviewScreen.kt`, `settings/SettingsScreen.kt`). That is 24 files, all of them measured. Excluded: `AndroidManifest.xml` and `res/values/strings.xml` (resources, not executable); Hilt-generated Java (`Hilt_*`, `DaggerCockpitApp_HiltComponents_SingletonC`, `*_GeneratedInjector`, ...), which is generated code. No files were renamed or deleted. No in-scope file was missing from the report.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (AGP 9.4.1); `./gradlew clean createDebugUnitTestCoverageReport` in `android/`; the full debug unit-test suite.
- Run start/completion time and exit status: started after 2026-10-20T11:12:19+02:00, report written 2026-10-20T11:12:47+02:00; BUILD SUCCESSFUL (exit 0). `testDebugUnitTest` and `createDebugUnitTestCoverageReport` both executed, not up-to-date.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 de14412ab1254f88657ab850534d7c7507d59086aa7e38974d6ec0b90a18c6e1.
- Evidence outputs are fresh and inputs stayed unchanged: yes. `clean` removed the old build output first, and the test hashes were the same before and after the run.
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. Statements are JaCoCo INSTRUCTION counters.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/CockpitApp.kt` | 3/3 (100%) | N/A | 1/1 (100%) | 1/1 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/MainActivity.kt` | 43/43 (100%) | N/A | 5/5 (100%) | 5/5 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt` | 894/907 (98.6%) | 40/42 (95.2%) | 22/22 (100%) | 71/72 (98.6%) |
| `android/app/src/main/java/ch/sonensei/cockpit/ui/MoreScreen.kt` | 128/132 (97.0%) | 2/4 (50.0%) | 6/6 (100%) | 12/12 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/ui/PlaceholderScreen.kt` | 174/206 (84.5%) | 2/2 (100%) | 1/4 (25.0%) | 23/26 (88.5%) |
| `android/app/src/main/java/ch/sonensei/cockpit/ui/navigation/CockpitDestination.kt` | 306/306 (100%) | 9/9 (100%) | 12/12 (100%) | 32/32 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/ui/theme/Color.kt` | 290/290 (100%) | N/A | 3/3 (100%) | 78/78 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/ui/theme/Theme.kt` | 82/95 (86.3%) | 13/18 (72.2%) | 1/1 (100%) | 13/13 (100%) |
| 16 placeholder feature screen files (`InboxScreen.kt` etc.) (identical counters each) | 22/25 (88.0%) each, 352/400 total | 1/2 (50.0%) each, 16/32 total | 1/1 each, 16/16 total | 1/1 each, 16/16 total |
| **Aggregate (24 files, summed counters)** | **2272/2382 (95.4%)** | **82/107 (76.6%)** | **67/70 (95.7%)** | **251/255 (98.4%)** |

The aggregate covers all 24 in-scope Kotlin files. Excluded: the manifest, strings.xml and the Hilt-generated Java. No in-scope file is unmeasured.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ui/PlaceholderScreen.kt:61-63` (3 lines, 3 methods) | `@Preview` function, used only by the IDE | Not material, so no test added. |
| Line 11 of all 16 placeholder feature screen files (1 of 2 branches) | Compose `$default` mask for the `modifier` default parameter; the shell always passes a modifier | Compose-compiler-generated, so no test added. |
| `ui/theme/Theme.kt:17,21` (5 branches) | Compose `$changed`/`$default` skip groups on the function signature. All four logical paths (dynamic/fallback x light/dark, plus both system-default modes) are tested | Generated code, not product behavior. |
| `ui/MoreScreen.kt:18-20` (2 branches) | Compose default-modifier and skip groups | Generated code. Click behavior on every item is tested. |
| `ui/CockpitShell.kt:51,118,136` (2 branches, 1 line) | Compose default-parameter, `when` restart-group and recomposition-scope glue. All 17 `when` arms are reached by tests | Generated code. |

## Unable to test

- `.taskcheck`: this helper's sandbox could not run it (permission denied). The parent worker must run it.
- AC2 "listed in `android/README.md`": documentation. It was checked by reading the README against the package layout (all 16 screens and packages match), not by a test.
- Real-device behavior (wallpaper-derived colors on a physical Android 12+ device, edge-to-edge insets): needs a device or emulator (`docs/ARCHITECTURE.md` says only humans verify on a device). Robolectric covers the theme selection logic only.

## Summary

Tests added and all validation passed. Task-scoped coverage is sufficient, and no production defects were found. `.taskcheck` itself could not be run in this helper session; the parent must run it.
