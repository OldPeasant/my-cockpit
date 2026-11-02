# Test Report

Date: 2026-11-02

Task: and-dashboard-inbox-to-mail - Android: Dashboard Inbox card opens Mail when the GTD Inbox is empty

Scope: task base a97a99a7d1382ba5d2e0d04563b41970fe977561 (claim commit), task code commit 9486c4e28b014e14570cce7902bd5466c4c59187 as supplied by the parent. Since then, the parent's history re-hashed it to e0d70f811 (same diff), on top of 6c45c0b23 (assume, tasks/ only). Measured at HEAD 58fbc63a510e841b52a853b4323fba1690ade2df. `git diff 9486c4e28 HEAD` touches only `tasks/` and `reviews/`, so the production code is identical. Changed production file: `android/app/src/main/java/ch/sonensei/cockpit/dashboard/DashboardScreen.kt` (one file, no renames or deletions).

Requirements:
- `tasks/and-dashboard-inbox-to-mail.md` acceptance criteria
- Parent decision (relayed after the code review): a failed or offline refresh keeps the last loaded data, and the card follows the data it shows. An error with data `Inbox(Count(0,false), Count(26,true))` opens Mail. An error without data opens the GTD Inbox.
- `docs/ARCHITECTURE.md` "Coverage"; conventions in `AGENTS.md` and the existing `DashboardScreenTest.kt`

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/dashboard/DashboardDestinationTest.kt` (new, plain JUnit, pure tests of `WidgetId.destinationFor`)
- `android/app/src/test/java/ch/sonensei/cockpit/dashboard/DashboardInboxCardTest.kt` (new, Robolectric + Compose, taps on the rendered Inbox card)

## Tests added or updated

Count: 17 (8 in DashboardDestinationTest, 9 in DashboardInboxCardTest). No existing test was changed.

## Requirement coverage

Commands: (F) `cd android && ANDROID_HOME=$HOME/Android/Sdk ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.dashboard.*'`; (A) `cd android && ANDROID_HOME=$HOME/Android/Sdk ./gradlew ktlintCheck testDebugUnitTest lintDebug`.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: 0 Inbox tasks + at least one unread mail opens Mail | `DashboardDestinationTest`: `an empty Inbox with unread mail opens Mail` (Count(0,false)/Count(26,true)), `an empty Inbox with exactly one unread mail opens Mail` (boundary 1, more=false); `DashboardInboxCardTest`: `an empty Inbox with unread mail opens Mail` (tap -> onOpen(Mail)), `the tap follows the data as it changes` | F, A | Pass |
| AC1: tasks present opens the GTD Inbox | `an Inbox with tasks opens the GTD Inbox, with or without unread mail` (1 task, 50+ tasks, tasks with no mail); Compose `an Inbox with tasks opens the GTD Inbox` (Count(3)/Count(26,true)) | F, A | Pass |
| AC1: no unread mail opens the GTD Inbox | `an empty Inbox without unread mail opens the GTD Inbox` (pure and Compose) | F, A | Pass |
| AC1: data not loaded yet or failed (no data) opens the GTD Inbox | `an Inbox not loaded yet or whose load failed without data opens the GTD Inbox` (null, default WidgetState, FAILED/OFFLINE without data); Compose `a loading or failed Inbox card opens the GTD Inbox`, `a failed Inbox without data opens the GTD Inbox` | F, A | Pass |
| Parent decision: an error with the kept data follows that data | pure `a failed refresh follows the data it kept` (FAILED and OFFLINE: kept 0/26+ -> Mail, kept 2/26+ -> Inbox); Compose `a failed refresh that kept an empty Inbox with unread mail opens Mail`, `an offline refresh that kept an empty Inbox with unread mail opens Mail`, `a failed refresh that kept an Inbox with tasks opens the GTD Inbox` | F, A | Pass |
| AC2: the card's icon stays the Inbox icon | `the Inbox card keeps the Inbox icon, not the Mail one` (WidgetCard draws `widget.destination.icon`, which equals `CockpitDestination.Inbox.icon` and differs from Mail's) | F, A | Pass (structural only, see "Unable to test") |
| AC2: other cards' destinations are unchanged | `the other cards keep their destination, loading or loaded` (all 7 other widgets: `destination`, `destinationFor(null)`, `destinationFor(own data)`); existing `DashboardScreenTest.each card opens the matching screen` (unchanged, still passes) | F, A | Pass |
| AC3: `./gradlew ktlintCheck testDebugUnitTest lintDebug` passes | whole Android suite | A | Pass |

## Focused test execution

Command:

`cd android && ANDROID_HOME=$HOME/Android/Sdk ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.dashboard.*'`

Result:

Pass: BUILD SUCCESSFUL, 79 tests across 9 dashboard suites, 0 failures, 0 skipped. My first run failed because of a mistake in my own test, which passed Inbox data to non-Inbox widgets. Real states never hold that. I fixed the test, not the code. See "Observations".

## Full test execution

Command:

