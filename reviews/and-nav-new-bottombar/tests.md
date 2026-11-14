# Test Report

Date: 2026-11-14

Task: and-nav-new-bottombar - Android: New replaces Inbox in the bottom bar (Inbox moves to More)

Scope: task commits 24ba5b930 and b0bdab0d3 on main, `git diff 5e380566bfd26ba3f0683b7d903ac60dcc029eb8..b0bdab0d3657e8c2429d78020d9c56691b83b7f4` (Android only), plus the new untracked test file listed below. No other uncommitted changes in the clone at measurement time (apart from the code reviewer's `reviews/and-nav-new-bottombar/review.md`).

Requirements:
- `tasks/and-nav-new-bottombar.md` acceptance criteria and its recorded assumption (stacked screens highlight the tab of the nearest destination below them)
- Parent worker instructions (focus on selection on stacked screens, Dashboard tab after a card, system Back)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/ui/CockpitShellSelectionTest.kt` (new)

The existing tests updated by the parent worker (`CockpitDestinationTest`, `CockpitShellTest`, `CockpitShellNavigationTest`, `MailBackToOriginInAppTest`, `NewScreenInAppTest`, `InboxGraphTest`, `DashboardInAppTest`) were reviewed and left unchanged.

## Tests added or updated

Count: 17 (all in `CockpitShellSelectionTest`)

The tests use the real `CockpitShellFrame` (real bottom bar, NavHost, task detail and search routes, real `MoreScreen`) with plain content for the other destinations. The Dashboard gets two buttons standing in for its Inbox and Today cards; they call the frame's `onOpen` callback, which is what the real `DashboardScreen` cards call. System Back goes through the activity's `OnBackPressedDispatcher`. Test ids are fictitious UUIDs.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 bottom bar Dashboard, New, Calendar, Mail, More; New opens the New view | existing `CockpitDestinationTest`, `CockpitShellTest.bottomBarSwitchesBetweenAllMainDestinations`; new `routesInboxAndNewStillOpenTheirScreens` | `android/gradlew -p android createDebugUnitTestCoverageReport` (full unit suite) | Pass |
| AC2 Inbox is the first More entry and opens the Inbox; New not on More | existing `CockpitDestinationTest`, `CockpitShellTest.moreListsEverySecondaryDestination`, `InboxGraphTest`; new `inboxOpenedFromMoreSelectsMoreAndATaskOnItKeepsMoreSelected` | same | Pass |
| AC3 New highlighted on New and on a task/mail opened from it | `aTaskOpenedOnTopOfNewKeepsNewSelected`, `aMailOrTaskOpenedFromATaskOnNewKeepsNewSelected`, `searchOpenedFromNewKeepsNewSelectedAndBackReturnsToNew`, `backFromATaskOnNewReturnsToNewWithNewStillSelected`, `newTabRestoresTheTaskLeftOpenOnIt` | same, and focused `--tests 'ch.sonensei.cockpit.ui.CockpitShellSelectionTest'` | Pass |
| AC3 More highlighted on the Inbox (from More, from the card, from the `inbox` route, and on a task opened on it) | `inboxOpenedFromMoreSelectsMoreAndATaskOnItKeepsMoreSelected`, `inboxOpenedFromTheDashboardCardSelectsMore`, `routesInboxAndNewStillOpenTheirScreens` | same | Pass |
| Stacked screen on the Dashboard keeps Dashboard highlighted (assumption: nearest destination below) | `aTaskOpenedOnTopOfTheDashboardKeepsDashboardSelected` | same | Pass |
| AC4 Dashboard Inbox/Today card, then the Dashboard tab returns to the Dashboard with nothing below it | `dashboardTabAfterTheInboxCardShowsTheDashboardWithNothingBelow`, `dashboardTabAfterTheTodayCardShowsTheDashboardWithNothingBelow` | same | Pass |
| AC4 regression for the `restoreState` change: card, another tab, then the Dashboard tab does not bring the Inbox/Today back | `dashboardTabAfterTheInboxCardAndAnotherTabDoesNotBringTheInboxBack`, `dashboardTabAfterTheTodayCardAndNewDoesNotBringTodayBack` | same | Pass |
| AC4 routes `inbox` and `new` still work | `routesInboxAndNewStillOpenTheirScreens` | same | Pass |
| AC4 Back from New returns to the Dashboard (nothing below) | `systemBackFromNewReturnsToTheDashboard` | same | Pass |
| AC4 Back from Inbox opened from More returns to the More list, then the Dashboard | `systemBackFromInboxOpenedFromMoreReturnsToTheMoreList`, `backArrowFromInboxOpenedFromMoreReturnsToTheMoreList` | same | Pass |
| AC4 Back from Inbox opened from the Dashboard card returns to the Dashboard | `systemBackFromInboxOpenedFromTheDashboardCardReturnsToTheDashboard` | same | Pass |
| AC5 tests updated; `ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass | ktlint and the full Android unit suite ran here; `lintDebug` and `scripts/check.sh` not run | `android/gradlew -p android ktlintCheck -q`; coverage command above | ktlint Pass; unit suite Pass; `lintDebug` / `check.sh` Not run: left to the parent's final validation (step 8), no production change by the test writer |

## Focused test execution

Command:

`android/gradlew -p android testDebugUnitTest --tests 'ch.sonensei.cockpit.ui.CockpitShellSelectionTest' -q`

Result:

Pass (17 tests). The first run had 3 failures caused by the test setup (the frame rendered plain content instead of the More list, so the Inbox entry could not be found); fixed in the test by rendering the real `MoreScreen`. No production defect involved.

## Full test execution

Command:

`android/gradlew -p android createDebugUnitTestCoverageReport` (runs `:app:testDebugUnitTest`, the full Android unit suite, then the JaCoCo report)

Result:

Pass: BUILD SUCCESSFUL in 3m 39s; JUnit XML results 5510 tests, 0 failures, 0 errors, 0 skipped (`CockpitShellSelectionTest` 17/17).

Also: `android/gradlew -p android ktlintCheck -q` -> Pass (after wrapping one chained call in the new test).

## .taskcheck

Result:

Not run: `sh scripts/check.sh` also builds the server and web parts and the parent runs it as part of final validation; the Android lint/unit parts that this test change can affect (ktlint, full unit suite) passed above.

## Bugs found

None. All new tests pass against the task implementation.

## Coverage

Status: verified (whole-file, Android JaCoCo, fresh run on the snapshot below).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured by intersecting the JaCoCo XML per-line entries with the added/modified lines of `git diff -U0 5e380566b..HEAD -- android/app/src/main`. Executable changed lines: `CockpitDestination.kt` 43, 49; `CockpitShell.kt` 103, 104, 105, 312. Result 6/6 lines covered (100%), with 8/8 JaCoCo branches on those lines covered (line 104: 4/4, line 312: 4/4). Comment, blank and import lines are not executable and not counted.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-nav-new-bottombar`; base `5e380566bfd26ba3f0683b7d903ac60dcc029eb8` (supplied by the parent, the task's assume commit right after the claim); HEAD `b0bdab0d3657e8c2429d78020d9c56691b83b7f4`; task commits 24ba5b930 and b0bdab0d3 (supplied by the parent, consistent with `git log` and the diff).
- Source/test snapshot identifier (including uncommitted inputs): HEAD b0bdab0d3 plus untracked `CockpitShellSelectionTest.kt` sha256 `6d24968a2462a70ca65abb9384cb65f1d9f14b26a8cc0391001d36f86e74ec39`; `CockpitShell.kt` sha256 `5ae413a2233c395ae357bf55370dc4f9049595733b6cf82ae4d0102f0f08ec51`; `CockpitDestination.kt` sha256 `2947119f76df412dddf07a18b4f884d8453967b1a4fdb93cd06bb2be729e4991`.
- Exact included files; renamed/deleted/excluded files and reasons: included `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt`, `android/app/src/main/java/ch/sonensei/cockpit/ui/navigation/CockpitDestination.kt` (the only production files in the diff). Excluded: the 7 changed test files (tests). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP 9.4.1 `enableUnitTestCoverage`, Gradle 9.8.1 wrapper; `android/gradlew -p android createDebugUnitTestCoverageReport` from the clone root; full `testDebugUnitTest` (no filter).
- Run start/completion time and exit status: started 2026-11-14T09:59:00+01:00, report written 2026-11-14T10:02:41+01:00, exit 0 (BUILD SUCCESSFUL).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` sha256 `a36a8c3fe4e0d4a1dae8fc0f389bbe892670af10231afe9870113a6e4b58ce73`; log `.tmp/tw-and-nav-coverage.log`.
- Evidence outputs are fresh and inputs stayed unchanged: the log shows `:app:testDebugUnitTest` and `:app:createDebugUnitTestCoverageReport` executed (not up to date) in this run, report mtime falls inside the run, and the three file hashes and HEAD were identical before and after the run.
- Final run after latest test additions and production fixes: yes (no production fixes so far; the new test file was final before the run).

Values below use covered/total (percentage).

| Changed file | Statements (JaCoCo instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt` | 2150/2249 (95.6%) | 119/141 (84.4%) | 51/56 (91.1%) | 178/180 (98.9%) |
| `android/app/src/main/java/ch/sonensei/cockpit/ui/navigation/CockpitDestination.kt` | 389/389 (100%) | 9/9 (100%) | 13/13 (100%) | 38/38 (100%) |
| Aggregate (summed counters, both files, no files missing or excluded) | 2539/2638 (96.2%) | 128/150 (85.3%) | 64/69 (92.8%) | 216/218 (99.1%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CockpitShell.kt`:268 (missed line) | Title fallback when a search item route has no type argument | Pre-existing code, not changed by this task; not reachable through the shell's own routes. No test added. |
| `CockpitShell.kt`:345 (missed line) and the partial branches at 84, 87, 110, 122, 200, 225-233, 266-267, 287, 322-327 | Pre-existing shell code (reminder open requests, title resolution, FAB clearance, `DestinationContent` dispatch, Compose-generated branches) | Outside the task's changed lines; the task's changed lines 103-105 and 312 are fully covered including all their branches. No test added. |

## Unable to test

- Regression proof by reverting the fix: the `restoreState` change (CockpitShell.kt:312) could not be shown to fail without the fix, because the test writer may not modify production code. The tests `dashboardTabAfterTheInboxCardAndAnotherTabDoesNotBringTheInboxBack` and `dashboardTabAfterTheTodayCardAndNewDoesNotBringTodayBack` encode the scenario the parent described. Follow-up optional: the parent can confirm by temporarily reverting line 312 locally.
- A mail opened inside the New view itself is not a separate route (New shows it within its own screen), so "mail opened from New" was tested as a mail item route opened from a task on New.
- `lintDebug` and `sh scripts/check.sh` (AC5) were not run here; the parent runs them in final validation.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.
