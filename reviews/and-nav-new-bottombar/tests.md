# Test Report

Date: 2026-11-14

Task: and-nav-new-bottombar - Android: New replaces Inbox in the bottom bar (Inbox moves to More)

Scope: final task code, `git diff 5e380566bfd26ba3f0683b7d903ac60dcc029eb8..04497c289` (Android only). Measured at HEAD `de4dd60211cffa94ce1b183364107649e4cb91bb`, whose `android/` tree is identical to `04497c289` (`git diff --quiet 04497c289 HEAD -- android` succeeds; later commits only touch `reviews/`). Clean working tree at measurement time; this report replaces the earlier one written for `b0bdab0d3` (before the CR-4 fix and `CockpitShellSelectionRestoreTest`).

Requirements:
- `tasks/and-nav-new-bottombar.md` acceptance criteria and its recorded assumptions (stacked screens highlight the tab of the destination below them; reselect behavior per CR-2)
- Parent worker instructions (re-check tests against the final code, incl. the CR-4 fix storing the stacked screen's tab in `entry.savedStateHandle`)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`

## Test files changed

None in this round. The final tests were reviewed against the final code and judged sufficient:

- `android/app/src/test/java/ch/sonensei/cockpit/ui/navigation/CockpitDestinationTest.kt` (12 tests)
- `android/app/src/test/java/ch/sonensei/cockpit/ui/CockpitShellTest.kt` (8 tests)
- `android/app/src/test/java/ch/sonensei/cockpit/ui/CockpitShellNavigationTest.kt` (13 tests)
- `android/app/src/test/java/ch/sonensei/cockpit/ui/CockpitShellSelectionTest.kt` (21 tests)
- `android/app/src/test/java/ch/sonensei/cockpit/ui/CockpitShellSelectionRestoreTest.kt` (3 tests, `StateRestorationTester` recreation)
- also adjusted earlier in the task: `MailBackToOriginInAppTest`, `NewScreenInAppTest`, `InboxGraphTest`, `DashboardInAppTest`

## Tests added or updated

Count: 0 in this round (the task's test changes total 9 test files, committed earlier in the task).

Reason: every changed behavior in the final diff is exercised, including the CR-4 code path (the stored underlying route is read back after recreation in all three `CockpitShellSelectionRestoreTest` tests, and the "entry below is itself stacked" path in `aMailOrTaskOpenedFromATaskOnNewKeepsNewSelected`, `aTaskOpenedFromATaskOnNewIsRestoredWithNewSelectedAndBackWalksDownWithNewSelected`, `aTaskOpenedFromASearchItemOpenedFromSearchOnNewKeepsNewSelected`). The only unexercised branches are not reachable through the shell (see Coverage).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 bottom bar Dashboard, New, Calendar, Mail, More with New's label/icon; New opens the New view | `CockpitDestinationTest`, `CockpitShellTest.bottomBarSwitchesBetweenAllMainDestinations`, `CockpitShellSelectionTest.routesInboxAndNewStillOpenTheirScreens` | `android/gradlew -p android createDebugUnitTestCoverageReport ktlintCheck` (full unit suite) | Pass |
| AC2 Inbox first on More (before Today), opens the Inbox; New not on More | `CockpitDestinationTest`, `CockpitShellTest.moreListsEverySecondaryDestination`, `InboxGraphTest`, `inboxOpenedFromMoreSelectsMoreAndATaskOnItKeepsMoreSelected` | same | Pass |
| AC3 New highlighted on New and on a task/mail/search opened from it | `aTaskOpenedOnTopOfNewKeepsNewSelected`, `aMailOrTaskOpenedFromATaskOnNewKeepsNewSelected`, `searchOpenedFromNewKeepsNewSelectedAndBackReturnsToNew`, `backFromATaskOnNewReturnsToNewWithNewStillSelected`, `newTabRestoresTheTaskLeftOpenOnIt`, `aTaskOpenedFromATaskOnNewIsRestoredWithNewSelectedAndBackWalksDownWithNewSelected`, `theSameTaskOpenedOnTwoTabsKeepsTheTabItLiesOnSelected`, `aTaskOpenedFromASearchItemOpenedFromSearchOnNewKeepsNewSelected` | same | Pass |
| AC3 selection survives activity recreation (CR-4 fix) | `aTaskOnNewKeepsNewSelectedAfterRecreation`, `aMailOpenedFromATaskOnNewKeepsNewSelectedAfterRecreation`, `aTaskOpenedFromATaskOnCalendarKeepsCalendarSelectedAfterRecreationAndBack` | same | Pass |
| AC3 More highlighted on the Inbox (from More, the card, the `inbox` route, and on items opened on it) | `inboxOpenedFromMoreSelectsMoreAndATaskOnItKeepsMoreSelected`, `inboxOpenedFromTheDashboardCardSelectsMore`, `routesInboxAndNewStillOpenTheirScreens`, `anItemOpenedFromATaskOnTheInboxOpenedFromTheCardKeepsMoreSelected` | same | Pass |
| AC4 Dashboard Inbox/Today card still open their screens; Dashboard tab afterwards does not bring them back (`restoreState` change) | `dashboardTabAfterTheInboxCardShowsTheDashboardWithNothingBelow`, `dashboardTabAfterTheTodayCardShowsTheDashboardWithNothingBelow`, `dashboardTabAfterTheInboxCardAndAnotherTabDoesNotBringTheInboxBack`, `dashboardTabAfterTheTodayCardAndNewDoesNotBringTodayBack`, `DashboardInAppTest` | same | Pass |
| AC4 routes `inbox` and `new` still work | `routesInboxAndNewStillOpenTheirScreens`, `NewScreenInAppTest`, `CockpitShellNavigationTest` | same | Pass |
| AC4 Back from New/Inbox like other destinations | `systemBackFromNewReturnsToTheDashboard`, `systemBackFromInboxOpenedFromMoreReturnsToTheMoreList`, `backArrowFromInboxOpenedFromMoreReturnsToTheMoreList`, `systemBackFromInboxOpenedFromTheDashboardCardReturnsToTheDashboard`, `MailBackToOriginInAppTest` | same | Pass |
| AC5 tests updated; `ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass | ktlint and the full unit suite ran here | same | ktlint Pass; unit suite Pass; `lintDebug` and `scripts/check.sh` Not run: parent's final validation |

## Focused test execution

Command:

`android/gradlew -p android createDebugUnitTestCoverageReport ktlintCheck` (no new tests in this round, so the focused classes were read from the full run's JUnit XML)

Result:

Pass: `CockpitDestinationTest` 12, `CockpitShellTest` 8, `CockpitShellNavigationTest` 13, `CockpitShellSelectionTest` 21, `CockpitShellSelectionRestoreTest` 3; 0 failures.

## Full test execution

Command:

`android/gradlew -p android createDebugUnitTestCoverageReport ktlintCheck`

Result:

Pass: BUILD SUCCESSFUL in 3m 29s, exit 0. `:app:testDebugUnitTest` and `:app:createDebugUnitTestCoverageReport` executed (not up to date). JUnit XML: 451 suites, 5517 tests, 0 failures, 0 errors, 0 skipped. `:app:ktlintCheck` Pass (up to date: sources unchanged since its last successful run).

## .taskcheck

Result:

Not run: `sh scripts/check.sh` also builds the server and web parts; the parent runs it in final validation. The Android parts this task affects (ktlint, full unit suite) passed above.

## Bugs found

None. All tests pass against the final code.

## Coverage

Status: verified (Android JaCoCo, fresh run on the final code).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured by intersecting JaCoCo XML per-line counters with the added/modified lines of `git diff -U0 5e380566bfd26ba3f0683b7d903ac60dcc029eb8 04497c289 -- android/app/src/main` (lines without JaCoCo entries, i.e. comments, blanks, declarations, are not counted). Executable changed lines: `CockpitDestination.kt` 43, 49; `CockpitShell.kt` 103, 104, 297-303, 328. Result: 12/12 lines covered (100%); branches on those lines 14/16 (87.5%).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-nav-new-bottombar`; base `5e380566bfd26ba3f0683b7d903ac60dcc029eb8` (supplied by the parent); final code `04497c289`; measured HEAD `de4dd60211cffa94ce1b183364107649e4cb91bb` (android tree identical to `04497c289`). Task code commits f9b4c67bc, 3c44df81e, b942d9a56, 97d11923a, 04497c289 per `git log`, consistent with the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): clean tree at HEAD de4dd6021. sha256 `CockpitShell.kt` 97d0fbaed9ffb3096e061dc68b14e739171d679aa5804438b996540a72c9e9a3, `CockpitDestination.kt` 2947119f76df412dddf07a18b4f884d8453967b1a4fdb93cd06bb2be729e4991, `CockpitShellSelectionTest.kt` a8c8d515e248dabf54c75947995922733565c5e38b8cd8fd7824cc2fc75b2ad5, `CockpitShellSelectionRestoreTest.kt` 2ffbeaa7487601a7c949fdd9eba06238ba86ff06528ca59e07f847e3e0ca465a.
- Exact included files; renamed/deleted/excluded files and reasons: included `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt` and `android/app/src/main/java/ch/sonensei/cockpit/ui/navigation/CockpitDestination.kt` (the only production files in the diff). Excluded: the 9 changed test files. No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`, Gradle 9.8.1 wrapper; `android/gradlew -p android createDebugUnitTestCoverageReport ktlintCheck` from the clone root; full `testDebugUnitTest`, no filter.
- Run start/completion time and exit status: started about 2026-11-14T10:54+01:00, report written 2026-11-14T10:57:27+01:00, exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` sha256 `68426d9116213fae642b0eaa9ec97eb8607aecf89111e1989600eafbad3530fb`; log `.tmp/tw-cov.log`; analysis script `.tmp/tw_cov.py`.
- Evidence outputs are fresh and inputs stayed unchanged: the test and report tasks executed in this run, the report mtime falls inside it, and HEAD and the `CockpitShell.kt` hash were the same before and after.
- Final run after latest test additions and production fixes: yes (final code incl. the CR-4 fix; no tests added afterwards).

Values below use covered/total (percentage).

| Changed file | Statements (JaCoCo instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt` | 2173/2277 (95.4%) | 125/149 (83.9%) | 51/56 (91.1%) | 184/186 (98.9%) |
| `android/app/src/main/java/ch/sonensei/cockpit/ui/navigation/CockpitDestination.kt` | 389/389 (100%) | 9/9 (100%) | 13/13 (100%) | 38/38 (100%) |
| Aggregate (summed counters, both files, none missing or excluded) | 2562/2666 (96.1%) | 134/158 (84.8%) | 64/69 (92.8%) | 222/224 (99.1%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CockpitShell.kt`:298 (`previousBackStackEntry ?: return null`, 1 of 2 branches) | Stacked screen with no entry below it | Not reachable: the graph's start destination is the Dashboard, so a stacked route (task, search) always lies on at least one entry, also for deep links (synthetic back stack). No test added. |
| `CockpitShell.kt`:302 (`resolved?.let`, null branch) | Entry below is neither a cockpit destination nor carries a stored route | Not reachable through the shell: every stacked entry below the current one was rendered as current earlier and stored its resolved route (which can only be null via line 298). The "null is never stored" contract is defensive. No test added. |
| `CockpitShell.kt`:267, 361 (missed lines) and partial branches at 83, 86, 109, 121, 199, 224-232, 265-266, 286, 338-343 | Pre-existing shell code (search title fallback, reminder requests, title resolution, `DestinationContent` dispatch, Compose-generated branches) | Outside the task's changed lines. No test added. |

## Unable to test

- Regression proof by reverting a fix (e.g. the `restoreState` change at `CockpitShell.kt`:328 or the CR-4 stored route) was not demonstrated, because the test writer may not modify production code. The recreation tests and the Dashboard-tab tests encode those scenarios.
- A mail opened inside the New view itself is shown within New's own screen (no separate route), so "mail opened from New" is tested as a mail item route opened from a task on New.
- `lintDebug` and `sh scripts/check.sh` (AC5) were not run here; the parent runs them in final validation.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.
