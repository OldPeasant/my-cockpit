# Test Report

Date: 2026-11-02

Task: and-task-detail - Android: task detail screen with editing

Scope: `git diff 305558bcc69b44c40f9b91a010cc944ae849c286..c171fc231a797a39cce60a73059ddc1ba7f028ad`, Android part only. Task commits: a0b11de74, 8f85753ba, e6e8d0a57 (merged as 035c6684c), c5e12669f (title length error as plural), d6abae750 (test-writer tests), c171fc231 (fixer: CR-1, CR-3/TW-1, CR-4, CR-5). `tasks/and-test-tmpdir.md` in the diff is task metadata and was ignored. This report describes revision c171fc231.

Requirements:
- `tasks/and-task-detail.md` acceptance criteria and its confirmed/recorded assumptions
- `SPEC.md` section 4.2 (Problem Details, ETag/If-Match, ISO-8601) as summarised in `AGENTS.md`
- `docs/ARCHITECTURE.md` "Coverage" and code conventions
- `reviews/and-task-detail/review.md` and `reviews/and-task-detail/fixes.md` (findings the fixes target)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskDraftTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskDetailRepositoryTest.kt` (new; fixer added 2 tests)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskDetailViewModelEdgeTest.kt` (new; fixer added 2 tests, ktlint reformatting)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskDetailContentTest.kt` (new; fixer extended the Logbook test, ktlintFormat)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskDetailStringsTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/dashboard/DashboardTaskOpenTest.kt` (new; fixer: lambda arity only)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/FakeTaskDetailSource.kt` (extended: settable `actionResult`, `restoreResult`, `moveResult`; defaults unchanged)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskDetailScreenTest.kt` (fixer: `TaskOpener` lambda arity only, assertions unchanged)

No existing test was weakened, removed or disabled.

## Tests added or updated

Count: 86 new test methods (test-writer 82, fixer 4) plus 2 updated (`TaskDetailContentTest` Logbook test now also checks the completion time; `TaskDetailRepositoryTest` delete/restore test made void, now also checks restore sends no If-Match).

