# Test Report

Date: 2026-11-08

Task: and-review-steps - Android weekly review: waiting, projects, next, someday steps

Scope: `git diff 7d2195e9e305b8c3fc4b3c8ee2f30e89a43c2407..9b0bc97e341831b8ae1e418af50ea634d4f67682 -- android` (task commits 2a4ab706c, 60e4871fe, 52934c122, ecc89bbeb, 4c24ba45b, merged as 9b0bc97e3), plus the uncommitted test additions of this run listed below.

Requirements:
- tasks/and-review-steps.md acceptance criteria and recorded assumptions (follow-up title and project, delete without confirmation, ProjectOpenRequests)
- reviews/and-review-steps/review.md (CR-1 to CR-5) and fixes.md
- docs/ARCHITECTURE.md "Coverage", AGENTS.md project notes
- Web parity reference: web/src/app/features/review/steps/*

## Test files changed

Earlier in this task (an interrupted test-writer run and the fixer), already in the task commits:
- `android/app/src/test/java/ch/sonensei/cockpit/review/ReviewActionsTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/review/ReviewDecisionsViewModelTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/review/ReviewActionStepsTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/ProjectOpenRequestsTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/review/ReviewWizardRouteInAppTest.kt` (project row opens Projects detail)

This run (uncommitted, test files only):
- `android/app/src/test/java/ch/sonensei/cockpit/review/ReviewActionsTest.kt` (+3 tests)
- `android/app/src/test/java/ch/sonensei/cockpit/review/ReviewDecisionsViewModelTest.kt` (+4 tests)
- `android/app/src/test/java/ch/sonensei/cockpit/review/ReviewActionStepsTest.kt` (+2 tests)

## Tests added or updated

Count: 9 added in this run (on top of 60 task tests already present: ReviewActionsTest 16, ReviewDecisionsViewModelTest 20, ReviewActionStepsTest 15, ProjectOpenRequestsTest 5, ReviewWizardRouteInAppTest 4).

Added in this run, for gaps found against the criteria and review findings:
- ReviewDecisionsViewModelTest: `a keep tapped while the item's decision is still saved is ignored, so a refused save leaves it to decide again` (busy guard on the local-only keep path; without it the keep would mark the item decided and hide it after a refused save)
- ReviewDecisionsViewModelTest: `add next step - a second tap while it is saved does not add it twice` (busy guard, no duplicate next action)
- ReviewDecisionsViewModelTest: `add next step finishing after the user moved on updates the project but is not announced in the new step` (CR-3 on the addNextStep path, which has its own decision code)
- ReviewDecisionsViewModelTest: `a conflict in the projects, next actions or someday step loads that step again and keeps kept items out` (CR-2 for the other three steps: 409 status change, 412 move to someday, 404 someday delete; only the affected step reloads)
- ReviewActionsTest: `follow up of a waiting task outside any project creates it without a project, and a refusal is reported`
- ReviewActionsTest: `every project status is sent as the same patch status and read back from a project` (guards the `valueOf(name)` mappings both ways for all five statuses)
- ReviewActionsTest: `activating a someday project that changed elsewhere is refused with its status` (project 412 and task offline, routing never deletes)
- ReviewActionStepsTest: `a step still loading its first list shows neither the empty text nor a retry`
- ReviewActionStepsTest: `a failed reload keeps the items shown instead of the retry`

## Requirement coverage

Commands: F = `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.review.ReviewActionsTest' --tests 'ch.sonensei.cockpit.review.ReviewDecisionsViewModelTest' --tests 'ch.sonensei.cockpit.review.ReviewActionStepsTest' --tests 'ch.sonensei.cockpit.gtd.projects.ProjectOpenRequestsTest'`; C = `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.review.*' --tests 'ch.sonensei.cockpit.gtd.projects.*' createDebugUnitTestCoverageReport`; A = `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. Waiting: keep / follow up / done per item | ReviewActionsTest follow up (with and without project), complete If-Match, stale version refused; ReviewDecisionsViewModelTest still waiting, done busy/second tap, follow up title, refused/offline, 404/409/412 reload, keep while saving ignored; ReviewActionStepsTest waiting row choices, busy disables, follow-up title without whom | F, C, A | Pass |
| 2. Projects: add next step, change status, keep; rows open project detail | ReviewActionsTest set status, every status mapping, add next step (success, create fails, second call fails); ReviewDecisionsViewModelTest looks good / status / same status, row requests project, add next step (empty draft, success, not created, created but not set, 412, double tap, moved on), conflict reload; ReviewActionStepsTest title opens project (role, 48 dp), next-step field and button/IME, blank draft, busy, status menu with state description; ProjectOpenRequestsTest (ProjectsViewModel shows the requested project when created after the request and while kept); ReviewWizardRouteInAppTest project opens detail in Projects and back returns | F, C, A | Pass |
| 3. Next actions older than 30 days: keep / move to someday / delete | ReviewActionsTest stale next actions ask for 30 days, move to someday If-Match, delete 204 If-Match, refusals; ReviewDecisionsViewModelTest keep/someday/delete, 412 conflict reload keeps kept item out; ReviewActionStepsTest row choices and last-changed line, failed reload keeps items | F, C, A | Pass |
| 4. Someday (tasks and projects): activate / keep / delete | ReviewActionsTest someday order, activate task to Next vs project to ACTIVE, delete routed to tasks vs projects endpoint, refused/offline delete and activate; ReviewDecisionsViewModelTest activate/keep/delete, refused activation, 404 conflict reload; ReviewActionStepsTest Task/Project labels and choices, loading state | F, C, A | Pass |
| 5. New strings in English and German | ReviewActionStepsTest `every review string has a German translation and no German string is orphaned`, `in German the steps, choices and decisions are translated`, banner messages, every decision kind text | F, C, A | Pass |
| 6. Unit/Compose tests cover the new behavior; `./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass | all of the above | A (Pass); `sh scripts/check.sh` | A: Pass. check.sh: Not run: the sandbox refused the command that runs it, the parent worker runs `.taskcheck` |
| CR-2 to CR-5 regressions | ReviewDecisionsViewModelTest kept items stay out after conflict reload (all four steps), keep during running load, CR-3 late decision (decide and addNextStep paths), CR-4 NEXT_STEP_NOT_SET (offline, 412); ReviewActionStepsTest CR-5 state description, Button role, 48 dp | F, C | Pass |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.review.ReviewActionsTest' --tests 'ch.sonensei.cockpit.review.ReviewDecisionsViewModelTest' --tests 'ch.sonensei.cockpit.review.ReviewActionStepsTest' --tests 'ch.sonensei.cockpit.gtd.projects.ProjectOpenRequestsTest'`

Result:

Pass (ReviewActionsTest 19, ReviewDecisionsViewModelTest 24, ReviewActionStepsTest 17, ProjectOpenRequestsTest 5; 0 failures, 0 errors). A first run failed only on ktlint (argument wrapping in a new test), fixed in the test.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` (2026-11-08 17:13:47Z to 17:18:58Z)

Result:

Pass: exit 0, 353 suites, 4200 tests, 0 failures, 0 errors; ktlint and Android lint clean.

## .taskcheck

Result:

Not run: the sandbox refused running `sh scripts/check.sh` from this helper. The parent worker runs it before `done`.

## Bugs found

None. No new or existing test exposed a production defect.

## Coverage

Status: verified (scoped test selection: the `review` and `gtd.projects` test packages, which hold every test of this task).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (JaCoCo XML gives per-line counters, but no diff-intersection was done in this run).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-review-steps; base 7d2195e9e305b8c3fc4b3c8ee2f30e89a43c2407; HEAD 9b0bc97e341831b8ae1e418af50ea634d4f67682; task commits 2a4ab706c, 60e4871fe, 52934c122, ecc89bbeb, 4c24ba45b as given by the parent, file list from `git diff --stat` of base..HEAD.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 9b0bc97e3 plus uncommitted test files with sha256 ReviewActionsTest.kt 8e8675079a4eb72d83e097444d50ec1dab0ef3f598a292a7668b82663b699e44, ReviewDecisionsViewModelTest.kt 84f7498533b66be7dde7fb5e71886ea7129bfcdd3cd63ffbac6176c1e8bb2563, ReviewActionStepsTest.kt 53c297251f3ef7ac689ba4739d25187d6bdc6cd20b17d25299d5cd09d592f677 (same before and after the run). No production files uncommitted.
- Exact included files: android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/{ProjectOpenRequests.kt, ProjectsViewModel.kt}, android/app/src/main/java/ch/sonensei/cockpit/review/{ReviewActions.kt, ReviewActionSteps.kt, ReviewRepository.kt, ReviewScreen.kt, ReviewViewModel.kt, ReviewWizard.kt}. Excluded: strings_review.xml (en, de) as resources without executable code (covered by the translation test); tests and review reports. No renamed or deleted production files.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (Gradle 9.8.1, JDK 21.0.12.1); `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.review.*' --tests 'ch.sonensei.cockpit.gtd.projects.*' createDebugUnitTestCoverageReport` in `android/`; 421 tests (review 188, gtd 233).
- Run start/completion time and exit status: 2026-11-08 17:12:23Z to 17:13:16Z, exit 0, 0 failures.
- Generated report paths and report hashes/run identifier: android/app/build/reports/coverage/test/debug/report.xml (deleted before the run, regenerated at 17:13:16Z), sha256 5747eb8fdeca431666758bc7935fe41774febeefda3e5be746da3d31ff734f7c.
- Evidence outputs are fresh and inputs stayed unchanged: yes (old report removed first; test file hashes identical before and after; `git status` shows only the three test files).
- Final run after latest test additions and production fixes: yes (includes all fixer changes in HEAD and all tests of this run).

Values below use covered/total (percentage) from JaCoCo counters (Statements = INSTRUCTION).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `gtd/projects/ProjectOpenRequests.kt` | 26/26 (100.0%) | N/A | 4/4 (100.0%) | 7/7 (100.0%) |
| `gtd/projects/ProjectsViewModel.kt` | 3689/3731 (98.9%) | 310/336 (92.3%) | 149/152 (98.0%) | 417/417 (100.0%) |
| `review/ReviewActions.kt` | 761/775 (98.2%) | 10/10 (100.0%) | 46/47 (97.9%) | 69/70 (98.6%) |
| `review/ReviewActionSteps.kt` | 3223/3266 (98.7%) | 96/107 (89.7%) | 64/65 (98.5%) | 202/203 (99.5%) |
| `review/ReviewRepository.kt` | 763/766 (99.6%) | 30/30 (100.0%) | 52/53 (98.1%) | 100/100 (100.0%) |
| `review/ReviewScreen.kt` | 2707/2796 (96.8%) | 100/106 (94.3%) | 72/98 (73.5%) | 275/277 (99.3%) |
| `review/ReviewViewModel.kt` | 3620/3645 (99.3%) | 160/176 (90.9%) | 121/121 (100.0%) | 316/319 (99.1%) |
| `review/ReviewWizard.kt` | 3695/3755 (98.4%) | 93/100 (93.0%) | 51/53 (96.2%) | 311/313 (99.4%) |
| Aggregate (8 files, summed counters, none unmeasured) | 18484/18760 (98.5%) | 799/865 (92.4%) | 559/593 (94.3%) | 1697/1706 (99.5%) |

ProjectsViewModel.kt, ReviewScreen.kt, ReviewWizard.kt and ReviewRepository.kt are whole files with mostly pre-existing code; the task changed only small parts of them.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ReviewActions.kt:39` | Default-argument constructor of `SomedayItem` (version default) | Justified gap: generated code, every caller passes a version |
| `ReviewActionSteps.kt:237`, branches at 226/234/236 | Unreachable fallthrough of the exhaustive `when`, null `project`/`status` fallbacks of the decision text | Justified gap: every decision kind is rendered by `the decision line announces each kind of decision` |
| `ReviewActionSteps.kt` branches at 66, 142-143, 178 | Blank whom on a waiting item, next action without `updatedAt`, LazyColumn key lambdas | Justified gap: display detail only; the meaningful states (whom/no whom, last changed) are tested |
| `ReviewViewModel.kt:314-315` | Capture cancellation (pre-existing code, not this task) | Outside task responsibility |
| `ReviewViewModel.kt:499` and branches 400-401, 494 | `else -> it` for non-action steps in `removeItem`; `addNextStep` with no draft entry at all | Justified gap: unreachable for the four steps; the empty/blank draft guard is tested |
| `ReviewViewModel.kt` other partial branches (172-285, 513, 586, 607) | Pre-existing overview/save code and `StepData.map` null value | Outside this task, or covered at line level |
| `ReviewScreen.kt:68, 355`; `ReviewWizard.kt:99, 203`; `ProjectsViewModel.kt` partial branches | Pre-existing Hilt wiring and screen code; ReviewScreen function count includes the 14 callback default lambdas that tests replace | Outside task responsibility; the task wiring (`ReviewCallbacks.of` openProject) is exercised by ReviewWizardRouteInAppTest |

## Unable to test

- `sh scripts/check.sh` (`.taskcheck`): not run by this helper because the sandbox refused the command; the parent runs it.
- TalkBack speech itself: only the semantics (state description, role, live region) are checked in Robolectric; real screen-reader output needs a device. No follow-up needed for this task.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. Nine tests added in this run for real gaps (busy guards, CR-2/CR-3 on the remaining paths, status mapping, loading/failed-reload rendering). All focused, scoped and full Android validation passed; `.taskcheck` is left to the parent worker.
