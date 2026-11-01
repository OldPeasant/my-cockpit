# Test Report

Date: 2026-11-01

Task: and-mail-search - Android mail: search box with 'search on server' option

Scope: third (final) pass for the final code. Task base `4f351d00b`, final code HEAD `9535590b17461d540e8af6eb4828070b5f416d4b` (commits `7cc2dddb0` implementation, `af2f5113a` review fixes plus scroll-to-top change, `9535590b1` version carried into a new search), as given by the parent worker; task ownership from the parent and the task's `touches`. Production change since the second pass (`git diff af2f5113a 9535590b1 -- android/app/src/main`): only `MailListViewModel.kt`, where `search(query)` and `searchOnServer` with an edited query now build the new `MailSearchState` with `version = search.version` instead of resetting it to 0 while loading. No test files were changed in this pass; the working tree was clean apart from this report.

Requirements:
- `tasks/and-mail-search.md` acceptance criteria and its recorded assumptions
- `reviews/and-mail-search/fixes.md` (CR-1 scroll position, CR-2 server search uses the box text)
- The parent's scroll design: `MailSearchState.version` is set to the search generation on each successful answer; `MailRoute` collects `uiState.map { it.search?.version }.distinctUntilChanged().drop(1)` and calls `requestScrollToItem(0)`
- `AGENTS.md` project notes, `docs/ARCHITECTURE.md` "Coverage", existing test conventions in `android/app/src/test/java/ch/sonensei/cockpit/mail/list/`

## Test files changed

Over the whole task (none changed in this third pass):

- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailSearchViewModelEdgeCasesTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailSearchRouteInAppTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailSearchScreenEdgeCasesTest.kt`
- plus the fixer's additions in `MailSearchScreenTest` and the files above (see `fixes.md`)

## Tests added or updated

Count: 0 in this pass. Earlier passes: 20 tests in the first pass, 3 in the second pass (listed below); all are kept unchanged.

Second-pass tests, now run:
- MailSearchViewModelEdgeCasesTest `the version changes with each answer, not when a search starts, fails, or a result is read or removed`: failed on `af2f5113a` (expected the version of the first answer while the same query loads again, got 0, line 294) and passes on `9535590b1`. This was the defect the third commit fixed (the version dropped to 0 while a new search loaded, so MailRoute scrolled to the top before the answer arrived).
- MailSearchViewModelEdgeCasesTest `a late answer of an older search leaves the version, and a search opened again gets a new one`: re-checked against `9535590b1` and still consistent. The "new" search now starts with the version of the unanswered "old" search (0); the late "old" answer is dropped by the generation check, so neither results nor version change; `closeSearch` then `openSearch` builds a fresh `MailSearchState` with version 0, and the first answer gets a new generation, different from the one before closing. Passes.
- MailSearchRouteInAppTest `a failed search on the server keeps the place in the results, the answer to retry starts at the top`: passes.

No test was added in this pass. The `search(query)` change is pinned by the first test above. The second changed line (`searchOnServer` with an edited query keeping the version while loading) has no assertion of its own; see the coverage gap table. It was not judged clearly missing: it uses the same construction as the line that is pinned, and the route tests cover the scroll on the server answer.

## Requirement coverage

Run results come from the parent worker on `9535590b1` (Gradle is denied for this sub-agent): `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*'` -> all pass, including all 6 MailSearch test classes (48 tests). Every test added by the test-writer and the fixer ran in that run. The parent's full run on `9535590b1` (`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport`) then exited 0 with 2361 tests, 0 failed, ktlint and lint clean.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: search action; submit searches the folder or all folders from the unified inbox | MailSearchScreenTest, MailSearchViewModelTest, MailSearchRepositoryTest scope tests; MailSearchViewModelEdgeCasesTest reopen tests; route test `a result opens the read view...` | parent mail.* run on `9535590b1` | Pass |
| AC1: results in the usual rows; tapping opens the read view; back keeps the place | MailSearchScreenTest rows; `search results have no swipe actions`; route tests `a result opens the read view...`, `back from a result returns to the same place...` | parent mail.* run on `9535590b1` | Pass |
| AC1: closing search returns to the folder list (X and Back) | `the system back closes an open search`, route tests (Back and X), MailSearchViewModelTest close test | parent mail.* run on `9535590b1` | Pass |
| AC2: Search on server with server=true and the box text; failures named; hasMore hint | MailSearchRepositoryTest; MailSearchViewModelTest; MailSearchViewModelEdgeCasesTest server tests; MailSearchScreenTest failures/hasMore and edited-text test; route test `search on server asks the servers...` | parent mail.* run on `9535590b1` | Pass |
| AC2/AC1: the list starts at the top on each answer and keeps its place otherwise | `the version changes with each answer...`, `a late answer of an older search leaves the version...`, route `a failed search on the server keeps the place...`, route `back from a result returns to the same place...` | parent mail.* run on `9535590b1` | Pass (the first failed on `af2f5113a`, fixed by `9535590b1`) |
| AC3: loading, empty and error states like the list | MailSearchScreenTest empty/error/loading; ViewModel failure/retry tests; route test with error and Retry during a server search | parent mail.* run on `9535590b1` | Pass |
| AC4: EN and DE strings | MailSearchScreenTest `the search speaks German`; `lintDebug` MissingTranslation | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` (parent, `9535590b1`) | Pass (exit 0, lint clean) |
| AC5: unit and Compose tests; ktlintCheck testDebugUnitTest lintDebug pass | all of the above | same full command | Pass (exit 0, 2361 tests, 0 failed, ktlint and lint clean) |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*'` (run by the parent on `9535590b1`)

Result:

Pass (all mail tests, including the 48 tests in the 6 MailSearch test classes).

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` (run by the parent on `9535590b1`)

Result:

Pass: exit 0, 2361 tests, 0 failed, ktlint and lint clean (run by the parent on `9535590b1`, reported to the test-writer; not run by the test-writer).

## .taskcheck

Result:

Not run by the test-writer (`sh scripts/check.sh` runs Gradle). The parent runs it before `done`.

## Bugs found

### The search version dropped to 0 while a new search loaded (fixed)

Finding ID:

`TW-1`

Test:

`MailSearchViewModelEdgeCasesTest` `the version changes with each answer, not when a search starts, fails, or a result is read or removed`

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailListViewModel.kt` (`search` and `searchOnServer` with an edited query)

Requirement:

The results list starts at the top when a search answers and otherwise keeps its place (AC1 back keeps the place; AC2 the server answer shows its newest rows).

Expected behavior:

The version stays the same while a new search loads and changes only when an answer arrives.

Actual behavior:

On `af2f5113a` the version was reset to 0 when a search started (assertion failed with 0 at line 294), so MailRoute scrolled when the search started instead of when it answered.

Severity:

Low

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.` Fixed in `9535590b1`; the test passes there.

No other defects found by inspection of `9535590b1`.

## Coverage

Status: verified by the parent's run (not measured by the test-writer). Gradle is denied for this sub-agent; the numbers below are the JaCoCo line and branch counters the parent reported from its full run on `9535590b1`, copied unchanged. Statement and function counters were not reported and are given as not reported.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-mail-search; base `4f351d00b`; final code `9535590b17461d540e8af6eb4828070b5f416d4b` (commits `7cc2dddb0`, `af2f5113a`, `9535590b1`); scope from the parent and the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `9535590b1`; no uncommitted source or test changes (only this report).
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailApi.kt`, `MailListViewModel.kt`, `MailModels.kt`, `MailRepository.kt`, `MailScreen.kt`, `MailSearchContent.kt` (all in that package). Excluded: `android/app/src/main/res/values/strings_mail.xml` and `values-de/strings_mail.xml` (resources, no executable code; checked by the German screen test and lintDebug). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP (version not reported), `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` in `android/` (docs/ARCHITECTURE.md "Coverage"), all debug unit tests; run by the parent, not by the test-writer.
- Run start/completion time and exit status: exit 0, 2361 tests, 0 failed; times not reported.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` (parent's run); no hash reported.
- Evidence outputs are fresh and inputs stayed unchanged: the parent ran it on `9535590b1`, the final code, after all test additions; no source or test file changed after that (this pass changed only this report).
- Final run after latest test additions and production fixes: yes, the parent's full run on `9535590b1`.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `mail/list/MailApi.kt` | not reported | not reported | not reported | 85/119 (71.4%) |
| `mail/list/MailListViewModel.kt` | not reported | 224/237 (94.5%) | not reported | 274/274 (100%) |
| `mail/list/MailModels.kt` | not reported | 75/76 (98.7%) | not reported | 93/93 (100%) |
| `mail/list/MailRepository.kt` | not reported | 60/62 (96.8%) | not reported | 90/90 (100%) |
| `mail/list/MailScreen.kt` | not reported | 190/224 (84.8%) | not reported | 469/476 (98.5%) |
| `mail/list/MailSearchContent.kt` | not reported | 52/60 (86.7%) | not reported | 91/92 (98.9%) |

Aggregate from summed counters: lines 1102/1144 (96.3%) over all 6 files; branches 601/659 (91.2%) over 5 files, without `MailApi.kt` (its branch counters were not reported). Resource files are excluded as above. `MailApi.kt` lines not covered are generated members of DTO data classes (per the parent). `MailScreen.kt` contains the whole mail screen, so its branch gap includes code outside this task. Changed-line coverage is not measured.

Known behavior by inspection that stays without a dedicated test (the exact uncovered lines and branches are in the parent's JaCoCo report; the test-writer did not read it):

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailListViewModel.kt` `searchOnServer` edited-query branch, `version = search.version` | The version stays the same while a server search for edited text loads (no early scroll) | The branch itself runs in the edited-text tests, but no assertion pins the version while it loads; same construction as the pinned `search` line; low risk, left untested |
| `MailSearchContent.kt` SearchBox placeholder fallback (`Mailbox.Folder` whose folder is no longer `state.folder`) | Placeholder reads "Search mail" when the scoped folder is not the selected one | Not reachable from the UI (the picker is hidden while searching); left untested |
| `MailSearchContent.kt` LaunchedEffect focus request | Keyboard opens for a new, empty box | Focus/IME is not observable reliably in Robolectric; left untested |
| `MailRepository.search` network exception path (`mailCall` offline) | Offline when the server cannot be reached | Covered by the shared `mailCall` tests of the list (MailRepositoryTest); 5xx path covered by MailSearchRepositoryTest |
| `MailScreen.kt` MailRoute scroll collector after a configuration change | The place in the results is kept on rotation (`drop(1)`) | Rotation with a hoisted `rememberLazyListState` is not exercised by the route tests; low risk, left untested |

## Unable to test

- Running any test, ktlint, lint or coverage directly: Gradle is denied for this sub-agent. All results above are the parent's runs on `9535590b1`.
- Real device behavior (keyboard IME search key on a phone): not testable here; the IME action is exercised through Compose `performImeAction`.

## Summary

Tests added and all validation passed: the parent's full `ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` on `9535590b1` exits 0 (2361 tests, 0 failed, ktlint and lint clean), including every test added for this task. The only defect found (TW-1, version reset while loading) is fixed in `9535590b1`. Task-scoped coverage (parent's JaCoCo run): lines 1102/1144 (96.3%) over 6 files, branches 601/659 (91.2%) over 5 files; the remaining gaps are listed above.
