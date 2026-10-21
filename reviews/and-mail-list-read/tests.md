# Test Report

Date: 2026-10-21

Task: and-mail-list-read - Android mail list and reading

Scope: task commits `dc3200563744d28fa732c050b24ca4637748fa5c..58f705653f136cb1837c0fbe9aa36ea2cbb26119`
(9 commits: e0fca56f, 9e314026, b4461438, 7c007355, 0e9bf534, ae0c8518, 636fae7a, 8d16eb56, 58f70565),
branch `main`, measured HEAD `58f705653f136cb1837c0fbe9aa36ea2cbb26119`. Production scope:
`android/app/src/main/java/ch/sonensei/cockpit/mail/{list,read}` (+ `res/values/strings_mail.xml`, resources only).
This is the final pass; it supersedes the earlier report written for revision 93418b24.

Requirements:
- `tasks/and-mail-list-read.md` acceptance criteria and recorded assumptions
- `SPEC.md` §9.2 item 5 (via the task goal)
- `docs/ARCHITECTURE.md` "Coverage"
- `AGENTS.md` project notes

## Test files changed

- None in this pass. The existing task tests (13 classes under
  `android/app/src/test/java/ch/sonensei/cockpit/mail/{list,read}`) already cover the acceptance criteria and the
  fixes of 8d16eb56; the coverage review found no meaningful untested behavior.

## Tests added or updated

Count: 0 (118 existing task tests reviewed and run)

## Requirement coverage

Focused command (F): `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*'`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Unified inbox | `MailListViewModelTest` "the unified inbox loads first, further pages are appended"; `MailListScreenStatesTest` "the unified inbox of several accounts names each row's account"; `MailRepositoryTest` "the unified inbox asks for all accounts ..." | F | Pass |
| AC1 Folders | `MailListScreenTest` "the mailbox picker offers the unified inbox and each account's folders", "a selected folder is named by its role"; `MailListViewModelTest` "selecting a folder loads it from its newest mail"; `MailModelsTest` folder ordering/role/depth tests; `MailRepositoryTest` "accounts are sorted by name and list only selectable folders ..." | F | Pass |
| AC1 Swipe actions (archive/delete) | `MailListScreenTest` "swiping a row to the right archives it, to the left deletes it", "in the Archive folder a row cannot be archived again, only deleted", "the swipe actions are also accessibility actions", "a failed swipe action is reported with a retry"; `MailListViewModelTest` "a swiped mail leaves the list at once ...", "a refused or offline swipe puts the mail back ..."; `MailListViewModelEdgeCasesTest` concurrent-swipe cases | F | Pass |
| AC1 Read view with sanitized HTML in a WebView, JS disabled, no file access | `MailWebViewTest` "the settings lock the untrusted html down" (javaScriptEnabled=false, allowFileAccess=false, allowContentAccess=false, blockNetworkLoads), "the page around the mail allows only inline styles and data images", link-handling tests; `MailBodyViewTest`; `MailReadScreenTest` "the headers are shown above the html body", "a text-only mail is shown as text, without the web view"; `MailReadRepositoryTest` "a message is read with its headers, sanitized body ..." | F | Pass |
| AC1 To-task / waiting | `MailReadScreenTest` "a created task is announced ...", "a created waiting item is announced ...", "a failed task is reported", "a failed waiting item is reported"; `MailReadViewModelTest` "a created task or waiting item is reported with its title", "a failed GTD action is reported ..."; `MailReadRepositoryTest` "to-task and to-waiting post to their endpoints ..." | F | Pass |
| AC1 Opening marks read (assumption), only while the mail is open (fix 8d16eb56) | `MailReadViewModelTest` "opening an unread mail shows it and marks its current copy read", "a mail closed or replaced before it loaded is not marked read"; `MailRouteInAppTest` "a mail opened from the list is shown, marked read ..." | F | Pass |
| Failed page is not auto-retried (fix 8d16eb56) | `MailListPagingRetryTest` (2 tests); `MailListScreenStatesTest` "a failed page is not asked for again until a retry succeeds", "a failed refresh near the end does not ask for the next page" | F | Pass |
| AC2 `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.list.*'` passes, Compose/Robolectric screen tests covering swipe actions; `mail.read` WebView settings | All `mail.list` classes (72 tests) inside F; WebView settings in `mail.read.MailWebViewTest` | F (superset of the named command, includes `mail.list.*`) | Pass |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*'`

