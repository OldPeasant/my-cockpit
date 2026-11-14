# Test Report

Date: 2026-11-14

Task: and-task-new-project - Android task detail: create a new project from the project picker

Scope: `git diff 42aeffb510fbaa757d97b23da39a83f72ef847ff..990502fd7ba7ca48931f268e467f5f499340fdb9 -- android` (implementation commit given by the parent). Measured at HEAD `c65dc2e032ca50ebebae30e201c375faba0eeef0`; `git diff 990502fd..HEAD -- android` is empty, so the production code measured equals the implementation commit. Branch `main`, no uncommitted production changes.

Requirements:
- `tasks/and-task-new-project.md` acceptance criteria and its two recorded assumptions (project set in the draft, assigned on Save; a cancelled form keeps the created project)
- Parent instructions (test list); `docs/ARCHITECTURE.md` "Coverage"; existing tests in `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/`

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskDetailNewProjectViewModelTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskDetailNewProjectScreenTest.kt` (new, Robolectric Compose)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskDetailNewProjectRepositoryTest.kt` (new, MockWebServer + in-memory Room)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskDetailStringsTest.kt` (one test added)

## Tests added or updated

Count: 22 (11 view model, 7 Compose, 3 repository, 1 strings)

## Requirement coverage

Command for all rows: `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.task.*' createDebugUnitTestCoverageReport`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: picker has "New project…" | `the picker offers New project after the projects`, `the dialog asks only for a title and Create is disabled while it is blank` | above | Pass |
| AC1: asks only for a title, creates Active project without area | `creates an Active project with only the title, then reloads the projects` (POST body has only `title`, response mapped to ACTIVE, no area); `Create passes the trimmed title and closes the dialog` | above | Pass |
| AC1: assigns the task to it | `a new project is created with the trimmed title, put in the draft and offered in the picker`, `saving after a new project sends the task's new projectId`, `with the view model the new project shows in the picker and is saved with the task` | above | Pass |
| AC2: Cancel leaves the project unchanged | `Cancel closes the dialog and leaves the task's project` | above | Pass |
| AC2: empty title leaves the project unchanged | `a blank title creates nothing and leaves the project`, `the dialog asks only for a title and Create is disabled while it is blank`, `without the edit form nothing is created` | above | Pass |
| AC2: create failure shows an error, project unchanged | `a failed create says so and leaves the project unchanged`, `a refused create shows the server's field message, else its detail`, `an offline create says offline and leaves the project unchanged`, `a failed create shows the error and the task keeps its project`, `a refused create shows the server's reason` | above | Pass |
| AC2: retry does not create a second project | `saving again after a failed save does not create a second project`, `the same title again reuses the project made here, another title creates a new one`, `a failed create can be retried and then creates the project once` | above | Pass |
| AC3: new project in the picker | `a new project is created ...`, `once the projects reload with the new project it is offered only once`, Compose `with the view model the new project shows in the picker ...` | above | Pass |
| AC3: new project in Projects (projects reloaded into the cache) | `creates an Active project with only the title, then reloads the projects` (GET /api/v1/projects once, cache holds Garden + Kitchen); failures reload nothing: `a refused title ...`, `an unreachable server is offline, a refused request a failure, ...` | above | Pass |
| AC3: task appears in the project's task list | Covered indirectly: the saved PATCH carries `projectId` (view model and Compose tests). The Projects screen's own task list is not exercised here. | above | Pass (indirect) |
| AC4: EN + DE strings | `the New project texts exist in English and German, the error keeps the server's reason`, plus the existing generic `every string exists in German ...` / `the German texts are translated, not copied` | above | Pass |
| AC5: unit/Compose tests, ktlintCheck + testDebugUnitTest | all of the above | above | Pass (task package only; `lintDebug` and `sh scripts/check.sh` not run by me) |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.task.*' createDebugUnitTestCoverageReport`

Result:

Pass. Exit 0, ktlint clean (main + test source sets). `ch.sonensei.cockpit.gtd.task.*`: 362 tests, 0 failures, 0 errors, 0 skipped (from `android/app/build/test-results/testDebugUnitTest/TEST-ch.sonensei.cockpit.gtd.task.*.xml`). New suites: ViewModel 11/11, Screen 7/7, Repository 3/3, Strings 6/6.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Not run: the session had limited time. Only the `gtd.task` package was run. The parent runs the full Android suite, `lintDebug`, and `.taskcheck`.

## .taskcheck

Result:

Not run (`sh scripts/check.sh` is left to the parent worker before `done`).

## Bugs found

None. Every new test passed against the implementation commit.

## Coverage

