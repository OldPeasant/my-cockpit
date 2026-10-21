# Test Report

Date: 2026-10-21

Task: and-mail-list-read - Android mail list and reading

Scope: `git diff c09b5f6e04edd8778a4aa1f4ab40d109fd6dc82f..93418b24492065a3303e8b4ae803f52b31425864 -- android/app/src/{main,test}/java/ch/sonensei/cockpit/mail/{list,read}` on branch `main`. Task commits e4fab28b, bd14278e, f01eb4b9, 7b14fdfd (code) plus a087e815 and 93418b24 (reports only). The base and the commit list came from the parent worker and match `git log c09b5f6e..HEAD`. Uncommitted inputs are the 4 test files listed below. Production code under `src/main` is unchanged against HEAD.

Requirements:
- tasks/and-mail-list-read.md: acceptance criteria and the recorded assumptions (swipe right = archive, left = delete, no archive swipe in Archive, refused swipe re-inserted, opening marks read, WebView blocks network loads)
- SPEC.md §3 "HTML email is untrusted", §9.2 item 5
- docs/ARCHITECTURE.md "Coverage", AGENTS.md project notes
- reviews/and-mail-list-read/fixes.md: the fixed behaviour for CR-1, CR-2, CR-5 and CR-6, checked as the parent asked

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailListViewModelEdgeCasesTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailListScreenStatesTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailWebViewTest.kt`

## Tests added or updated

Count: 10 added. No existing test was changed.

- MailListViewModelEdgeCasesTest (5):
  - `a refused swipe does not duplicate a mail that a reload already brought back`: the CR-1 "already in the list" branch. Before this, only the success case of that race was tested.
  - `no next page is asked for while the mailbox reloads, even with a cursor`: the existing test `no next page is asked for while the mailbox loads or another page loads` calls `loadMore()` during a refresh only after the cursor is already `null`, so it never reached the `loading` guard. The new test does.
  - `a refresh that finds the mailbox empty clears the list`: the CR-5 `mergeFirstPage` case of an empty first page.
  - `picking the shown mailbox again after its load failed loads it`: `select()` with the same mailbox while `loaded == false`.
  - `a refresh drops a loaded mail that moved up into the new first page, keeping one copy`: CR-5 merge without duplicates, keeping the cursor of the later pages.
- MailListScreenStatesTest (2):
  - `a mail without a subject says so`
  - `drafts, sent, spam and archive folders are named by role whatever the server calls them`
- MailReadViewModelTest (2), CR-2 session handling:
  - `retry after the view closed loads nothing`
  - `a GTD answer for a closed mail is not shown on the next one`: also checks that the next mail's actions are not left busy.
- MailWebViewTest (1), CR-6:
  - `a tapped link with an upper-case scheme still leaves the view, one without a scheme is dropped`

The existing tests already cover the CR-1 re-insert rule (another mailbox shown meanwhile; success after an overtaking reload), the CR-5 merge, replace and end cases, the CR-2 session counter (late load or archive after close, reopen reloads, another mail replaces the shown one, plus the in-app route test), and the CR-6 gesture and main-frame checks. I checked each of them against the production code.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Unified inbox, paging | MailListViewModelTest `the unified inbox loads first, further pages are appended`; MailListViewModelEdgeCasesTest (load, failure, paging guard, CR-5 merge tests incl. 3 new); MailListScreenStatesTest paging tests; MailRepositoryTest unified/folder paging | (A) | Pass |
| Folders (picker, role names, selection) | MailListScreenTest `the mailbox picker offers ...`, `a selected folder is named by its role`; MailListScreenStatesTest `special folders are named by role ...`, new `drafts, sent, spam and archive ...`; MailListViewModelTest `selecting a folder ...`; new `picking the shown mailbox again ...`; MailModelsTest folderOrder/depth | (A) | Pass |
| Swipe actions archive/delete (Compose UI) | MailListScreenTest `swiping a row to the right archives it, to the left deletes it`, `in the Archive folder a row cannot be archived again, only deleted`, `the swipe actions are also accessibility actions` | (A) | Pass |
| Swipe outcome: optimistic removal, refused re-insert (CR-1) | MailListViewModelTest swipe tests; MailListViewModelEdgeCasesTest refused/race tests incl. new `a refused swipe does not duplicate ...` | (A) | Pass |
| Read view with sanitized HTML in a WebView, JS disabled, no file access (`mail.read` WebView settings) | MailWebViewTest `the settings lock the untrusted html down`, link tests incl. CR-6 and new upper-case scheme test, CSP document test; MailBodyViewTest | (A) | Pass |
| Read view load, mark read, errors, retry, close/reopen (CR-2) | MailReadViewModelTest (16 incl. 2 new); MailReadScreenTest; MailRouteInAppTest (Hilt + Robolectric in MainActivity) | (A) | Pass |
| To-task / waiting | MailReadViewModelTest `a created task or waiting item ...`, `a failed GTD action ...`, new `a GTD answer for a closed mail ...`; MailReadScreenTest toolbar and notice tests; MailReadRepositoryTest | (A) | Pass |
| AC2: `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.list.*'` passes | all mail.list classes (7 classes, 67 tests) | (A), which includes the mail.list classes | Pass |