Result:

Pass - BUILD SUCCESSFUL; 118 tests in 13 classes, 0 failures, 0 errors, 0 skipped (JUnit XML timestamps
2026-10-21T17:11Z). ktlintCheck clean.

## Full test execution

Command:

`cd android && ./gradlew testDebugUnitTest --rerun createDebugUnitTestCoverageReport` (full Android unit suite with coverage)

Result:

Pass - 133 suites, 1349 tests, 0 failures (run twice, both BUILD SUCCESSFUL). Note: an earlier, non-forced
`./gradlew createDebugUnitTestCoverageReport` invocation ended "BUILD FAILED in 2m"; the output was truncated before the
cause was captured and its results were overwritten by the next run. An immediate rerun and two forced reruns
(`--rerun` of `testDebugUnitTest`) all passed, and the mail suite passed in every run. The cause is unknown; it may be a
transient or flaky failure anywhere in the Android suite, and nothing ties it to this task. No coverage numbers from
that failed run are used.

## .taskcheck

Result:

Pass - `sh scripts/check.sh` -> "== all checks passed" (server, web, android). Web build prints a pre-existing bundle
budget warning, not related to this task.

## Bugs found

None.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. All measured files are new in this task (added in the task range), so
whole-file coverage equals coverage of task-added code, except `MailScreen.kt`, which replaced a placeholder (1 line
removed).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-mail-list-read`; base
  `dc3200563744d28fa732c050b24ca4637748fa5c` (supplied by the parent as merge-base with origin/main); HEAD
  `58f705653f136cb1837c0fbe9aa36ea2cbb26119`; commits from `git log dc320056..HEAD` (9, all task commits), cross-checked
  against the task `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 58f70565, clean worktree for `android/`. The
  only uncommitted file is `reviews/and-mail-list-read/review.md`, written by the concurrent code-reviewer. It is not a
  test input.
- Exact included files: `mail/list/{MailApi,MailListViewModel,MailModels,MailRepository,MailScreen}.kt`,
  `mail/read/{MailBodyView,MailReadRepository,MailReadScreen,MailReadViewModel}.kt`. Excluded:
  `res/values/strings_mail.xml` (string resources, not executable). There are no renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`;
  `./gradlew testDebugUnitTest --rerun createDebugUnitTestCoverageReport` in `android/`; full debug unit test suite.
- Run start/completion time and exit status: final run completed 2026-10-21T17:21Z (report mtime
  17:21:06Z), BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`,
  sha256 `90257f2da700ed1c371ca927d80f7bb836c8c908e90d9f84068766e983523d02`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. `--rerun` forced test execution, the report was rewritten
  by this run, and HEAD/worktree were unchanged before and after.
- Final run after latest test additions and production fixes: yes (covers 8d16eb56 and 58f70565; no tests were added in this pass).

Values below are JaCoCo per-sourcefile counters as covered/total (percentage). The "Statements" column uses JaCoCo
INSTRUCTION counters (bytecode instructions), and "Functions" uses METHOD counters.

