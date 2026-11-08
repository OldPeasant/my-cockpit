# Test Report

Date: 2026-11-08

Task: and-user-settings: Android reads and writes the synced user settings

Scope: base 80c9f39e15daef3e3c6c57536749ae4e264ebac7 (given by the parent) up to HEAD e97612486; task commits 215fcbda5, 57a4e8017, e97612486, as named by the parent. The test-writer could not run git: Bash was denied in this sub-agent session. The scope comes from the parent's prompt and the task's `touches` list, and the production files were read directly.

Requirements:
- tasks/and-user-settings.md (acceptance criteria and the ASSUMPTION notes from 2026-11-08)
- Class documentation of `UserSettingsRepository` ("an older answer never overwrites a newer one")
- Existing test conventions (JUnit4, MockK, kotlinx-coroutines-test, Robolectric Compose tests with `createComposeRule`/`createAndroidComposeRule`)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/core/settings/UserSettingsRepositoryEdgeCasesTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/core/settings/DataStoreUserSettingsStoreTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModelSettingsTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/inbox/InboxUserLabelsTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskDetailUserLabelsTest.kt` (new)

The test-writer did not touch the existing tests the parent already wrote (UserSettingsRepositoryTest, UserSettingsTest, CalendarViewModelSettingsTest, AppearanceViewModelTest, AppearanceSectionTest, CockpitSettingsThemeTest).

## Tests added or updated

Count: 23 new tests (7 + 5 + 5 + 4 + 2)

## Requirement coverage

The test-writer could not run any of the new tests in this session, because Bash (and so Gradle) was denied. The parent must run them, using the command in "Focused test execution".

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 repository: a load that started before a logout is not applied (generation guard) | UserSettingsRepositoryEdgeCasesTest `a load answered after a logout does not bring the old user's settings back` | focused command below | Not run: Gradle unavailable to the sub-agent |
| AC1 repository: an older GET answer must not overwrite a newer PATCH answer | UserSettingsRepositoryEdgeCasesTest `a refresh answered after an update does not overwrite the update's newer answer` | focused command below | Not run; expected to FAIL against the current code (TW-1) |
| AC1 repository: 409/428 count as conflicts, reload once, and do not retry the write | UserSettingsRepositoryEdgeCasesTest `409 and 428 also reload the settings and report a conflict without retrying the write` | focused command below | Not run |
| AC1 repository: cache read/write/clear failures do not break loading, applying or logout | UserSettingsRepositoryEdgeCasesTest `a broken cache does not stop the settings from loading, applying or clearing` | focused command below | Not run |
| AC1 repository: a 200 without a body and an unreadable answer are not taken | UserSettingsRepositoryEdgeCasesTest `a success without a body is not taken`, `an unreadable server answer fails the load and rejects the update` | focused command below | Not run |
| AC1 repository: an empty change sends nothing | UserSettingsRepositoryEdgeCasesTest `an empty change is not sent` | focused command below | Not run |
| AC1 offline cache: DataStore round trip, ETag removal, clear, corrupt or non-object JSON counts as no cache | DataStoreUserSettingsStoreTest (5 tests) | focused command below | Not run |
| AC3 time zone from settings: new events in the synced zone, device zone as fallback, settings loaded later apply, an event's own zone wins | EventEditorViewModelSettingsTest (5 tests) | focused command below | Not run |
| AC4 labels in inbox processing: renamed labels shown, others translated, chips still set the level, live update and logout, German translation of the server's English defaults | InboxUserLabelsTest (4 tests) | focused command below | Not run |
| AC4 labels in task detail: properties and edit-form chips | TaskDetailUserLabelsTest (2 tests) | focused command below | Not run |
| AC2 theme, AC5 strings | Covered by the parent's CockpitSettingsThemeTest / AppearanceSectionTest (German qualifier); the test-writer added nothing here | n/a | Not re-run by the test-writer |
| AC6 ktlintCheck testDebugUnitTest lintDebug, scripts/check.sh | n/a | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`; `sh scripts/check.sh` | Not run: Bash denied |

## Focused test execution

Command (for the parent to run):

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.core.settings.UserSettingsRepositoryEdgeCasesTest' --tests 'ch.sonensei.cockpit.core.settings.DataStoreUserSettingsStoreTest' --tests 'ch.sonensei.cockpit.calendar.edit.EventEditorViewModelSettingsTest' --tests 'ch.sonensei.cockpit.gtd.inbox.InboxUserLabelsTest' --tests 'ch.sonensei.cockpit.gtd.task.TaskDetailUserLabelsTest'`

Result:

Not run. Every Bash call in the test-writer session was denied ("Permission to use Bash has been denied"), so the tests were written without being compiled or run. Expected outcome: all tests pass except the TW-1 regression test, which should fail. The new files have not been checked by ktlint either: run `./gradlew ktlintCheck` (or ktlintFormat).

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Not run (Bash denied).

## .taskcheck

Result:

Not run (Bash denied). `.taskcheck` is `sh scripts/check.sh`.

## Bugs found

### A refresh answered after an update reverts the newly saved settings and ETag

Finding ID:

`TW-1`

Test:

`UserSettingsRepositoryEdgeCasesTest.a refresh answered after an update does not overwrite the update's newer answer`

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/core/settings/UserSettingsRepository.kt:134-151` (`refresh`). The GET runs outside `lock`. After it, the code only compares `generation`, which changes on `clear()` only, then calls `accept(response)`.

Requirement:

AC1 (cached StateFlow, update with ETag/If-Match), plus the class's own contract on `lock`: "Serializes loads, writes and clearing, so an older answer never overwrites a newer one."

Expected behavior:

A GET is sent (for example from `MainActivity.onStart`) and the server answers it with the state before a PATCH. If that answer arrives after the PATCH answer was applied, it is dropped. The settings stay at the PATCH result (theme LIGHT), and the ETag in memory and in the cache stays "2".

Actual behavior (from reading the code; the test has not been run):

`refresh` applies the stale answer, so the theme goes back to DARK and the ETag back to "1", in memory and in the DataStore cache. The user sees the theme they just picked jump back. The next theme change then gets 412, reloads and returns Conflict. The state heals on the next refresh. Possible fixes include treating any applied update as a newer generation (bump a counter in `accept` from `update`), or holding the lock across the GET. The parent or fixer chooses the fix.

Severity:

Medium. User-visible but self-healing. It happens when the user changes the theme while a start or sign-in refresh is still in flight on a slow network.

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Coverage

Status: unavailable. Bash is denied in this sub-agent session, so the test-writer could not run Gradle, Kover/JaCoCo or git.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-user-settings; base 80c9f39e15daef3e3c6c57536749ae4e264ebac7; HEAD e97612486 (abbreviated, as supplied by the parent; the test-writer could not resolve it); commits 215fcbda5, 57a4e8017, e97612486, from the parent's prompt.
- Source/test snapshot identifier (including uncommitted inputs): not available; no hashing without a shell. The uncommitted inputs are the 5 new test files listed above.
- Exact included files; renamed/deleted/excluded files and reasons: scope as described by the parent: `core/settings/` (UserSettings.kt, UserSettingsRepository.kt, LocalUserSettings.kt), `ui/theme/Theme.kt`, `settings/AppearanceViewModel.kt`, `settings/SettingsScreen.kt`, `calendar/agenda/CalendarViewModel.kt`, `calendar/edit/EventEditorViewModel.kt`, `gtd/inbox/InboxScreen.kt`, `gtd/task/TaskDetailScreen.kt`, the Next screen chips, and the MainActivity/CockpitApp/Share/Capture activity wiring. None of them was measured.
- Provider/version, command, working directory, and test selection: none run. The ARCHITECTURE.md "Coverage" command for android should be run by the parent.
- Run start/completion time and exit status: none.
- Generated report paths and report hashes/run identifier: none.
- Evidence outputs are fresh and inputs stayed unchanged: not applicable, no run.
- Final run after latest test additions and production fixes: no; no coverage run was possible.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| all task-changed production files | N/A (not measured) | N/A | N/A | N/A |

Uncovered-behavior review, done by reading the code because no coverage report exists:

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `UserSettingsRepository.kt` refresh generation check (line 148) | A logout during a load | UserSettingsRepositoryEdgeCasesTest generation test |
| `UserSettingsRepository.kt` stale GET vs PATCH ordering | An older answer overwrites a newer one | Regression test added, TW-1 |
| `UserSettingsRepository.kt` store read/write/clear catch blocks, null body, SerializationException, 409/428, empty change | Error paths of AC1 | UserSettingsRepositoryEdgeCasesTest |
| `UserSettingsRepository.kt` `DataStoreUserSettingsStore` | Offline cache for the start | DataStoreUserSettingsStoreTest |
| `UserSettingsRepository.kt` `UserSettingsModule` (Hilt provider, ReplaceFileCorruptionHandler, SessionCache binding) | Logout clears the settings | Not unit tested: Hilt glue. The behavior of `clear()` is covered by the parent's test |
| `UserSettingsRepository.kt` cacheRead branch "server answer arrived first" (`state.value == null`) | Cache must not overwrite a newer answer | Unreachable today: refresh/update/clear all join `cacheRead` first. Defensive code, no test |
| `EventEditorViewModel.kt` `zone` getter | AC3 for new and edited events | EventEditorViewModelSettingsTest |
| `InboxScreen.kt` / `TaskDetailScreen.kt` label call sites | AC4 | InboxUserLabelsTest, TaskDetailUserLabelsTest |
| Next screen filter chips using the labels | The assumption extends AC4 to Next | Not tested by the test-writer (outside AC4's wording); low risk, same helper |
| `MainActivity` onStart refresh, CockpitApp `start(signedIn())`, Share/Capture providing LocalUserSettings | Refresh triggers, theme in every activity | Not unit tested: activity wiring. `start()` is covered by the parent's test |

## Unable to test

- No test was compiled or run: Bash and Gradle were denied to this sub-agent. The parent has to run the focused command above, then ktlintCheck, testDebugUnitTest, lintDebug and `sh scripts/check.sh`.
- Coverage was not measured, for the same reason.
- Activity wiring (MainActivity onStart refresh, Share/Capture activities applying the theme) and the Hilt module were not tested. Follow-up is optional; a Robolectric activity test would be needed.
- The DataStore file corruption handler (`ReplaceFileCorruptionHandler`) was not tested: it lives in the Hilt provider. Corrupt JSON inside a valid file is tested.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion. TW-1 (a stale refresh answer overwrites a newer update) is expected to fail its regression test. None of the new tests could be run in this sub-agent session (Bash denied), so they and task-scoped coverage still need a run by the parent.
