# Test Report

Date: 2026-10-21

Task: and-projects-lists - Android projects, waiting, someday

Scope: `git diff db31b7fa5235aef1cac2ab182f8aff77eb8807b1..0b64a78ad8ce0ebd4ca63b7843dd524bb9e44b98 -- android` (base = `git merge-base HEAD origin/main`; HEAD 0b64a78a on branch main). Task commits: 1e95f760, 3bc8cef0, 17d08e52, ca1d88a9, a0dcc05e, c50d46ec, 0b64a78a (all `and-projects-lists:` commits since the base). Production code is final (all review findings fixed, see review.md and fixes.md). Uncommitted at the start: none. Uncommitted at the end: only the test files listed below.

Requirements:
- tasks/and-projects-lists.md (2 acceptance criteria, 4 recorded assumptions)
- SPEC.md §5.2 (stalled rule, "Choose next step" prompt), §5.3 (projects grouped by area, stalled highlighted, detail with next step, Waiting For, Someday), §4.2 (If-Match)
- docs/ARCHITECTURE.md "Coverage", AGENTS.md project notes
- reviews/and-projects-lists/review.md and fixes.md
- Existing test conventions (gtd/next and gtd/today route and in-app tests)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/ProjectsRouteTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/ProjectsScreensInAppTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/lists/ListRoutesTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/ProjectsScreenTest.kt` (2 tests added)

## Tests added or updated

Count: 12

The existing 117 tests covered the view models, repositories, grouping and the screens driven by a fake state. The gap was the wiring between them. `ProjectsRoute`, `WaitingRoute`, `SomedayRoute`, `ProjectsCallbacks.of` and `LiveListCallbacks.of` were never executed (0 % in the first coverage run). `ProjectsCallbacks.of` passes ten callbacks by position, so if two were swapped no existing test would fail. The Hilt-built view models behind the Projects, Waiting and Someday tabs were not exercised either.

- `ProjectsRouteTest` (Robolectric + Compose, real `ProjectsViewModel`, mocked repositories):
  - `the list opens a project, shows finished projects, and back returns to the list`
  - `making a task the next step sends it with the project's version and confirms it`
  - `completing the next step prompts for a new one, and choosing a task there sets it`
  - `a stalled project's choose button opens the prompt, which adds a new next step or is skipped`
- `ListRoutesTest` (real `WaitingViewModel`/`SomedayViewModel`):
  - `waiting shows the longest waiting first and follows up through the server`
  - `waiting completes a task with undo through the completion repository`
  - `someday activates a task through the server`
- `ProjectsScreensInAppTest` (Hilt `MainActivity`, like `NextScreenInAppTest`):
  - `the Projects tab is the real Projects view`
  - `the Waiting tab is the real Waiting For view`
  - `the Someday tab is the real Someday view`
- `ProjectsScreenTest`:
  - `a project with a due date shows it next to the stalled badge`
  - `a detail that could not load while offline says the server is unreachable and offers a retry`

## Requirement coverage

Command A = `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.projects.*' --tests 'ch.sonensei.cockpit.gtd.lists.*' createDebugUnitTestCoverageReport`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Projects grouped by area, stalled highlighted | ProjectGroupingTest (9), ProjectsScreenTest `groups projects by area and highlights the stalled one`, `a project with a due date shows it next to the stalled badge`, ProjectsViewModelTest `groups by area in area order, stalled first, finished hidden until asked`, ProjectsRouteTest `the list opens a project, shows finished projects, ...` | A | Pass |
| AC1 Project detail with tasks and next step | ProjectsScreenTest `the detail marks the next step ...`, detail load/stale/offline tests, ProjectsViewModelTest `opening a project shows its tasks and next step`, ProjectsRouteTest `making a task the next step ...` | A | Pass |
| AC1 / SPEC 5.2 "Choose next step" prompt | ProjectsScreenTest prompt tests, ProjectsViewModelTest prompt tests, ProjectWorkRepositoryTest `completing the next step reports that the project needs one ...`, ProjectsRouteTest `completing the next step prompts ...`, `a stalled project's choose button opens the prompt ...` | A | Pass |
| AC1 Waiting For (longest waiting first, follow up) | ListViewModelsTest, ListScreensTest waiting tests, TaskListsRepositoryTest `follow up creates a next action in the same project`, ListRoutesTest `waiting shows the longest waiting first ...`, `waiting completes a task with undo ...` | A | Pass |
| AC1 Someday (activate) | ListScreensTest `someday activates a task`, ListViewModelsTest `activate moves the task and reports a refusal`, TaskListsRepositoryTest `activate moves to next ...`, ListRoutesTest `someday activates a task through the server` | A | Pass |
| AC1 the three screens are reachable in the app (Hilt) | ProjectsScreensInAppTest (3) | A | Pass |
| AC2 `gtd.projects.*` passes, covering stalled badge and next-step prompt with Robolectric/Compose UI tests; and `gtd.lists` | all 129 tests in both packages | A | Pass (129 tests, 0 failures, 0 skipped) |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.projects.*' --tests 'ch.sonensei.cockpit.gtd.lists.*' createDebugUnitTestCoverageReport`

