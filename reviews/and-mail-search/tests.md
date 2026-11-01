# Test Report

Date: 2026-11-01

Task: and-mail-search - Android mail: search box with 'search on server' option

Scope: task base `4f351d00be15fdadff304529edba0fb8d15db4fc`, implementation commit `99caa631050c1da20c799cf072f05b795ab28035` (`git diff 4f351d00b 99caa6310`). Measured working tree HEAD `7cc2dddb0994ed346bd1086a2770dcfa437b77d7` (the same commit after a rebase; `git diff --stat 99caa6310 7cc2dddb0 -- android` is empty). Task ownership: the commit list and base were given by the parent worker and match the task's `touches`. Uncommitted inputs: only the three new test files listed below (untracked) and this report.

Requirements:
- `tasks/and-mail-search.md` acceptance criteria and its three recorded assumptions (rows without swipe actions in results; hasMore hint also for `server.truncated`; one page of up to 50 results, box replaces the mailbox picker, Back or X closes, scope fixed when the search opens)
- `AGENTS.md` project notes, `docs/ARCHITECTURE.md` "Coverage"
- Existing test conventions in `android/app/src/test/java/ch/sonensei/cockpit/mail/list/` (MailListScreenTest, MailRouteInAppTest, ProjectsScreenTest back-dispatcher pattern)

## Test files changed

New files (no existing test was edited):

- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailSearchViewModelEdgeCasesTest.kt` (git blob `93ceca388156b95c22ae0cd484866a9e73522256`)
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailSearchScreenEdgeCasesTest.kt` (git blob `e14405f4f14a34cd39eb4b691a273b51b3b1feab`)
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailSearchRouteInAppTest.kt` (git blob `bda90489b7b0d891ae6beeb53611c8254d6abaaf`)

Existing task tests reviewed and kept unchanged: `MailSearchRepositoryTest` (4), `MailSearchViewModelTest` (8), `MailSearchScreenTest` (9).

## Tests added or updated

Count: 20 added (9 ViewModel, 9 screen, 2 in-app route)

MailSearchViewModelEdgeCasesTest:
- `opening the search again keeps the open search as it is`
- `a search opened again after closing starts empty in the mailbox shown now`
- `a new query after a search on the server searches the cache again and offers the server again`
- `a failed first search offers no server search, and retry repeats the cached search`
- `retry and search on server do nothing before a query, retry does nothing while a search runs`
- `an older search failing after a newer one answered leaves no error`
- `a search on the server answering after a new query is not shown`
- `a mail removed in the read view leaves both the results and the mailbox`
- `read and removed mails without an open search change only the mailbox`

MailSearchScreenEdgeCasesTest:
- `with search closed the mailbox keeps its picker, rows and search action, and shows no search box` (regression guard for the normal list)
- `while searching the box replaces the mailbox picker`
- `the system back closes an open search`
- `search results have no swipe actions`
- `results of a folder search do not name their account`
- `the box of the unified inbox says it searches all folders`
- `a search shown again keeps its query in the box`
- `the box takes no more text than the server accepts`
- `while a search on the server runs the cached results stay and the hasMore hint waits for its answer`

MailSearchRouteInAppTest (Hilt + Robolectric, MainActivity, fake repositories; real MailRoute wiring to MailListViewModel):
- `a result opens the read view, back returns to the results, back again to the mailbox`
- `search on server asks the servers and names the account that failed, the close button returns`

## Requirement coverage

Gradle cannot be run by this sub-agent (denied in this environment). Nothing listed here was executed by the test-writer. The parent worker runs the command below and records the results; the "Result" column is therefore "Not run" for this report.

Command for the parent: `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.list.MailSearch*'` (covers the 3 existing and 3 new search test classes), then `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: search action; submit searches current folder or all folders from the unified inbox | MailSearchScreenTest `the mail list has a search action`, `submitting a query searches...`; MailSearchViewModelTest folder/unified tests; MailSearchRepositoryTest scope tests; new: `a search opened again after closing starts empty in the mailbox shown now`, `opening the search again keeps...`, route test `a result opens the read view...` | parent command above | Not run: Gradle denied for sub-agent |
| AC1: results in the usual rows; tapping opens read view | MailSearchScreenTest `results show in mail rows...`; new: `search results have no swipe actions`, `results of a folder search do not name their account`, route test `a result opens the read view, back returns to the results...` | parent command above | Not run: Gradle denied for sub-agent |
| AC1: closing search returns to the folder list (X and Back) | MailSearchViewModelTest `closing the search drops a running answer...`; new: `the system back closes an open search`, `with search closed the mailbox keeps its picker...`, both route tests (Back and X) | parent command above | Not run: Gradle denied for sub-agent |
| AC2: Search on server repeats with server=true; failures named; hasMore hint | MailSearchRepositoryTest (server=true, failures, truncated); MailSearchViewModelTest `search on server keeps the cached results...`; MailSearchScreenTest failures/hasMore; new: `a new query after a search on the server...`, `a search on the server answering after a new query is not shown`, `while a search on the server runs...`, route test `search on server asks the servers...` | parent command above | Not run: Gradle denied for sub-agent |
| AC3: loading, empty, error states like the list | MailSearchScreenTest empty/error/loading; MailSearchViewModelTest failure/retry; new: `a failed first search offers no server search, and retry repeats the cached search`, `retry and search on server do nothing before a query...`, `an older search failing after a newer one answered leaves no error` | parent command above | Not run: Gradle denied for sub-agent |
| AC4: EN and DE strings | MailSearchScreenTest `the search speaks German` plus English assertions; `lintDebug` (MissingTranslation) | parent command above | Not run: Gradle denied for sub-agent |
| AC5: unit and Compose tests; ktlintCheck testDebugUnitTest lintDebug pass | all of the above | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` | Not run: Gradle denied for sub-agent |
| Read view updates results (markSeen/removed) | MailSearchViewModelTest `a result read or removed...`; new: `a mail removed in the read view leaves both...`, `read and removed mails without an open search...`, route test (back to results after reading) | parent command above | Not run: Gradle denied for sub-agent |
| Query capped at MAX_QUERY (200) | MailSearchViewModelTest `a blank query does nothing, a long one is cut...`; new: `the box takes no more text than the server accepts` | parent command above | Not run: Gradle denied for sub-agent |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.list.MailSearch*'`