`cd android && ANDROID_HOME=$HOME/Android/Sdk ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Pass: exit 0, BUILD SUCCESSFUL in 3m 22s (2026-11-02T19:15:09+01:00 to 19:18:32+01:00), 2882 unit tests, 0 failures, 0 errors, 0 skipped. ktlintCheck and lintDebug passed.

## .taskcheck

Result:

Not run by the test-writer. The Android part of it (`ktlintCheck testDebugUnitTest lintDebug`) passed above. This task changes no server or web code. The parent runs `.taskcheck` before `done`.

## Bugs found

None.

Observation (not a defect, no fix required): `WidgetId.destinationFor` does not check that the receiver is `WidgetId.INBOX`. Any widget given `WidgetData.Inbox` data with 0 tasks and unread mail would return Mail. In the app this can't happen: `DashboardRepository.load(widget)` returns each widget's own data type, and `DashboardContent` passes `state.state(widget).data`. The tests therefore give each widget its own data type. If the parent wants that to hold by construction, it could add `this == WidgetId.INBOX` to the condition, but that is optional.

## Coverage

Status: verified.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured by hand from the JaCoCo XML `<line>` entries for the added/modified executable lines of `git diff -U0 a97a99a7d..9486c4e28` (hunks +115..125 and +168..169). The executable changed lines are 120, 121, 123, 124 (`destinationFor`) and 168, 169 (`DashboardContent` items lambda). Covered: 6/6 lines (100%). Branches on changed lines: 6/6 (100%, all on line 120). The comment and KDoc lines 115-119 are not executable and are left out of the count.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-dashboard-inbox-to-mail; base a97a99a7d1382ba5d2e0d04563b41970fe977561; measured HEAD 58fbc63a510e841b52a853b4323fba1690ade2df. Scope came from the parent (task commit 9486c4e28, now e0d70f811 with an identical diff) and matches the task `touches` field.
- Source/test snapshot identifier (including uncommitted inputs): SHA-256 before and after the run, unchanged: `DashboardScreen.kt` 6c6a5756d5ddc0a61ee020cb9cfb30c2349cdd73bb944415f6d4de805515a380; `DashboardDestinationTest.kt` 8495e52b6176db7bbf6bcae29fa968ca5c3170fc769b86ab5162e4f619e98886; `DashboardInboxCardTest.kt` 80532a095b0006e676ebcc38be01bc6cad4d55a0db9e7036a3bc69a695708ee9. The new tests were untracked during the run and are committed with this report.
- Exact included files; renamed/deleted/excluded files and reasons: included `android/app/src/main/java/ch/sonensei/cockpit/dashboard/DashboardScreen.kt`. Nothing renamed, deleted or excluded.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`; `ANDROID_HOME=$HOME/Android/Sdk ./gradlew createDebugUnitTestCoverageReport` in `android/`. Test selection: the whole debug unit test suite. Its `testDebugUnitTest` was UP-TO-DATE from the full run just before, on identical inputs (hashes verified), so the execution data comes from that 2882-test run.
- Run start/completion time and exit status: full test run 19:15:09 to 19:18:32 (exit 0); coverage report 19:18:39 to 19:18:43 +01:00 (exit 0).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` (written 2026-11-02 19:18:43, sha256 14ff43c70b49f58ce3cfc7b32f0cf8c83cbc91988b3a9ccff2c3488c4ba50e64), plus `index.html` next to it.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report was regenerated by this run, and the snapshot hashes matched after the run.
- Final run after latest test additions and production fixes: yes. It ran after all tests were added. The production code has not changed since 9486c4e28.

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/dashboard/DashboardScreen.kt` | 2072/2088 (99.2%) (JaCoCo instructions) | 101/108 (93.5%) | 31/31 (100%) | 186/187 (99.5%) |

`destinationFor` alone: instructions 18/18, branches 6/6, lines 4/4.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `DashboardScreen.kt:64-65, 76-77` (default `modifier` parameter synthetic branches) | Not task code: Kotlin default-argument bridges | None, not material |
| `DashboardScreen.kt:154` (LaunchedEffect on `state.widgets`) | Not task code, already there before this task | None, out of scope |
| `DashboardScreen.kt:224, 256-257` (`when (data)` exhaustiveness and fallthrough bytecode) | Not task code, compiler-generated sealed `when` paths | None, not material |
| `DashboardScreen.kt:291` (one branch of the overdue-date check) | Not task code, already there before this task | None, out of scope |

## Unable to test

- AC2's icon can't be checked in the rendered card. The card's `Icon` has `contentDescription = null` and no test tag, so it has no semantics node to assert on. Comparing pixels would need Robolectric native graphics, which the project doesn't use for this. Instead, the test asserts that the card's icon source (`WidgetId.INBOX.destination.icon`) is the Inbox icon and not the Mail one, and that `WidgetCard` uses `widget.destination`, which this task did not change. No follow-up needed unless the icon is ever made to depend on the data.
- Navigating to the Mail screen on a real device (Compose UI test on an emulator) was not run. The criteria ask for unit and Compose tests, and the tests here use Robolectric.

## Summary

Tests added and all validation passed. Task-scoped coverage is sufficient: every changed executable line and branch is covered. No production defects found.