| Changed file | Statements (instructions) | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `mail/list/MailApi.kt` | 538/886 (60.7%) | N/A (0/0) | 64/91 (70.3%) | 70/98 (71.4%) |
| `mail/list/MailListViewModel.kt` | 1058/1059 (99.9%) | 75/78 (96.2%) | 28/28 (100.0%) | 111/111 (100.0%) |
| `mail/list/MailModels.kt` | 599/604 (99.2%) | 59/60 (98.3%) | 44/45 (97.8%) | 68/68 (100.0%) |
| `mail/list/MailRepository.kt` | 379/382 (99.2%) | 26/26 (100.0%) | 15/15 (100.0%) | 54/56 (96.4%) |
| `mail/list/MailScreen.kt` | 3283/3392 (96.8%) | 144/189 (76.2%) | 59/70 (84.3%) | 280/285 (98.2%) |
| `mail/read/MailBodyView.kt` | 257/260 (98.8%) | 13/14 (92.9%) | 11/12 (91.7%) | 42/43 (97.7%) |
| `mail/read/MailReadRepository.kt` | 449/455 (98.7%) | 38/38 (100.0%) | 23/25 (92.0%) | 46/46 (100.0%) |
| `mail/read/MailReadScreen.kt` | 2151/2259 (95.2%) | 115/136 (84.6%) | 32/39 (82.1%) | 160/165 (97.0%) |
| `mail/read/MailReadViewModel.kt` | 644/644 (100.0%) | 36/36 (100.0%) | 37/37 (100.0%) | 72/72 (100.0%) |
| **Aggregate (summed counters, 9 files)** | 9358/9941 (94.1%) | 506/577 (87.7%) | 313/362 (86.5%) | 903/944 (95.7%) |

All 9 executable files in scope are measured. The only exclusion is `strings_mail.xml` (resources), and no files are
missing from the report.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `mail/list/MailApi.kt:63,66-176` | Hilt `MailApiModule.mailApi` provider and kotlinx-serialization generated members of the DTOs (copy/equals/hashCode/serializer write paths) | Non-material. DTO parsing is exercised through `MailRepositoryTest`/`MailReadRepositoryTest` with a MockWebServer. The uncovered parts are compiler-generated glue and the DI provider, which is replaced in tests. |
| `mail/list/MailScreen.kt:84-138` (partial) | `MailScreen` Hilt/placeholder switch, `MailRoute` default `hiltViewModel()` argument, `MailListCallbacks` default lambdas | Non-material. The route behavior is covered by `MailRouteInAppTest` with an explicit view model. The missed branches are Kotlin default-argument synthetics and unused default lambdas. |
| `mail/list/MailScreen.kt:211,215,246-297` (partial branches) | Load-more trigger, swipe dismiss `when` / `SwipeBackground` `Settled` path | Covered in behavior by `MailListScreenTest` swipe tests and the paging tests in `MailListScreenStatesTest`/`MailListPagingRetryTest`. The remaining branches are exhaustive-`when` synthetics and the `Settled` no-op. |
| `mail/read/MailReadScreen.kt:56-98,183-199` (partial) | `MailReadRoute` default args/`hiltViewModel()`, `MailReadCallbacks` defaults, `Notice` `when` exhaustiveness | Non-material. Every notice variant (task/waiting created, task/waiting failed, archive/delete failed) has a `MailReadScreenTest` test. The missed branches are compiler-generated. |
| `mail/read/MailBodyView.kt:58,93,108` | Companion init, the `view.tag != document` re-load guard, `ActivityNotFoundException` fallback | Low risk. The fallback is a silent no-op when no app handles a link, and the guard only prevents reloading on recomposition. No test added. |
| `mail/list/MailRepository.kt:87-88`, `mail/read/MailReadRepository.kt:18,20` | `CancellationException` rethrow; data-class generated members | Non-material (coroutine cancellation plumbing and generated code). |
| `mail/list/MailListViewModel.kt:33,191`, `mail/list/MailModels.kt:33,92` | One short-circuit branch each in folder lookup / refresh merge | Non-material. The refresh merge paths are tested extensively in `MailListViewModelEdgeCasesTest`. |

## Unable to test

- On-device WebView rendering of sanitized HTML: the tests run on Robolectric's shadow WebView, so they verify the
  settings, CSP wrapper and link interception, but not real Chromium rendering or enforcement. This is acceptable for
  AC2, which asks for unit tests of the WebView settings. An instrumented/on-device check would be a follow-up outside
  this task's criteria.
- Real server integration: the API is exercised with MockWebServer only. That is the intended level for this task.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.
