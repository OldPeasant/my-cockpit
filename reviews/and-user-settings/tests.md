# Test Report

Date: 2026-11-08

Task: and-user-settings: Android reads and writes the synced user settings

Scope: second round, on the final code. Base a32b1930c26fc97f61c159db611ff19b2a731a3e (given by the parent) up to HEAD c317d934eb53ed1860057b5244d8907b04bd7451. Task commits (confirmed with `git log a32b1930c..HEAD`): fe6fd3c36, decac4add, 03a170513, 84c4cf231, c317d934e. c317d934e holds the CR-1/CR-2 fix (sequence tickets in `UserSettingsRepository`), made after the first test report. The working tree was clean when this round started. The only uncommitted change is the test file this round edited (see below).

Requirements:
- tasks/and-user-settings.md (acceptance criteria and the ASSUMPTION notes from 2026-11-08)
- reviews/and-user-settings/review.md (CR-1, CR-2) and fixes.md (the guarantee the sequence tickets give)
- KDoc of `UserSettingsRepository` (`lock`, `sequence`, `appliedSequence`, `refresh`)
- docs/ARCHITECTURE.md "Coverage"; existing test conventions (JUnit4, MockK, kotlinx-coroutines-test with `UnconfinedTestDispatcher`, Robolectric Compose tests)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/core/settings/UserSettingsRepositoryEdgeCasesTest.kt` (2 tests added in this round; uncommitted)

All task test files at HEAD: UserSettingsRepositoryTest, UserSettingsRepositoryEdgeCasesTest, DataStoreUserSettingsStoreTest, UserSettingsTest (core/settings); CalendarViewModelSettingsTest; EventEditorViewModelSettingsTest; InboxUserLabelsTest; TaskDetailUserLabelsTest; AppearanceViewModelTest, AppearanceSectionTest (settings); CockpitSettingsThemeTest (ui/theme). None of them was changed or weakened in this round apart from the 2 additions.

## Tests added or updated

Count: 2 added in this round

- `UserSettingsRepositoryEdgeCasesTest.a refresh started while an update is in flight is taken after the update`. A refresh that waits for the lock while a PATCH is pending must get a later ticket than the update. Its answer, sent after the PATCH, is taken (theme SYSTEM, ETag "3" in memory and in the cache). This protects against over-dropping: if a ticket were taken outside the lock or before the update's, this refresh would be dropped wrongly.
- `UserSettingsRepositoryEdgeCasesTest.a later refresh that fails does not make an earlier answer be dropped`. Two refreshes overlap. The later one gets 503 (OFFLINE), and the earlier one still answers LOADED and is applied. This pins the fixer's statement that non-2xx answers do not move `appliedSequence`.

Why only these two: the first-round tests together with the fixer's tests already cover a GET pending during logout, a stale GET after a PATCH, and overlapping refreshes answered in both orders. The two cases above were the remaining ordering cases that the ticket design must not drop by mistake. They were not covered.

## Requirement coverage

The test-writer cannot run Gradle in this sub-agent session (a Gradle call was denied: "Permission to use Bash has been denied"). The "Pass" results below come from the parent's runs on HEAD c317d934e, as reported to the test-writer. Those runs did not include the 2 tests added in this round.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 repository loads GET, exposes StateFlow, caches for offline start | UserSettingsRepositoryTest `refresh loads, parses and caches the settings`, `the cached settings are shown at start and stay when the server cannot be reached`, `a server error keeps the last settings`; DataStoreUserSettingsStoreTest (5) | parent: `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.core.settings.*' ktlintCheck` | Pass (parent, HEAD c317d934e) |
| AC1 typed update with PATCH + If-Match, 412 reloads; 409/428 the same, no retry; offline/rejected/server error mapping; empty change; not loaded | UserSettingsRepositoryTest `update sends only the changed key with If-Match and takes the answer`, `a 412 reloads the settings and reports a conflict`, `update without loaded settings sends nothing`, `update maps offline, rejected and server errors`; EdgeCases `409 and 428 also reload...`, `an empty change is not sent`, `a success without a body is not taken`, `an unreadable server answer...` | same | Pass (parent) |
| AC1 ordering (CR-1/CR-2): older answer never overwrites a newer one; logout drops a pending GET | EdgeCases `a load answered after a logout...`, `a refresh answered after an update...`, `of two overlapping refreshes the later one wins...`, `of two overlapping refreshes answered in order...` | same | Pass (parent) |
| AC1 ordering: a newer answer is not dropped by mistake | EdgeCases `a refresh started while an update is in flight is taken after the update`, `a later refresh that fails does not make an earlier answer be dropped` (new) | focused command below | Not run: added in this round, Gradle denied to the sub-agent; the parent must run it |
| AC1 storage failures, logout clears, refresh at sign-in | EdgeCases `a broken cache...`; UserSettingsRepositoryTest `clear forgets the settings and the cache`, `start refreshes at each new sign-in but not for the state at start` | parent command above | Pass (parent) |
| AC2 theme app-wide, dynamic color for system | CockpitSettingsThemeTest; AppearanceViewModelTest; AppearanceSectionTest | parent: `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug -q` | Pass (parent, 2940 tests, 0 failures) |
| AC3 week start and time zone from settings, locale fallback | CalendarViewModelSettingsTest; EventEditorViewModelSettingsTest (5) | same | Pass (parent) |
| AC4 labels in inbox processing and task detail | InboxUserLabelsTest (4); TaskDetailUserLabelsTest (2); UserSettingsTest (label parsing/defaults) | same | Pass (parent) |
| AC5 strings in English and German | AppearanceSectionTest (German qualifier); both `strings_settings.xml` files changed in the diff; lintDebug (MissingTranslation) | same | Pass (parent) |
| AC6 ktlintCheck testDebugUnitTest lintDebug; `sh scripts/check.sh` | full suite | parent: android command above; `sh scripts/check.sh` | android: Pass (parent). check.sh: Not run by the test-writer (Bash denied); the parent runs it |