Result:

Pass. BUILD SUCCESSFUL, ktlintCheck passed. Results from the JUnit XML: ProjectsScreenTest 28, ProjectsViewModelTest 27, ProjectWorkRepositoryTest 16, ProjectGroupingTest 9, ProjectsRouteTest 4, ProjectsScreensInAppTest 3, ListScreensTest 14, ListViewModelsTest 13, LiveLoadTest 6, TaskListsRepositoryTest 6, ListRoutesTest 3. Total 129, 0 failures, 0 errors, 0 skipped.

## Full test execution

Command:

`sh scripts/check.sh` (server, web and android lint plus unit tests)

Result:

Pass ("== all checks passed", 2026-10-21 12:35:37 to 12:39:16 +0200). Android: 88 suites, 910 tests, 0 failures, 0 errors, 0 skipped.

## .taskcheck

Result:

Pass (`.taskcheck` is `sh scripts/check.sh`, see above).

## Bugs found

None. While I wrote the tests, two of them failed. Both failures came from the tests: the stalled badge is in the merged row and needs `useUnmergedTree`, and a substring text match found 2 nodes. Production behaviour matched the requirements.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files. This includes hits from all tests in `gtd.projects.*` and `gtd.lists.*`, including the tests that existed before.
Changed-line coverage: not measured. All 8 Kotlin files are new or almost entirely new in this task (1833 insertions, 3 deletions; the 3 removed lines are placeholder bodies of SomedayScreen/WaitingScreen/ProjectsScreen). So whole-file coverage is essentially changed-line coverage, but no changed-line intersection was computed.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-projects-lists; base db31b7fa5235aef1cac2ab182f8aff77eb8807b1 (`git merge-base HEAD origin/main`); HEAD 0b64a78ad8ce0ebd4ca63b7843dd524bb9e44b98; scope from `git diff --name-only db31b7fa..HEAD -- android/app/src/main`, all 7 commits in the range are task commits, and the files lie within the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): production and test files of both packages unchanged in the index (`git ls-files -s` hash 61fca421...e79d03, the same before and after the run). Uncommitted test files (sha256): ListRoutesTest.kt 14c81eff...793f, ProjectsRouteTest.kt e4804c4a...2adf, ProjectsScreensInAppTest.kt 53b5285b...12f7, ProjectsScreenTest.kt 8183142c...11c0. Hashes taken before and after the run are identical.
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/gtd/lists/{ListUi,ListViewModels,SomedayScreen,TaskLists,WaitingScreen}.kt`, `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/{ProjectWorkRepository,ProjectsScreen,ProjectsViewModel}.kt`. Excluded: `android/app/src/main/res/values/strings_projects_lists.xml` (resource strings, not executable; JaCoCo does not instrument it). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (version not pinned in the build, AGP default; report DTD "JACOCO Report 1.1"); command A above; working directory `android/`; test selection `ch.sonensei.cockpit.gtd.projects.*` and `ch.sonensei.cockpit.gtd.lists.*`.
- Run start/completion time and exit status: 2026-10-21 12:34:46 to 12:35:25 +0200; BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` (written 12:35:25.43, sha256 27bd8757e6568357765f485698a1b03af48e68f1dafb3bc7d17536adf1b55f9f, JaCoCo session id dell26-296b2cc4).
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report mtime is the end of this run. The test-results directory holds exactly the 11 suites of this selection, and the snapshot hashes match before and after.
- Final run after latest test additions and production fixes: yes. Production code was not changed during testing, and the run came after the last test edit and ktlintFormat.

