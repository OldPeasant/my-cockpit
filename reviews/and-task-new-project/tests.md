# Test Report

Date: 2026-11-14

Task: and-task-new-project - Android task detail: create a new project from the project picker

Scope: `git diff 0415413eef1fc2bc162f3d44ef2457c8971dd1a0..4e39ae4159871458a6c77fd0f431bc844acfa46e -- android` (task commits 290215502 implementation, 9942f87e5 tests and reports, a1cfe443f fixes for CR-2/CR-3, 4e39ae415 extra tests; all given by the parent). Measured at HEAD `bf73870191c1f57c2fc764e4e5fc4ef800365318`. `git diff 4e39ae415..bf7387019` changes only `reviews/and-task-new-project/review.md`, so the code measured is the final revision 4e39ae415. Branch `main`, working tree clean.

This is the final re-run on the code after the fixes. The earlier report described the pre-fix revision 990502fd and is replaced by this one.

Requirements:
- `tasks/and-task-new-project.md` acceptance criteria and its three recorded assumptions (project set in the draft and assigned on Save; a cancelled form keeps the project; `ProjectResponse.toDomain()` added in `gtd/data/GtdMappers.kt` for CR-3)
- `reviews/and-task-new-project/review.md` and `fixes.md` (CR-2: a project made with "New project…" gets the saved task as its next step if stalled, as Inbox processing does; CR-3: a shared project mapper)
- `docs/ARCHITECTURE.md` "Coverage"; parent instructions

## Test files changed

None in this run. The fix commit a1cfe443f already has tests: four repository tests for `makeNextStepIfStalled` (stalled, not stalled, failures, no connection), view model tests that check the next-step call happens only after a successful save and only for a project created in this screen, and `GtdMappersTest` `a project answer maps straight to the same domain project as through the cache row`. A review of the fix diff against the coverage below found no remaining material gap, so no tests were added.

Task test files (from earlier task commits): `TaskDetailNewProjectViewModelTest.kt`, `TaskDetailNewProjectScreenTest.kt`, `TaskDetailNewProjectRepositoryTest.kt`, `TaskDetailStringsTest.kt`, `FakeTaskDetailSource.kt`, `gtd/data/GtdMappersTest.kt` (under `android/app/src/test/java/ch/sonensei/cockpit/`).

## Tests added or updated

Count: 0 (in this run)

## Requirement coverage

Command for all rows: `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.*' createDebugUnitTestCoverageReport`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: picker has "New project…" | `the picker offers New project after the projects`, `the dialog asks only for a title and Create is disabled while it is blank` | above | Pass |
| AC1: asks only for a title, creates an Active project with no area | `creates an Active project with only the title, then reloads the projects`, `Create passes the trimmed title and closes the dialog` | above | Pass |
| AC1: assigns the task to it | `a new project is created with the trimmed title, put in the draft and offered in the picker`, `saving after a new project sends the task's new projectId`, `with the view model the new project shows in the picker and is saved with the task` | above | Pass |
| AC1 (CR-2): the new project gets the task as next step, as in Inbox processing | Repository: `a stalled project gets the task as its next step with If-Match, then the projects reload`, `a project that is not stalled keeps its next step`, `failures are ignored and reload nothing`, `no connection at all is ignored too`; view model next-step assertions in `TaskDetailNewProjectViewModelTest` (called after a successful save only, not for other projects or failed saves) | above | Pass |
| AC1 (CR-3): created project maps like the cached one | `a project answer maps straight to the same domain project as through the cache row` | above | Pass |
| AC2: Cancel or empty title leaves the project unchanged | `Cancel closes the dialog and leaves the task's project`, `a blank title creates nothing and leaves the project`, `without the edit form nothing is created` | above | Pass |
| AC2: create/save failure shows an error; retry creates no second project | `a failed create says so and leaves the project unchanged`, `a refused create shows the server's field message, else its detail`, `an offline create says offline and leaves the project unchanged`, `a failed create can be retried and then creates the project once`, `saving again after a failed save does not create a second project`, `the same title again reuses the project made here, another title creates a new one`, Compose `a failed create shows the error and the task keeps its project`, `a refused create shows the server's reason` | above | Pass |
| AC3: new project in the picker and in Projects | `once the projects reload with the new project it is offered only once`, `creates an Active project ... then reloads the projects` (cache holds the new project) | above | Pass |
| AC3: task appears in the project's task list | Indirect: the saved PATCH carries `projectId` (view model and Compose tests) | above | Pass (indirect) |
| AC4: EN + DE strings | `the New project texts exist in English and German, the error keeps the server's reason`, generic German string tests | above | Pass |
| AC5: tests; ktlintCheck + testDebugUnitTest + lintDebug + `sh scripts/check.sh` | all of the above | above | ktlintCheck and `gtd.*` unit tests Pass; `lintDebug`, full Android suite and `scripts/check.sh` Not run: time budget, left to the parent |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.*' createDebugUnitTestCoverageReport`

Result:

Pass. BUILD SUCCESSFUL in 1m 53s, exit 0. ktlintCheck up to date (clean). `testDebugUnitTest` re-executed: 89 result files (all newer than this run's start), 1243 tests, 0 failures, 0 errors, 0 skipped.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Not run: ~20 minute budget. Only the `ch.sonensei.cockpit.gtd.*` packages ran. The parent runs the full Android suite and `lintDebug`.

## .taskcheck

Result:

Not run (`sh scripts/check.sh` is left to the parent worker before `done`).

## Bugs found

None.

## Coverage

Status: verified (task-scoped, from a run of the `gtd.*` test packages)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured separately. JaCoCo XML `<line>` entries (ci/mi/cb/mb) intersected with the added lines of `git diff -U0 0415413e..HEAD` per file (script `.tmp/tw_cov2.py`, Git-ignored).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-task-new-project`; base `0415413eef1fc2bc162f3d44ef2457c8971dd1a0`; final code revision `4e39ae4159871458a6c77fd0f431bc844acfa46e`; measured HEAD `bf73870191c1f57c2fc764e4e5fc4ef800365318` (reviews-only commit on top). Commits and scope given by the parent and checked with `git diff --stat 0415413e..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD bf7387019, clean working tree. sha256 of the four production files recorded before the run (`.tmp/tw_before.sha`) and matched after it.
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskDetailRepository.kt`, `.../gtd/task/TaskDetailScreen.kt`, `.../gtd/task/TaskDetailViewModel.kt`, `.../gtd/data/GtdMappers.kt`. Excluded: `res/values/strings_task_detail.xml`, `res/values-de/strings_task_detail.xml` (resources, not instrumentable; covered by the strings test), test files. No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (`createDebugUnitTestCoverageReport`), Gradle 9.8.1; command above; cwd `android/`; selection `--tests 'ch.sonensei.cockpit.gtd.*'`.
- Run start/completion time and exit status: 2026-11-14 09:39:33 UTC to 09:41:31 UTC; exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` (mtime 09:41:31 UTC, sha256 `285cf1f3cb62e34351a610d8efc550fe76156f25f40c7037bdfe2db16566362a`); exec `android/app/build/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec` (mtime 09:41:26 UTC).
- Evidence outputs are fresh and inputs stayed unchanged: `testDebugUnitTest` and the report task executed in this run (not up to date); the production hashes match before and after.
- Final run after latest test additions and production fixes: yes (covers fix commit a1cfe443f and test commit 4e39ae415).

Values below use covered/total (percentage). JaCoCo "Statements" = INSTRUCTION, "Functions" = METHOD.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `gtd/task/TaskDetailRepository.kt` | 1817/1862 (97.6%) | 112/118 (94.9%) | 101/105 (96.2%) | 173/181 (95.6%) |
| `gtd/task/TaskDetailScreen.kt` | 10015/12027 (83.3%) | 309/422 (73.2%) | 220/306 (71.9%) | 780/885 (88.1%) |
| `gtd/task/TaskDetailViewModel.kt` | 5307/5470 (97.0%) | 438/505 (86.7%) | 128/133 (96.2%) | 489/505 (96.8%) |
| `gtd/data/GtdMappers.kt` | 614/614 (100.0%) | 50/50 (100.0%) | 7/7 (100.0%) | 83/83 (100.0%) |
| Aggregate (summed counters, 4 files; the 2 string resources excluded as non-instrumentable) | 17753/19973 (88.9%) | 909/1095 (83.0%) | 456/551 (82.8%) | 1525/1654 (92.2%) |

Changed-line coverage (lines added by the task):
- TaskDetailRepository.kt: lines 14/18, branches 4/4
- TaskDetailScreen.kt: lines 47/47, branches 15/18
- TaskDetailViewModel.kt: lines 38/38, branches 29/32
- GtdMappers.kt: lines 1/1, branches 0/0
- Total: lines 100/104, branches on changed lines 48/54

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `TaskDetailRepository.kt:395-396` | `makeNextStepIfStalled` rethrows `CancellationException` | Not material: standard coroutine rethrow, same pattern as the rest of the repository |
| `TaskDetailRepository.kt:399, 401` | `catch SerializationException` / `catch DateTimeException` in `makeNextStepIfStalled` | Not material: defensive catches of the best-effort call. The "unreadable answer" case is tested (`failures are ignored and reload nothing`) and goes through the `IllegalArgumentException` catch (line 400, covered); the result (nothing happens, nothing reloads) is identical |
| `TaskDetailScreen.kt:1226` (2 branches) | `detail` present but blank, so the generic error text is used | Not material: same `takeIf { isNotBlank }` fallback used elsewhere in the screen |
| `TaskDetailScreen.kt:1048` (1 branch) | Compose-generated branch of the dialog's `if (naming)` / `rememberSaveable` | Not material: opening and closing the dialog are tested |
| `TaskDetailViewModel.kt:469` (2 branches) | `newProject` guard for `busy` / `readOnly` | Low risk, defensive: the picker is disabled in those states; the `draft == null` and blank-title parts are tested |
| `TaskDetailViewModel.kt:481` (1 branch) | `st.draft?.copy` when the form closed while the create was pending | Not covered: the project is created but set on no draft (acceptable). Needs a suspended fake; left as a minor gap |

## Unable to test

- AC3 "the task appears in the project's task list": only shown indirectly through the `projectId` in the saved PATCH (and the next-step POST for a stalled new project). An end-to-end check needs the Projects screen against a real server, outside this unit scope. Low priority.
- `lintDebug`, the full Android unit suite and `sh scripts/check.sh` were not run here (time budget). The parent must run them before `done`.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. The final revision (including fix commit a1cfe443f) passes ktlintCheck and all 1243 `gtd.*` unit tests; changed-line coverage is 100/104 lines, and the only uncovered changed lines are defensive catch clauses of the best-effort next-step call.