(A) = `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*'`

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*'`

Result:

Pass. BUILD SUCCESSFUL. 12 classes, 112 tests, 0 failures, 0 skipped. Per class: MailListViewModelEdgeCasesTest 20, MailListScreenStatesTest 12, MailListScreenTest 8, MailListViewModelTest 6, MailModelsTest 9, MailRepositoryTest 8, MailRouteInAppTest 4, MailReadViewModelTest 16, MailReadScreenTest 17, MailReadRepositoryTest 5, MailWebViewTest 5, MailBodyViewTest 2. ktlintCheck passed after `./gradlew ktlintFormat`.

## Full test execution

Command:

`cd android && ./gradlew createDebugUnitTestCoverageReport` (this runs the full Android `testDebugUnitTest`)

Result:

Pass. All Android unit tests: 1339 tests, 0 failures, 0 errors, 0 skipped.

## .taskcheck

Result:

Pass. `sh scripts/check.sh` (server, web and android lint plus unit tests) printed "== all checks passed".

## Bugs found

None. Every new test passed against the current production code.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: 902/943 executable lines (95.7%). Method: added lines from `git diff -U0 c09b5f6e..93418b24` for the two packages, intersected with the JaCoCo per-line entries of the same report (a line counts as covered when `ci > 0`). All executable lines of these files are task-added. The files are new, and MailScreen.kt was a placeholder before, so the changed-line and whole-file line numbers match. Branch counters on changed lines: 448/491. That count only sums JaCoCo branch counters on those lines; it is not a separate changed-branch analysis.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-mail-list-read. Base c09b5f6e04edd8778a4aa1f4ab40d109fd6dc82f. HEAD 93418b24492065a3303e8b4ae803f52b31425864. Commits e4fab28b, bd14278e, f01eb4b9 and 7b14fdfd, as given by the parent and matching `git log c09b5f6e..HEAD`. The file scope comes from `git diff --name-only c09b5f6e..HEAD -- android/app/src/main`, restricted to the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 93418b24 plus the 4 uncommitted test files above. sha256 over the sha256sum lines of `android/app/src/{test,main}/java/ch/sonensei/cockpit/mail/*/*.kt` = `9c46fe9c2946fe9fd75c12391088c26d03409248b7dcce0ca8bd7338632428f5`. The hash was the same before and after the run. `git diff HEAD -- android/app/src/main` is empty.
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/mail/list/{MailApi,MailListViewModel,MailModels,MailRepository,MailScreen}.kt` and `android/app/src/main/java/ch/sonensei/cockpit/mail/read/{MailBodyView,MailReadRepository,MailReadScreen,MailReadViewModel}.kt`. All 9 have report data. Excluded: `android/app/src/main/res/values/strings_mail.xml` (resources, no executable code). No files were renamed or deleted.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`. Command `./gradlew createDebugUnitTestCoverageReport` in `android/`. Test selection: the full debug unit test suite.
- Run start/completion time and exit status: 2026-10-21T18:44:31+02:00 to 18:46:45+02:00. BUILD SUCCESSFUL. `testDebugUnitTest` re-executed (not UP-TO-DATE) because the test inputs had changed.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, mtime 2026-10-21 18:46:44 +0200, sha256 `4518025d0bd2d2454e0f69af5f682f26fa151831feb2949ed5268ac668f69140`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report was rewritten by this run, and the snapshot hash was the same before and after it.
- Final run after latest test additions and production fixes: yes. It covers fix commit 7b14fdfd and all 10 new tests.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. "Statements" is JaCoCo INSTRUCTION.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `mail/list/MailApi.kt` | 538/886 (60.7%) | N/A (0/0) | 64/91 (70.3%) | 70/98 (71.4%) |
| `mail/list/MailListViewModel.kt` | 1058/1059 (99.9%) | 75/78 (96.2%) | 28/28 (100.0%) | 111/111 (100.0%) |
| `mail/list/MailModels.kt` | 599/604 (99.2%) | 59/60 (98.3%) | 44/45 (97.8%) | 68/68 (100.0%) |
| `mail/list/MailRepository.kt` | 379/382 (99.2%) | 26/26 (100.0%) | 15/15 (100.0%) | 54/56 (96.4%) |
| `mail/list/MailScreen.kt` | 3146/3245 (96.9%) | 108/135 (80.0%) | 59/70 (84.3%) | 279/284 (98.2%) |
| `mail/read/MailBodyView.kt` | 257/260 (98.8%) | 13/14 (92.9%) | 11/12 (91.7%) | 42/43 (97.7%) |
| `mail/read/MailReadRepository.kt` | 449/455 (98.7%) | 38/38 (100.0%) | 23/25 (92.0%) | 46/46 (100.0%) |
| `mail/read/MailReadScreen.kt` | 2065/2173 (95.0%) | 95/106 (89.6%) | 32/39 (82.1%) | 160/165 (97.0%) |
| `mail/read/MailReadViewModel.kt` | 638/638 (100.0%) | 34/34 (100.0%) | 37/37 (100.0%) | 72/72 (100.0%) |
| **Aggregate (summed counters, 9 of 9 files; strings_mail.xml excluded, none unmeasured)** | 9129/9702 (94.1%) | 448/491 (91.2%) | 313/362 (86.5%) | 902/943 (95.7%) |