Status: verified (task-scoped, from a run of the `gtd.task` test package only)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured separately. JaCoCo XML `<line>` entries (ci/mb/cb) intersected with the lines added in `git diff -U0 42aeffb5..990502fd` for each file (script `.tmp/tw_cov.py`, Git-ignored).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-task-new-project`; base `42aeffb510fbaa757d97b23da39a83f72ef847ff`; implementation `990502fd7ba7ca48931f268e467f5f499340fdb9` (given by the parent); measured HEAD `c65dc2e032ca50ebebae30e201c375faba0eeef0`, which has no Android changes after 990502fd.
- Source/test snapshot identifier (including uncommitted inputs): HEAD c65dc2e0 plus the untracked/modified tests (sha256): RepositoryTest `cce4e660…41f9`, ScreenTest `3fe5e214…3336`, ViewModelTest `1d6cf77a…dd13`, StringsTest `ea46a08f…2389`. No production files are uncommitted.
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskDetailRepository.kt`, `TaskDetailScreen.kt`, `TaskDetailViewModel.kt`. Excluded: `res/values/strings_task_detail.xml` and `res/values-de/strings_task_detail.xml`, which are resources with nothing JaCoCo can instrument (covered by the strings test instead), and test files. No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (`createDebugUnitTestCoverageReport`); command above; cwd `android/`; selection `--tests 'ch.sonensei.cockpit.gtd.task.*'`.
- Run start/completion time and exit status: 2026-11-14 09:20:18 UTC to about 09:21:42 UTC; exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` (mtime 09:21:42 UTC, sha256 `d02218735077705295c181e91a3fa3957ee9f407a6f9b6b347da5432e0b583a8`); exec `android/app/build/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec` (mtime 09:21:37 UTC).
- Evidence outputs are fresh and inputs stayed unchanged: the test task re-executed because the ScreenTest input changed. The exec and report were rewritten during this run, and the test hashes match after the run.
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage). JaCoCo "Statements" = INSTRUCTION counter.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `gtd/task/TaskDetailRepository.kt` | 1751/1797 (97.4%) | 110/116 (94.8%) | 98/104 (94.2%) | 164/169 (97.0%) |
| `gtd/task/TaskDetailScreen.kt` | 10015/12027 (83.3%) | 309/422 (73.2%) | 220/306 (71.9%) | 780/885 (88.1%) |
| `gtd/task/TaskDetailViewModel.kt` | 5248/5411 (97.0%) | 434/501 (86.6%) | 128/133 (96.2%) | 486/502 (96.8%) |
| Aggregate (summed counters, 3 files; string resources excluded as non-instrumentable) | 17014/19235 (88.5%) | 853/1039 (82.1%) | 446/543 (82.1%) | 1430/1556 (91.9%) |

Changed-line coverage (lines added by the task):
- TaskDetailRepository.kt: lines 6/6, branches 2/2
- TaskDetailScreen.kt: lines 47/47, branches 15/18
- TaskDetailViewModel.kt: lines 35/35, branches 25/28
- Total: lines 88/88, branches on changed lines 42/48

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `TaskDetailScreen.kt:1227-1230` (first run) | Error text with the server's reason (`Project not created: %1$s`) | Added `a refused create shows the server's reason`. Covered in the final run |
| `TaskDetailScreen.kt:1226` (2 branches missed) | `detail` present but blank, so the generic text is used | Not material. It is the same `takeIf { isNotBlank }` fallback the screen already uses for other messages |
| `TaskDetailScreen.kt:1048` (1 branch) | Compose-generated branch of `if (naming)` / `rememberSaveable` | Not material. The dialog opening and closing are tested |
| `TaskDetailViewModel.kt:469` (2 branches) | `newProject` guard for `busy` / `readOnly` | Low risk, defensive. The Compose picker is disabled in those states. The `draft == null` and blank parts of the guard are tested |
| `TaskDetailViewModel.kt:481` (1 branch) | `st.draft?.copy` when the form was closed while the create was pending | Not covered. The project would be created but not set on any draft (acceptable). A test would need a suspended fake, and there was not enough time |

## Unable to test

- "The task appears in the project's task list" (AC3): only shown indirectly, through the `projectId` in the saved PATCH. Showing it end-to-end needs the Projects screen against a real server, which is outside this unit scope. Low priority for follow-up.
- `lintDebug`, the full Android unit suite and `sh scripts/check.sh` were not run here. The parent must run them.

## Summary

Tests added and all validation passed: 22 new tests, 0 failures, 0 production defects found, and changed-line coverage of 88/88 lines. The remaining gaps are minor guard branches, listed above.