## Focused test execution

Command (for the parent, covers the 2 new tests and the whole settings package):

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.core.settings.UserSettingsRepositoryEdgeCasesTest' ktlintCheck`

Result:

Not run by the test-writer (Gradle denied in this sub-agent session). The new tests were written but not compiled here. Both are expected to pass on HEAD c317d934e: the expectation comes from tracing `refresh`, `update` and `apply` by hand with `UnconfinedTestDispatcher` and the FIFO `Mutex`. Earlier parent run on HEAD c317d934e (without the 2 new tests): `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.core.settings.*' ktlintCheck` -> passed.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug -q`

Result:

Pass, run by the parent on HEAD c317d934e: exit 0, 2940 test cases, 0 failures. It does not yet include the 2 tests added in this round.

## .taskcheck

Result:

Not run by the test-writer (Bash/Gradle denied). `.taskcheck` is `sh scripts/check.sh`; the parent runs it.

## Bugs found

None in this round. TW-1 from the first round (a stale refresh answer overwriting a newer update) is the same problem as CR-1. It is fixed in c317d934e. Its regression test `a refresh answered after an update does not overwrite the update's newer answer` is in the parent's passing run on HEAD c317d934e.

## Coverage

Status: unavailable. The Android coverage command (`cd android && ./gradlew createDebugUnitTestCoverageReport`, JaCoCo via AGP, docs/ARCHITECTURE.md "Coverage") could not be run: every Gradle call in this sub-agent session is denied ("Permission to use Bash has been denied"). No coverage report exists in the workspace: `android/app/build/reports/coverage/test/debug/` is not there. So there is no fresh or stale report to read, and no numbers are given.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-user-settings; base a32b1930c26fc97f61c159db611ff19b2a731a3e (from the parent); HEAD c317d934eb53ed1860057b5244d8907b04bd7451; commits fe6fd3c36, decac4add, 03a170513, 84c4cf231, c317d934e. The parent named them, and `git log a32b1930c..HEAD` lists exactly these 5.
- Source/test snapshot identifier (including uncommitted inputs): HEAD c317d934e, plus the uncommitted `UserSettingsRepositoryEdgeCasesTest.kt`, sha256 fb1bf5989e51a09a8e2f12bc443f2c097f270dfac693e84cde05726497d2c40d.
- Exact included files (from `git diff --name-status a32b1930c..HEAD -- android/app/src/main`): added `core/settings/LocalUserSettings.kt`, `core/settings/UserSettings.kt`, `core/settings/UserSettingsRepository.kt`, `settings/AppearanceViewModel.kt`; modified `CockpitApp.kt`, `MainActivity.kt`, `calendar/agenda/CalendarViewModel.kt`, `calendar/edit/EventEditorViewModel.kt`, `capture/entry/CaptureActivity.kt`, `capture/share/ShareActivity.kt`, `gtd/inbox/InboxScreen.kt`, `gtd/next/NextScreen.kt`, `gtd/task/TaskDetailScreen.kt`, `settings/SettingsScreen.kt`, `ui/theme/Theme.kt` (all under `android/app/src/main/java/ch/sonensei/cockpit/`). Excluded: `res/values/strings_settings.xml` and `res/values-de/strings_settings.xml`, which are resources with no executable code. No renamed or deleted files.
- Provider/version, command, working directory, and test selection: not run (JaCoCo via AGP `enableUnitTestCoverage`; `cd android && ./gradlew createDebugUnitTestCoverageReport`; all debug unit tests).
- Run start/completion time and exit status: none (no run).
- Generated report paths and report hashes/run identifier: none. The expected path is `android/app/build/reports/coverage/test/debug/report.xml`, which does not exist.
- Evidence outputs are fresh and inputs stayed unchanged: not applicable, no run.
- Final run after latest test additions and production fixes: no. Gradle is denied to the sub-agent. To get numbers, the parent can run the coverage command above and read the per-file `sourcefile` counters for the files listed.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| all 15 task-changed production files listed above | N/A (not measured) | N/A | N/A | N/A |