Result:

Not run by the test-writer (Gradle is denied for sub-agents in this environment). The parent worker runs it and records the result.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Not run by the test-writer (same reason). The parent worker runs it.

## .taskcheck

Result:

Not run by the test-writer (`sh scripts/check.sh` runs Gradle). The parent worker runs it before `done`.

## Bugs found

None found by reading the production code against the criteria. The new tests were written to the documented behavior; if one of them fails in the parent run, it has to be analysed (test mistake or production defect) before the task is completed. Points the tests deliberately pin down:

- After reading a search result, Back returns to the open search (results and query), not to the mailbox (route test). This follows from the search state living in the ViewModel.
- A late answer of an older search (success or failure) is never shown (generation check).

## Coverage

Status: unavailable to the test-writer. Gradle (and so `./gradlew createDebugUnitTestCoverageReport`) is denied for sub-agents here. The parent worker runs the tests; any coverage numbers come from the parent's run, not from this report.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-mail-search; base `4f351d00be15fdadff304529edba0fb8d15db4fc`; implementation `99caa631050c1da20c799cf072f05b795ab28035`; HEAD `7cc2dddb0994ed346bd1086a2770dcfa437b77d7` (android tree identical to the implementation commit); scope given by the parent and the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `7cc2dddb0` plus the three untracked test files with the git blob hashes listed above.
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailApi.kt`, `MailListViewModel.kt`, `MailModels.kt`, `MailRepository.kt`, `MailScreen.kt`, `MailSearchContent.kt` (all in that package). Excluded: `android/app/src/main/res/values/strings_mail.xml` and `values-de/strings_mail.xml` (resources, no executable code; checked by the German screen test and lintDebug). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP, `cd android && ./gradlew createDebugUnitTestCoverageReport` (docs/ARCHITECTURE.md "Coverage"). Not run by the test-writer.
- Run start/completion time and exit status: no run.
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

## Unable to test

- Running any test, ktlint, lint or coverage: Gradle is denied for this sub-agent. Follow-up: the parent runs the commands above and records the results with `tasks.py verify`.
- Real device behavior (keyboard IME search key on a phone): not testable here; the IME action is exercised through Compose `performImeAction`.

## Summary

Meaningful task behavior is covered by the existing 21 tests plus 20 new tests, but none of them was executed by the test-writer: the parent worker must run `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.list.MailSearch*'` and the full `ktlintCheck testDebugUnitTest lintDebug`, and coverage numbers (if wanted) come from the parent's run. No production defects were found by inspection.
