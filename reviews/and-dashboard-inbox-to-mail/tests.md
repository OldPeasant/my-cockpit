# Test Report

Date: 2026-11-02

Task: and-dashboard-inbox-to-mail - Android: Dashboard Inbox card opens Mail when the GTD Inbox is empty

Reviewed revision: 472f9c23cbb23555e62a3fa2dd92a433d8dcc341 (last code commit: CR-3, KDoc of `destinationFor` corrected). Measured at HEAD 1994971662d690afed154acac1d715234cd08599. `git diff 472f9c23c..HEAD -- android` is empty: 199497166 touches only `reviews/`. `git diff 58a429a48..472f9c23c -- android` changes only the KDoc comment above `destinationFor` (1 line removed, 2 added). No executable code changed, so no new tests were needed. The KDoc grew by one line, so every line number after 116 moved down by one compared with the previous report.

Scope: base a97a99a7d1382ba5d2e0d04563b41970fe977561. Task code commits, supplied by the parent: e0d70f811 (impl), 9d788c645 (tests), 58a429a48 (fix for CR-2 and TW-1), 472f9c23c (KDoc fix for CR-3). Changed production file: `android/app/src/main/java/ch/sonensei/cockpit/dashboard/DashboardScreen.kt` (no renames or deletions). It matches the task's `touches`.

Requirements:
- `tasks/and-dashboard-inbox-to-mail.md` acceptance criteria
- Recorded assumption (CR-1 decision): a card with an error that kept its data follows that data. Only a card with no loaded data falls back to the GTD Inbox.
- `reviews/and-dashboard-inbox-to-mail/fixes.md` (TW-1: only the Inbox widget may redirect to Mail; CR-3: KDoc wording)
- `docs/ARCHITECTURE.md` "Coverage"; `AGENTS.md`

## Test files changed