Uncovered-behavior review. No coverage report exists, so this review was done by reading the final code:

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `UserSettingsRepository.kt:151-165` refresh ticket and `ticket < appliedSequence` drop | AC1, CR-1: older answer dropped after update, later refresh, or logout | Covered by EdgeCases logout / update / overlapping-refresh tests (parent run: pass) |
| `UserSettingsRepository.kt:151,187` ticket order when a refresh waits behind a pending PATCH | A newer answer must not be dropped | New test `a refresh started while an update is in flight is taken after the update` (not run yet) |
| `UserSettingsRepository.kt:224-225` non-2xx does not call `apply` and so does not move `appliedSequence` | A failed later refresh must not discard an earlier good answer | New test `a later refresh that fails does not make an earlier answer be dropped` (not run yet) |
| `UserSettingsRepository.kt:131` cache read applies only when `state.value == null` | Cache must not replace a server answer | Not reachable: `refresh`, `update` and `clear` all `join()` the cache read first (the code comment says this too). Defensive, no test |
| `UserSettingsRepository.kt:198` post-conflict refresh | AC1 412 reloads | UserSettingsRepositoryTest `a 412 reloads...`, EdgeCases 409/428 |
| `UserSettingsRepository.kt:295-313` `UserSettingsModule` (DataStore factory with `ReplaceFileCorruptionHandler`, SessionCache binding) | Logout clears settings; corrupt file replaced | Hilt glue, not unit tested. `clear()` is covered; corrupt JSON inside a valid file is covered by DataStoreUserSettingsStoreTest |
| `MainActivity.kt` onStart refresh, `CockpitApp.kt` `start(signedIn())`, `CaptureActivity.kt` / `ShareActivity.kt` providing the theme | Refresh triggers, theme in every activity | Activity wiring, not unit tested; `start()` is covered by UserSettingsRepositoryTest |
| `NextScreen.kt` filter chips using the labels | Assumption extends AC4 to Next | Not tested (outside AC4's wording); same label helper as Inbox/TaskDetail, which are tested |

## Unable to test

- The 2 new tests were not compiled or run, and neither was ktlint on the edited file: Gradle is denied to this sub-agent. The parent must run the focused command above (which includes ktlintCheck).
- Task-scoped coverage was not measured, for the same reason; no report exists in the workspace.
- Activity wiring (MainActivity onStart, Capture/Share theme), the Hilt module and the DataStore file corruption handler are not unit tested. Follow-up is optional: it would need Robolectric activity tests.
- CR-3 (a possible one-frame system theme at cold start) is out of scope by the parent's decision and is not testable in a unit test.

## Summary

No production defects found in this round. The 2 tests added are not run yet, so this round has not passed validation. The CR-1/TW-1 fix holds under the existing ordering tests, according to the parent's run on HEAD c317d934e. The 2 new ordering tests, which check that a newer answer is not dropped by mistake, still need a run by the parent. Task-scoped coverage could not be measured because Gradle is denied to the sub-agent.