Review of the fixer's test changes against c171fc231:
- CR-3/TW-1 (due time with `Locale.ROOT`): the unchanged `TaskDraftTest.the due time is sent with ASCII digits whatever the device language` covers it; failed before the fix, passes now.
- CR-1 (an offline reload keeps the loaded task): `TaskDetailViewModelEdgeTest.a reload without an answer after a change keeps the loaded task, not the older list copy` fails on the old code (it would show the list copy without checklist) and checks detail, checklist, message and `cachedAt`. The existing `going offline after the task was loaded keeps the loaded task read-only` and `offline the task opens read-only from the cached copy` keep the other branch covered.
- CR-4 (completion time of a Logbook copy): covered along the chain: Logbook row passes `completedAt` (`TaskDetailContentTest`), `TaskSnapshots.put/cached` and `TaskDetailRepository.cached` keep it (`TaskDetailRepositoryTest.a snapshot from the Logbook keeps the completion time ...`), the view model shows the offline copy as completed (`TaskDetailViewModelEdgeTest.offline a task opened from the Logbook shows as completed`). The one link not exercised by a test is the shell's opener lambda storing it (`CockpitShell.kt` line 110, `TaskSnapshots.put(it, completedAt)`), a one-line pass-through; the existing shell test covers that lambda with a list row (completion null).
- CR-5 (links do not touch the shared offline flag): `TaskDetailRepositoryTest.links that fail on the server side leave the shared offline state alone` (503 on links, task loads, flag stays false) fails on the old code deterministically, because links no longer race with the flag.
- The test changes are adequate; no further tests were added in this pass.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. Rows in Next/Today/Projects/Waiting/Someday/Logbook, search and Dashboard open the detail; Back returns | `TaskDetailScreenTest` (Next, Waiting, shell route + Back), `SearchRouteInAppTest` (task opens the detail, not the browser), `TaskDetailContentTest.in the shell a Logbook row opens ...`, `DashboardTaskOpenTest` (2) | `android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport --console=plain` (run by the parent) | Pass |
| 2. Shows title, Markdown notes, checklist, project, contexts, due, start/tickler, estimate, energy, creativity, waiting-for, links (tap opens) | `TaskDetailContentTest` (fields/links, Waiting, Someday), `TaskDetailRepositoryTest.loads the task with its checklist ...` | same | Pass |
| 3. Editing: PATCH with If-Match, 412 reloads and tells, Problem Details at the field or as a message | `TaskDraftTest`, `TaskDetailRepositoryTest` update tests, `TaskDetailViewModelTest`, `TaskDetailViewModelEdgeTest`, `TaskDetailScreenTest` editing tests | same | Pass |
| 4. Checklist tick/untick, add, rename, delete | `TaskDetailRepositoryTest` checklist tests, `TaskDetailViewModelTest`/`EdgeTest` checklist tests, `TaskDetailContentTest.checklist items are ticked, renamed, deleted and added ...` | same | Pass |
| 5. Complete/reopen, move (Waiting with person), delete with undo, lists refresh | `TaskDetailRepositoryTest` actions + `after a change the live lists are told ...`, `TaskDetailViewModelTest`/`EdgeTest` actions, `TaskDetailContentTest` move/undo/timeout | same | Pass |
| 6. Offline read-only from cached list data, offline banner, edit disabled | `TaskDetailRepositoryTest` offline copy tests, `TaskDetailViewModelTest`/`EdgeTest` offline tests, `TaskDetailScreenTest` offline test | same | Pass |
| 7. All new strings in EN and DE | `TaskDetailStringsTest` (3) | same | Pass |
| 8. VM + Compose tests; `./gradlew ktlintCheck testDebugUnitTest lintDebug` passes | all of the above | same | Pass (BUILD SUCCESSFUL, exit 0, lint 0 errors) |

## Focused test execution

Command (parent, on the pre-fix revision c5e12669f + d6abae750-era tests):

`android/gradlew -p android testDebugUnitTest --tests "ch.sonensei.cockpit.gtd.task.*" --tests "ch.sonensei.cockpit.dashboard.DashboardTaskOpenTest"`

Result:

Fail on that revision: 88 tests, 2 failed: `TaskDetailRepositoryTest` initializationError (a test method returned a value; test-side, fixed) and TW-1 (expected, production defect, since fixed). Superseded by the full run below on c171fc231.

## Full test execution

Command (parent, on c171fc231):

`android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport --console=plain`

Result:

Pass: BUILD SUCCESSFUL (exit 0), whole Android unit suite green, ktlint clean, lint 0 errors. Task suites: TaskDetailScreenTest 7, TaskDraftTest 22, TaskDetailViewModelTest 17, TaskDetailViewModelEdgeTest 28, TaskDetailStringsTest 3, TaskDetailRepositoryTest 21, TaskDetailContentTest 10 (108 tests, 0 failures), plus DashboardTaskOpenTest 2 and the search tests, all passing.

## .taskcheck

Result:

Not run by the test-writer (Gradle is denied to this helper); the parent runs `sh scripts/check.sh` before `done`.

## Bugs found

### Due time is formatted with the device's digits, not ISO-8601

Finding ID:

`TW-1`

Test:

`ch.sonensei.cockpit.gtd.task.TaskDraftTest.the due time is sent with ASCII digits whatever the device language`

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskDetailViewModel.kt` (`TaskDraft.patch`, due time formatting)

Requirement:

AC 3 (due date/time saved with PATCH) and SPEC.md section 4.2 (ISO-8601 in the API).

Expected behavior:

`"dueTime":"09:05:00"` on every device.

Actual behavior:

Before the fix the body was `"۰۹:۰۵:00"` under `fa-IR` (confirmed by the parent's run). Status: Fixed in c171fc231 (`Locale.ROOT`, finding CR-3 in review.md); the test passes on c171fc231.

Severity:

Low

Fix required:

`Yes - production code must be fixed by the parent worker / fixer.` Done in c171fc231; no further action.

No other production defects found. The two observations of the first pass (links flipping the offline flag; an offline reload replacing a loaded task) were reported by the reviewer as CR-5 and CR-1 and are fixed and tested in c171fc231.

## Coverage

Status: verified (fresh run by the parent on c171fc231, exit 0).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-task-detail; base 305558bcc69b44c40f9b91a010cc944ae849c286 (from the parent); measured HEAD c171fc231a797a39cce60a73059ddc1ba7f028ad; commits listed under Scope, from the parent and `git log`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD c171fc231; production and test sources committed and unmodified (`git status`: only the untracked review reports `reviews/and-task-detail/review.md` and `tests.md`).
- Exact included files (under `android/app/src/main/java/ch/sonensei/cockpit/`): `gtd/task/TaskDetailScreen.kt`, `gtd/task/TaskDetailViewModel.kt`, `gtd/task/TaskDetailRepository.kt`, `gtd/task/TaskNavigation.kt`, `dashboard/DashboardScreen.kt`, `gtd/next/TaskListUi.kt`, `search/SearchModels.kt`, `search/SearchItemScreen.kt`, `ui/CockpitShell.kt`, `gtd/lists/ListUi.kt`, `gtd/lists/TaskLists.kt`, `gtd/lists/LogbookScreen.kt`, `gtd/lists/LogbookRepository.kt` - all 13 measured. Excluded: `res/values*/strings_task_detail.xml` (resources, checked by TaskDetailStringsTest), `tasks/and-test-tmpdir.md` (task metadata). No renames or deletions; no scoped file missing from the report.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`; `android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport --console=plain` from the clone root; whole debug unit suite.
- Run start/completion time and exit status: report written 2026-11-02 11:29:37 +0100; exit 0 (parent).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 238b8ae3d04e5cc1460b9b078471691a10c65affc153c64ae1cd2378a4c7efe3. Per-file counters re-read from it by the test-writer and match the parent's numbers.
- Evidence outputs are fresh and inputs stayed unchanged: the report was produced after the last code commit (c171fc231) and HEAD is still c171fc231 with no source changes.
- Final run after latest test additions and production fixes: yes.

Values below use covered/total (percentage), JaCoCo counters: statements = INSTRUCTION, functions = METHOD.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `gtd/task/TaskDetailScreen.kt` | 7480/9982 (74.9%) | 218/369 (59.1%) | 146/235 (62.1%) | 588/723 (81.3%) |
| `gtd/task/TaskDetailViewModel.kt` | 3016/3132 (96.3%) | 234/283 (82.7%) | 84/88 (95.5%) | 297/304 (97.7%) |
| `gtd/task/TaskDetailRepository.kt` | 1543/1583 (97.5%) | 102/112 (91.1%) | 90/95 (94.7%) | 146/149 (98.0%) |
| `gtd/task/TaskNavigation.kt` | 110/110 (100%) | 4/4 (100%) | 15/15 (100%) | 18/18 (100%) |
| `dashboard/DashboardScreen.kt` | 2050/2066 (99.2%) | 95/102 (93.1%) | 30/30 (100%) | 181/182 (99.5%) |
| `gtd/next/TaskListUi.kt` | 1495/1513 (98.8%) | 68/81 (84.0%) | 21/21 (100%) | 109/109 (100%) |
| `search/SearchModels.kt` | 338/338 (100%) | 20/20 (100%) | 18/18 (100%) | 42/42 (100%) |
| `search/SearchItemScreen.kt` | 572/775 (73.8%) | 24/41 (58.5%) | 7/16 (43.8%) | 39/43 (90.7%) |
| `ui/CockpitShell.kt` | 1890/1979 (95.5%) | 99/118 (83.9%) | 46/50 (92.0%) | 160/162 (98.8%) |
| `gtd/lists/ListUi.kt` | 1387/1409 (98.4%) | 54/65 (83.1%) | 30/35 (85.7%) | 122/122 (100%) |
| `gtd/lists/TaskLists.kt` | 784/824 (95.1%) | 26/28 (92.9%) | 31/31 (100%) | 91/91 (100%) |
| `gtd/lists/LogbookScreen.kt` | 2271/2403 (94.5%) | 107/134 (79.9%) | 46/54 (85.2%) | 162/171 (94.7%) |
| `gtd/lists/LogbookRepository.kt` | 272/281 (96.8%) | 13/15 (86.7%) | 15/15 (100%) | 40/40 (100%) |
| Aggregate (13 of 13 files, summed counters; no exclusions or unmeasured files) | 23208/26395 (87.9%) | 1064/1372 (77.6%) | 579/703 (82.4%) | 1995/2156 (92.5%) |