None in this final pass. The existing task tests already cover the criteria and the fix:
- `android/app/src/test/java/ch/sonensei/cockpit/dashboard/DashboardDestinationTest.kt` (9 tests, including the fixer's regression test for TW-1)
- `android/app/src/test/java/ch/sonensei/cockpit/dashboard/DashboardInboxCardTest.kt` (9 Robolectric/Compose tests)

## Tests added or updated

Count: 0 in this pass (the change since the last pass is comment-only). The task has 18 tests: 17 written by the test-writer, plus the fixer's regression test `only the Inbox card redirects to Mail, even if another card got Inbox data`. I reviewed them against the final code. Every criterion, the CR-1 decision and the TW-1 guard has a test, so nothing was missing.

## Requirement coverage

Command (A): `cd android && ANDROID_HOME=$HOME/Android/Sdk ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` (final run on 472f9c23c, 19:40:11 to 19:43:17 +01:00, exit 0).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: 0 Inbox tasks and at least one unread mail opens Mail | `DashboardDestinationTest`: `an empty Inbox with unread mail opens Mail`, `an empty Inbox with exactly one unread mail opens Mail` (boundary 1); `DashboardInboxCardTest`: `an empty Inbox with unread mail opens Mail`, `the tap follows the data as it changes` | A | Pass |
| AC1: tasks present opens the GTD Inbox | `an Inbox with tasks opens the GTD Inbox, with or without unread mail`; Compose `an Inbox with tasks opens the GTD Inbox` | A | Pass |
| AC1: no unread mail opens the GTD Inbox | `an empty Inbox without unread mail opens the GTD Inbox` (pure and Compose) | A | Pass |
| AC1: data not loaded yet, or failed without data, opens the GTD Inbox | `an Inbox not loaded yet or whose load failed without data opens the GTD Inbox`; Compose `a loading or failed Inbox card opens the GTD Inbox`, `a failed Inbox without data opens the GTD Inbox` | A | Pass |
| Decision (CR-1): an error with kept data follows that data | `a failed refresh follows the data it kept` (FAILED and OFFLINE); Compose `a failed refresh that kept an empty Inbox with unread mail opens Mail`, `an offline refresh that kept an empty Inbox with unread mail opens Mail`, `a failed refresh that kept an Inbox with tasks opens the GTD Inbox` | A | Pass |
| Fix TW-1: only the Inbox widget redirects to Mail | `only the Inbox card redirects to Mail, even if another card got Inbox data` (all 8 other widgets, given mail-only Inbox data) | A | Pass |
| AC2: the card's icon stays the Inbox icon | `the Inbox card keeps the Inbox icon, not the Mail one` | A | Pass (structural, see "Unable to test") |
| AC2: other cards' destinations are unchanged | `the other cards keep their destination, loading or loaded`; existing `DashboardScreenTest.each card opens the matching screen` | A | Pass |
| AC3: `./gradlew ktlintCheck testDebugUnitTest lintDebug` passes | whole Android suite | A | Pass |

## Focused test execution

Command:

Part of (A). Per-class results from `android/app/build/test-results/testDebugUnitTest/`: `DashboardDestinationTest` 9 tests, `DashboardInboxCardTest` 9 tests, 0 failures, 0 errors (run at 18:41:38Z and 18:41:44Z, during the final run).

Result:

Pass

## Full test execution

Command:

`cd android && ANDROID_HOME=$HOME/Android/Sdk ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport`

Result:

Pass: exit 0, BUILD SUCCESSFUL in 3m 5s. 2883 unit tests, 0 failures, 0 errors, 0 skipped. `testDebugUnitTest`, `lintDebug` and `createDebugUnitTestCoverageReport` executed. `ktlintCheck` was UP-TO-DATE, which is a pass on unchanged inputs.

Note: during the previous pass on 58a429a48, one attempt failed with `NoSuchFileException` on `in-progress-results-generic.bin` because a concurrent Gradle build ran in the same `android/build`. No test assertion failed. This final run passed on the first attempt.

## .taskcheck

Result:

Not run by the test-writer. Its Android part passed above. The task changes no server or web code. The parent runs `.taskcheck` before `done`.

## Bugs found

None.

The doc nit from the previous pass (the KDoc of `destinationFor` said the data was "null while loading or after a failure") was fixed in 472f9c23c (CR-3). The KDoc now matches the CR-1 decision the tests check: the data is null until the first successful load, and a failed refresh keeps the last data.

## Coverage

Status: verified.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured from the JaCoCo XML per-line entries for the added/modified lines of `git diff -U0 a97a99a7d..HEAD -- android/app/src/main` at 472f9c23c (hunks +101, +115..126, +169..170). Executable changed lines: 121, 122, 124, 125 (`destinationFor`) and 169, 170 (`DashboardContent` items lambda). Covered 6/6 lines (100%). Branches on changed lines: 8/8 (100%, all on line 121, including the `this == WidgetId.INBOX` guard). Lines 101 and 115-120 are KDoc and not executable, so they are not counted.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-dashboard-inbox-to-mail; base a97a99a7d1382ba5d2e0d04563b41970fe977561; measured HEAD 1994971662d690afed154acac1d715234cd08599 (code identical to 472f9c23c); commits e0d70f811, 9d788c645, 58a429a48 and 472f9c23c as supplied by the parent, matching the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): SHA-256 before and after the run, unchanged: `DashboardScreen.kt` 5ca3f0f9b9bc0eed51c1dd8f16603053bb4a2fd352541ac2a839b6fcaa91299d; `DashboardDestinationTest.kt` e6cead6eceda1bb4d0fc8a02ffce0304c6d697d4c4ba7018921c363451755bf9; `DashboardInboxCardTest.kt` 80532a095b0006e676ebcc38be01bc6cad4d55a0db9e7036a3bc69a695708ee9. There were no uncommitted source or test changes.
- Exact included files; renamed/deleted/excluded files and reasons: included `android/app/src/main/java/ch/sonensei/cockpit/dashboard/DashboardScreen.kt`. Nothing renamed, deleted or excluded.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`; command (A) above, run in `android/`. Test selection: the whole debug unit test suite.
- Run start/completion time and exit status: 2026-11-02 19:40:11 to 19:43:17 +01:00, exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, written 2026-11-02 19:43:16 +01:00, sha256 535f0d7e75aff0f56be7f855bb2885f841c566f7fde574060266b27a9b87f704.
- Evidence outputs are fresh and inputs stayed unchanged: yes. `testDebugUnitTest` and `createDebugUnitTestCoverageReport` executed in this run (not UP-TO-DATE), and the snapshot hashes match after the run.
- Final run after latest test additions and production fixes: yes. It ran on the code of 472f9c23c, the last code commit.

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/dashboard/DashboardScreen.kt` | 2075/2091 (99.2%) (JaCoCo instructions) | 103/110 (93.6%) | 31/31 (100%) | 186/187 (99.5%) |

`destinationFor` alone: instructions 21/21, branches 8/8, lines 4/4.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `DashboardScreen.kt:64-65, 76-77` (synthetic branches of the default `modifier` parameter) | Not task code: Kotlin default-argument bridges | None, not material |
| `DashboardScreen.kt:155` (LaunchedEffect on `state.widgets`) | Not task code, there before this task | None, out of scope |
| `DashboardScreen.kt:225, 257-258` (exhaustiveness and fallthrough bytecode of the sealed `when (data)`) | Not task code, compiler-generated | None, not material |
| `DashboardScreen.kt:292` (one branch of the overdue-date check) | Not task code, there before this task | None, out of scope |

## Unable to test

- AC2's icon can't be checked in the rendered card. The card's `Icon` has `contentDescription = null` and no test tag, so it has no semantics node to assert on. Instead, the test checks the icon's source: `WidgetCard` draws `widget.destination.icon`, which is the Inbox icon and does not depend on the data. No follow-up needed.
- Navigating to Mail on a real device or emulator was not run. The criteria ask for unit and Compose tests, which run under Robolectric here.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. The existing tests cover every acceptance criterion, the CR-1 decision and the TW-1 fix. The full Android check passed on the final code (472f9c23c).