Baseline before the new tests (same command, 18:41:53 to 18:42:15, tests UP-TO-DATE from the parent's run at 7b14fdfd): aggregate 9073/9702 instructions, 438/491 branches, 899/943 lines. MailListViewModel branches went from 71/78 to 75/78, MailReadViewModel from 33/34 to 34/34, MailBodyView from 12/14 to 13/14. MailScreen lines went from 276/284 to 279/284.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailListViewModel.kt:120` (refused swipe, mail already back in the list) | CR-1: no duplicate row | Added `a refused swipe does not duplicate ...` (now covered) |
| `MailListViewModel.kt:79` (`loading` guard with a cursor) | No page request while the mailbox reloads | Added `no next page is asked for while the mailbox reloads, even with a cursor` (now covered) |
| `MailListViewModel.kt:70, 190` | Re-select after failure; empty first page | Added 2 tests (now covered) |
| `MailListViewModel.kt:191` (1 of the short-circuit branches) | One `mergeFirstPage` guard combination | Left uncovered: the merge, replace, end, empty and other-mailbox outcomes are all tested; the remaining branch is one more combination of the same guard |
| `MailListViewModel.kt:33` (`folder` getter finds no matching folder) | Folder mailbox whose account list did not load (canArchive then defaults to true) | Left uncovered: there is no requirement for this state; low risk, because the server returns a harmless result for archive in Archive |
| `MailReadViewModel.kt:128` (`retry` with no mail open) | CR-2: no load after close | Added `retry after the view closed loads nothing` (now covered) |
| `MailBodyView.kt:53` (scheme null / upper case) | CR-6 link handling | Added the upper-case/no-scheme test (now covered) |
| `MailBodyView.kt:93` (tag equal, no reload on recomposition), `:108` (no app for the link) | Scroll/zoom kept; no crash without a handler | Left uncovered: Robolectric does not render the WebView meaningfully, and the ActivityNotFound path needs an activity resolver setup. Low risk, defensive |
| `MailBodyView.kt:58`, `MailModels.kt:33`, `MailReadRepository.kt:18,20`, `MailScreen.kt:292-295` | Generated data-class/companion members | Not material |
| `MailApi.kt:63-176` | Hilt `@Provides` and kotlinx-serialization generated members of the DTOs (defaults, write$Self, copy) | Not material. DTO parsing is covered by MailRepositoryTest/MailReadRepositoryTest against MockWebServer |
| `MailRepository.kt:87-88` (`CancellationException` rethrow) | A cancelled load must not show as offline | Left uncovered: a defensive rethrow. `CancellationException` is not caught by the other clauses anyway (it is an `IllegalStateException`, not an IOException or IllegalArgumentException) |
| `MailScreen.kt:84-97, 134-138, 170-176`; `MailReadScreen.kt:56-61, 93-98, 122-128` | Default parameter values (`hiltViewModel()`, default callbacks, default clock/zone/body) | Not material. The real defaults run in MailRouteInAppTest; JaCoCo counts the synthetic `$default` branches separately |
| `MailScreen.kt:244-253, 274, 285` (`Settled` branches, accessibility list without archive) | Swipe settle without dismiss; no archive action in Archive | Partly covered by the Archive swipe test. `Settled` is not reachable from `onDismiss` in practice. Not material |
| `MailScreen.kt:402` (dropdown dismissed without choosing) | Menu closes | Not material |
| `MailReadScreen.kt:183-199` | Exhaustive `when` fallbacks the compiler generated | Not material. Every notice text is asserted in MailReadScreenTest |

## Unable to test

- On-device WebView behaviour (CSP enforcement, `blockNetworkLoads` actually blocking a remote image, JavaScript actually not running). Robolectric's WebView is a shadow, so the tests check the settings and the generated document, not real rendering. A follow-up would be an instrumented or Playwright-like device test; no criterion asks for it.
- A real swipe gesture on a device. The Compose swipe is tested with Robolectric `performTouchInput { swipeLeft()/swipeRight() }`, which the criterion names ("Robolectric/Compose UI tests for screens").

## Summary

Tests added and all validation passed. I found no production defects. Task-scoped coverage is 95.7% of lines and 91.2% of branches. The remaining gaps are default parameters, generated code, or defensive paths, as justified above.

Note for the parent worker: while I worked, `reviews/and-mail-list-read/review.md` appeared as modified in the working tree (a re-review). I did not write it, and I left it untouched.