Note: the files other than `gtd/task/*` were only touched in a few lines; their whole-file numbers include older code and hits from older tests.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `TaskDetailScreen.kt` 122-155 (`TaskDetailRoute` link navigation: in-app item, list screen, browser `startActivity`, no-browser toast), 110-118 Hilt guard | AC 2 (tapping a linked item opens its screen or web link) | Needs a Hilt activity; the routing rule `linkTarget` reuses `searchTarget`, fully covered in `SearchModels.kt` (100%), and the link tap callback is tested in `TaskDetailContentTest`. Justified gap. |
| `TaskDetailScreen.kt` 890-997 (project picker dialog, due date and time pickers and their clear buttons), 816-841 (context chips) | AC 3 (editing project, contexts, due date/time through the UI) | Material date/time picker dialogs are not driven by the unit Compose tests; what they produce (the draft fields and PATCH body) is covered by `TaskDraftTest` and the view model tests. A device/e2e check would close it. Medium-low risk. |
| `TaskDetailScreen.kt` 1041-1083 (snackbar texts per message type), 330-347 (load error / offline-without-copy state), 405-408 (status line of a completed task) | Messages for 412, offline, moved etc.; load failure state | Message selection is covered in the view model tests; the Deleted/Undo branch is covered in `TaskDetailContentTest`. Text rendering of the other messages left uncovered; low risk. |
| `TaskDetailViewModel.kt` 515-524 (`changed()` per field for fields without a server problem), 505-506 (`messageOf` NotFound/Invalid-without-fields), 291 (load failure keeping an existing detail) | Edge branches of message/problem bookkeeping | Low risk; main paths covered (97.7% lines). |
| `TaskDetailRepository.kt` 256 (links answered non-2xx vs null body), 264 (Room row without refresh meta), 288 (empty person for Waiting) | Defensive branches | Low risk; the 2xx, 403 and 503 link paths and the Room/snapshot copies are tested. |
| `SearchItemScreen.kt` 48-98 (in-app detail per type: note, mail, contact, task fallback) | AC 1 (search opens tasks in the app) | The shell opens task hits on the task route (tested by `SearchRouteInAppTest`); the `"TASK"` branch here is a fallback. The other branches belong to earlier tasks. |
| `CockpitShell.kt` 110 (opener storing `completedAt` with the snapshot), 196-216 | CR-4 pass-through | One-line pass-through; the pieces on both sides are tested (see above). |
| `LogbookScreen.kt` 73-102, 142-175, 298-301 | Logbook's own dialog/paging (earlier task) | Outside this task's change except line 223 (covered). |

## Unable to test

- Material date/time pickers and the project picker dialog interaction, and the hand-off to a real browser for web links: need an emulator/device or a Hilt-backed UI test; follow-up only if a device check is wanted.
- `.taskcheck`: not runnable by the test-writer (Gradle denied); the parent runs it.

## Summary

Tests and task-scoped coverage are sufficient; no production defects remain open. TW-1, the only defect found by testing, was fixed in c171fc231 and its test passes. The full Android check including the 108 task-detail tests passes on c171fc231.