Values below use covered/total (percentage) from the per-file (`sourcefile` element) counters of the JaCoCo XML. Statements = JaCoCo INSTRUCTION (bytecode instructions; JaCoCo has no statement counter).

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `gtd/projects/ProjectWorkRepository.kt` | 493/493 (100.0%) | 12/12 (100.0%) | 23/23 (100.0%) | 54/54 (100.0%) |
| `gtd/projects/ProjectsViewModel.kt` | 1701/1725 (98.6%) | 132/142 (93.0%) | 80/82 (97.6%) | 194/194 (100.0%) |
| `gtd/projects/ProjectsScreen.kt` | 3870/3947 (98.0%) | 150/164 (91.5%) | 69/79 (87.3%) | 327/330 (99.1%) |
| `gtd/lists/ListUi.kt` | 1331/1360 (97.9%) | 52/64 (81.2%) | 30/35 (85.7%) | 118/118 (100.0%) |
| `gtd/lists/ListViewModels.kt` | 586/595 (98.5%) | 13/14 (92.9%) | 37/38 (97.4%) | 77/77 (100.0%) |
| `gtd/lists/TaskLists.kt` | 770/808 (95.3%) | 26/28 (92.9%) | 30/30 (100.0%) | 89/89 (100.0%) |
| `gtd/lists/WaitingScreen.kt` | 681/708 (96.2%) | 56/60 (93.3%) | 9/9 (100.0%) | 58/59 (98.3%) |
| `gtd/lists/SomedayScreen.kt` | 304/331 (91.8%) | 17/20 (85.0%) | 7/7 (100.0%) | 31/32 (96.9%) |
| **Aggregate (summed counters, 8 of 8 Kotlin files measured; strings XML excluded)** | 9736/9967 (97.7%) | 458/504 (90.9%) | 285/303 (94.1%) | 948/953 (99.5%) |

Before the new tests (same command without them, 12:29:39, report sha256 7911df2c...a567), the aggregate was instructions 9147/9967 (91.8%), branches 411/504 (81.5%), methods 271/303 (89.4%), lines 893/953 (93.7%). SomedayScreen.kt was then at 19/32 lines and WaitingScreen.kt at 46/59, because the routes were never run.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ProjectsScreen.kt:110-121`, `ListUi.kt:62`, `ProjectsScreen.kt:87-93`, `WaitingScreen.kt:33-39`, `SomedayScreen.kt:28-34` (callbacks `of(vm)`, `*Route`) | Wiring of each screen control to the right view model action. A swapped positional callback would break the app. | Covered now by ProjectsRouteTest and ListRoutesTest |
| `ProjectsViewModel.kt:126`, `ListViewModels.kt:153,175` (`@Inject` constructors), `ProjectsScreen.kt:78-79`, `WaitingScreen.kt:24-25`, `SomedayScreen.kt:19-20` (Hilt-activity branch) | Tabs show the real screens in the app | Covered now by ProjectsScreensInAppTest |
| `ProjectsScreen.kt:263` (project due date), `:369` (detail first-load offline error text) | Project row details, offline error message | Covered now by the 2 new ProjectsScreenTest tests |
| `ProjectsScreen.kt:82`, `WaitingScreen.kt:28`, `SomedayScreen.kt:23` (placeholder outside a Hilt activity) | Shell navigation and previews outside Hilt | Not material to this task. It is the shell's fallback, exercised by the shell navigation tests outside this package selection. |
| `ProjectsScreen.kt:308,477` and partial `when` branches (`:302`, `:471`, `ListUi.kt:215`) | none | Closing lines of exhaustive `when` over enums (compiler-generated no-match path). Not reachable. |
| `ProjectsViewModel.kt:104,179,184,216,217,223,253,260,280` partial branches | Null-safe chains (`detail?.project?.id`) and boolean short-circuits in `loading`, `addNextStep` reuse and `forgetCompleted` | The behaviours are covered by VM tests (no open project, a partly failed add with the same or a different title, pruning completed tasks). The remaining branch halves are short-circuit combinations with no separate behaviour. Left uncovered. |
| `ListUi.kt:75,78,88,95,112,114,145,180` partial branches; `TaskLists.kt:186-187` (`energy`/`creativity` mapping of null) | Optional row content (contexts, due chips) and model mapping of absent optional fields | Presentation and mapping of optional fields already exercised in gtd/next and gtd/data tests. Not material to this task. |

## Unable to test

- Live behaviour against a real server (network timeouts, the change stream). The in-app Hilt test only asserts that the real screens render with a loading, empty, list or error state. No backend runs in unit tests. The acceptance criteria name only the unit test suite, so no follow-up is needed for this task.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production defects found.
