# Test Report

Date: 2026-11-01

Task: and-mail-search - Android mail: search box with 'search on server' option

Scope: second pass after the fixer, for the final code. Task base `4f351d00b`, final code HEAD `af2f5113a` (commits `7cc2dddb0` implementation, `af2f5113a` review fixes plus the parent's scroll-to-top change), as given by the parent worker; task ownership from the parent and the task's `touches`. Uncommitted inputs: the two test files edited in this pass (listed below) and this report. Git and Gradle were not available to the test-writer in this pass (Bash denied), so file hashes are not recorded; the parent's commit of these files identifies the tested snapshot.

Requirements:
- `tasks/and-mail-search.md` acceptance criteria and its three recorded assumptions
- `reviews/and-mail-search/fixes.md` (CR-1 scroll position, CR-2 server search uses the box text, two failing tests)
- The parent's change after the fixer: `MailSearchState.version` is set to the search generation on each successful answer; `MailRoute` collects `uiState.map { it.search?.version }.distinctUntilChanged().drop(1)` and calls `searchState.requestScrollToItem(0)`
- `AGENTS.md` project notes, `docs/ARCHITECTURE.md` "Coverage", existing test conventions in `android/app/src/test/java/ch/sonensei/cockpit/mail/list/`

## Test files changed

This pass (added tests only; no existing test changed):

- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailSearchViewModelEdgeCasesTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailSearchRouteInAppTest.kt`

Earlier pass (kept): the same two files plus `MailSearchScreenEdgeCasesTest.kt` (20 tests); the fixer then added or adjusted tests in `MailSearchScreenTest`, `MailSearchScreenEdgeCasesTest`, `MailSearchViewModelEdgeCasesTest` and `MailSearchRouteInAppTest` (see `fixes.md`).

## Tests added or updated

Count: 3 added in this pass (2 ViewModel, 1 in-app route)

MailSearchViewModelEdgeCasesTest:
- `the version changes with each answer, not when a search starts, fails, or a result is read or removed`: version 0 when opened and while the first search loads; a new value after each successful answer, also for the same query searched again and for the answer of a retried server search; unchanged when a search or a server search starts, on Offline and on Failed(500) (cached results kept), and on markSeen/removed.
- `a late answer of an older search leaves the version, and a search opened again gets a new one`: a superseded search answering late changes neither results nor version; after close and reopen the version is 0, and the first answer gets a version different from the one before closing (so `distinctUntilChanged` in MailRoute does not swallow it).

MailSearchRouteInAppTest:
- `a failed search on the server keeps the place in the results, the answer to retry starts at the top`: 30 cached results, scrolled to row 125; "Search on server" answers Offline: the error with Retry shows, row 125 stays, row 100 is not composed (no scroll on a failure). Retry answers with a newer mail 99 first: row 99 is shown (scroll to the top on the answer, the regression the fixer found for keyed lists), Retry disappears, two server calls.

Already covered before this pass and not duplicated: a new submitted search starts at the top and back from a result keeps the place (`back from a result returns to the same place in the results, a new search starts at the top`); the server answer showing its new first row (`search on server asks the servers and names the account that failed, the close button returns`).

## Requirement coverage

Nothing was executed by the test-writer (Gradle denied for sub-agents). The parent reported running `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*'` on `af2f5113a` with all tests passing (396 or more, including all MailSearch test classes); that run predates the 3 tests added here, so the "Result" column below says what still has to run.

Command for the parent: `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.list.MailSearch*'`, then `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: search action; submit searches the folder or all folders from the unified inbox | MailSearchScreenTest, MailSearchViewModelTest, MailSearchRepositoryTest scope tests; MailSearchViewModelEdgeCasesTest reopen tests; route test `a result opens the read view...` | parent mail.* run | Pass in the parent's run (not run by the test-writer) |
| AC1: results in the usual rows; tapping opens the read view; back keeps the place | MailSearchScreenTest rows; `search results have no swipe actions`; route tests `a result opens the read view...`, `back from a result returns to the same place...` | parent mail.* run | Pass in the parent's run (not run by the test-writer) |
| AC1: closing search returns to the folder list (X and Back) | `the system back closes an open search`, route tests (Back and X), MailSearchViewModelTest close test | parent mail.* run | Pass in the parent's run (not run by the test-writer) |
| AC2: Search on server with server=true and the box text; failures named; hasMore hint | MailSearchRepositoryTest; MailSearchViewModelTest; MailSearchViewModelEdgeCasesTest server tests; MailSearchScreenTest failures/hasMore and edited-text test; route test `search on server asks the servers...` | parent mail.* run | Pass in the parent's run (not run by the test-writer) |
| AC2/AC1: the server answer shows its newest rows (list starts at the top on each answer, keeps its place otherwise) | new: `the version changes with each answer...`, `a late answer of an older search leaves the version...`, route `a failed search on the server keeps the place...`; existing route tests above | parent focused command above | Not run: added after the parent's run, Gradle denied for the test-writer |
| AC3: loading, empty and error states like the list | MailSearchScreenTest empty/error/loading; ViewModel failure/retry tests; new route test (error with Retry during a server search) | parent mail.* run; new test: focused command | Pass in the parent's run for the existing tests; new test not run |
| AC4: EN and DE strings | MailSearchScreenTest `the search speaks German`; `lintDebug` MissingTranslation | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` | Not run by the test-writer; parent to confirm lintDebug |
| AC5: unit and Compose tests; ktlintCheck testDebugUnitTest lintDebug pass | all of the above | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` | Not run by the test-writer; parent to run after these additions |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.list.MailSearch*'`

Result:

Not run by the test-writer (Gradle denied). The parent's earlier run of `--tests 'ch.sonensei.cockpit.mail.*'` on `af2f5113a` passed, without the 3 tests added in this pass. The parent must run the command above.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Not run by the test-writer. The parent runs it.

## .taskcheck

Result:

Not run by the test-writer (`sh scripts/check.sh` runs Gradle). The parent runs it before `done`.

## Bugs found

None found by reading the final code against the criteria and the fixes. Checked by inspection:

- `version` is only set in the `MailResult.Ok` branch of `runSearch`, after the generation check, so failures, starts, late answers, markSeen and removed leave it alone; the new tests pin this.
- After close and reopen the version goes null then 0, then a generation larger than any earlier one, so `distinctUntilChanged().drop(1)` in MailRoute never drops the first answer of a reopened search. A search whose first answer fails stays at version 0 and does not scroll, which is correct (nothing new to show).
- The collector lives in `LaunchedEffect(viewModel)` in MailRoute and survives opening a result (MailRoute stays composed, only its content switches), so returning from the read view does not re-emit and keeps the place; `drop(1)` skips the current value after a configuration change.

If a new test fails in the parent run it must be analysed (test mistake or production defect) before the task is completed; it must not be weakened or skipped.

## Coverage

Status: unavailable to the test-writer. Gradle (and so `./gradlew createDebugUnitTestCoverageReport`) is denied for sub-agents here. Coverage was not measured by the test-writer; any coverage numbers come from the parent's run, not from this report.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-mail-search; base `4f351d00b`; final code `af2f5113a` (commits `7cc2dddb0`, `af2f5113a`); scope given by the parent and the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `af2f5113a` plus the two edited test files of this pass (no hashes: git not available to the test-writer).
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailApi.kt`, `MailListViewModel.kt`, `MailModels.kt`, `MailRepository.kt`, `MailScreen.kt`, `MailSearchContent.kt` (all in that package). Excluded: `android/app/src/main/res/values/strings_mail.xml` and `values-de/strings_mail.xml` (resources, no executable code; checked by the German screen test and lintDebug). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP, `cd android && ./gradlew createDebugUnitTestCoverageReport` (docs/ARCHITECTURE.md "Coverage"). Not run by the test-writer.
- Run start/completion time and exit status: no run by the test-writer.
- Generated report paths and report hashes/run identifier: none (expected path `android/app/build/reports/coverage/test/debug/report.xml` if the parent runs it).
- Evidence outputs are fresh and inputs stayed unchanged: not applicable, no run.
- Final run after latest test additions and production fixes: no; coverage was not measured by the test-writer.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `mail/list/MailApi.kt` | not measured | not measured | not measured | not measured |
| `mail/list/MailListViewModel.kt` | not measured | not measured | not measured | not measured |
| `mail/list/MailModels.kt` | not measured | not measured | not measured | not measured |
| `mail/list/MailRepository.kt` | not measured | not measured | not measured | not measured |
| `mail/list/MailScreen.kt` | not measured | not measured | not measured | not measured |
| `mail/list/MailSearchContent.kt` | not measured | not measured | not measured | not measured |

No aggregate is reported.

Known behavior by inspection (not a measured result) that stays without a dedicated test:

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailSearchContent.kt` SearchBox placeholder fallback (`Mailbox.Folder` whose folder is no longer `state.folder`) | Placeholder reads "Search mail" when the scoped folder is not the selected one | Not reachable from the UI (the picker is hidden while searching); left untested |
| `MailSearchContent.kt` LaunchedEffect focus request | Keyboard opens for a new, empty box | Focus/IME is not observable reliably in Robolectric; left untested |
| `MailRepository.search` network exception path (`mailCall` offline) | Offline when the server cannot be reached | Covered by the shared `mailCall` tests of the list (MailRepositoryTest); 5xx path covered by MailSearchRepositoryTest |
| `MailScreen.kt` MailRoute scroll collector after a configuration change | The place in the results is kept on rotation (`drop(1)`) | Rotation with a hoisted `rememberLazyListState` is not exercised by the route tests; low risk, left untested |

## Unable to test

- Running any test, ktlint, lint or coverage: Gradle is denied for this sub-agent. Follow-up: the parent runs the commands above and records the results with `tasks.py verify`.
- Real device behavior (keyboard IME search key on a phone): not testable here; the IME action is exercised through Compose `performImeAction`.

## Summary

Tests added and no production defects found by inspection; validation of the 3 tests added in this pass is pending: the parent worker must run `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.list.MailSearch*'` and the full `ktlintCheck testDebugUnitTest lintDebug`. Coverage was not measured by the test-writer.
