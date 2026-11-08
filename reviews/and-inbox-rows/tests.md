# Test Report

Date: 2026-11-08

Task: and-inbox-rows - Android: inbox and New view rows complete and open tasks

Scope: `git diff db8cd5ded04a2d1cb51257378035dff4eeeed45c..47e4bbcc0abed9b2ece97daa2121c2355f76a01a -- android` (task commits 03fe305b6 impl, 2b869379b tests, 47e4bbcc0 CR-1 fix; these are the rebased forms of 7771bba40, 1f0cacae8, 3a3ca26ec named by the parent, and `git diff --stat 3a3ca26ec 47e4bbcc0 -- android` is empty, so the Android content is identical), plus one uncommitted test change from this run.

Requirements:
- `tasks/and-inbox-rows.md` acceptance criteria and recorded assumptions
- SPEC.md §5.3 (the New view opens a task in the task detail)
- `docs/ARCHITECTURE.md` "Coverage"; AGENTS.md project notes
- `reviews/and-inbox-rows/review.md` (CR-1) and `reviews/and-inbox-rows/fixes.md`

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/gtd/inbox/InboxViewModelTest.kt`

## Tests added or updated

Count: 2 (added in this run; the task's earlier commits already hold 30 new or rewritten tests in InboxScreenTest (11), InboxRouteTest (3), InboxViewModelTest (13) and NewRouteTest (3), plus updated cases in NewScreenTest and NewStoresTest)

- `a refused undo reloads the inbox, an undo that failed offline does not`: the CR-1 fix's undo path. A CONFLICT undo reloads the Inbox, an OFFLINE undo does not. Before this, only the completion side of that rule had a test.
- `an undo is reloaded only once the server answered it`: no reload while the undo is pending (the task stays hidden), and one reload after `Reopened`, after which the task shows again. A `CompletableDeferred` controls the order.

## Requirement coverage

Focused command (F): `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.inbox.*' --tests 'ch.sonensei.cockpit.newitems.*' createDebugUnitTestCoverageReport --rerun-tasks`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: Inbox rows have a complete checkbox | InboxScreenTest `every row has an unchecked complete checkbox labelled with its title that completes it`, `offline every row checkbox is disabled and completes nothing`, `processing mode shows the current item without the list rows and their checkboxes`; InboxRouteTest `the checkbox of a row completes the task and the snackbar offers undo`, `offline the row checkbox is disabled`; InboxViewModelTest `completing a task from the list hides it and offers undo`, `completing is ignored while offline`, `a second tap on a task being completed sends one completion`, `a task being completed is hidden before the server answers and comes back if it refuses`, `the not yet reloaded inbox keeps a completed task hidden, a newer version of it shows again`, `a task completed from the list is not offered when processing starts` | F | Pass |
| AC1: the same undo snackbar as Next | InboxScreenTest `the undo snackbar after completing from the list reopens the task`, `an undo offer that times out is dismissed, not undone`, `a refused completion is explained in the snackbar`, `the completion snackbar also shows while processing`; InboxViewModelTest `undoing a completion shows the task again`, `a refused completion brings the task back with the reason`, `a refused undo keeps the task hidden and says why`, `dismissing an older snackbar keeps the newer one` | F | Pass |
| AC1 / CR-1: the Inbox reloads after a complete or undo reaches the server | InboxViewModelTest `complete, undo and complete again sends the version of the reloaded inbox`, `a refused completion reloads the inbox, an offline one does not`, `the inbox is reloaded only once the server answered`, `a refused undo reloads the inbox, an undo that failed offline does not` (new), `an undo is reloaded only once the server answered it` (new) | F | Pass |
| AC1: tapping a row opens the task detail | InboxScreenTest `tapping a row opens its task detail, the checkbox does not`, `offline a row still opens its task detail`, `outside the shell a row has no tap target`, `in the shell a row announces that it opens the task`; InboxRouteTest `tapping a row opens the task detail with the list's copy` | F | Pass |
| AC2: a New view task row opens the task detail, and Back returns with the filter and scroll position kept | NewRouteTest `inTheShellATaskOpensTheDetailAndBackReturnsWithFilterAndScrollPosition`, `tappingATaskOpensTheTaskDetailWithTheListsCopy`, `withoutTheShellsOpenerATaskTapKeepsTheListAndOpensNoSheet`; NewScreenTest callback-order test (`openTask <id>`) | F | Pass |
| AC2: the read-only `NewTaskView` is removed | Removed with its tests (`NewTaskView`, `NewOpen.Task`, `NewUiState.inbox/task()`); NewStoresTest `malformedOpenValuesAreIgnored` (an old saved `task:<uuid>` is ignored) | F | Pass |
| AC3: new strings in English and German | No strings were added. The reused `tasks_complete`/`tasks_completed`/`tasks_undo` exist in both locales, and the two removed strings were removed from both. Android lint `MissingTranslation` enforces this. | Not run here (lintDebug is part of the parent's `scripts/check.sh` / `./gradlew ... lintDebug`) | Not run: the parent runs lint |
| AC4: unit and Compose tests, ktlintCheck, testDebugUnitTest (inbox, newitems) | All of the above | F | Pass (ktlintCheck ok; 229 tests in inbox + newitems, 0 failures, 0 skipped) |
| AC4: full `testDebugUnitTest lintDebug` and `sh scripts/check.sh` | - | Not run | Not run: the parent asked to run `scripts/check.sh` itself; the full Android suite was not run in this pass |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.inbox.*' --tests 'ch.sonensei.cockpit.newitems.*'` (before the new tests), then the same command with `createDebugUnitTestCoverageReport --rerun-tasks` (with the new tests)

Result:

Pass. Results from `android/app/build/test-results/testDebugUnitTest`: InboxViewModelTest 43, InboxScreenTest 32, InboxRouteTest 7, InboxRepositoryTest 35, InboxActionsTest 11, InboxUserLabelsTest 4, InboxGraphTest 2, NewViewModelTest 44, NewScreenTest 23, NewModelsTest 13, NewStoresTest 9, NewRouteTest 4, NewScreenInAppTest 2. Total 229, 0 failures, 0 errors, 0 skipped.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` (full)

Result:

Not run in this pass. `fixes.md` records an earlier full pass on the same production code. The parent runs `scripts/check.sh`.

## .taskcheck

Result:

Not run (the parent said it runs `sh scripts/check.sh` itself).

## Bugs found

None.

Observation (not a finding, no test kept): `InboxViewModel.refreshInboxAfter` decides "offline, skip the reload" from the shared `completer.state.snackbar` at the moment the task leaves `pending`. Because `StateFlow` conflates, a waiter that resumes late could in principle read a newer OFFLINE snackbar that belongs to a different task and skip one reload. On `Dispatchers.Main.immediate`, the waiter resumes before the next network callback is handled, so this cannot happen in practice. A missed reload also heals with the next TASK change event. Not reported as a defect.

## Coverage

Status: verified (fresh run on the snapshot below, exit successful, all selected tests passed)

Metric: whole-file coverage of task-changed production files (counters from inbox + newitems tests only; other packages' tests were not part of this run).
Changed-line coverage: measured. JaCoCo `<line>` entries (executable lines only) intersected with the added/modified lines of `git diff -U0 db8cd5ded..HEAD -- android/app/src/main/java` (script `.tmp/tw_changed_lines.py`, Git-ignored). Result: 68/68 changed executable lines covered (100%). Branches on changed lines: 32/34 (94.1%).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-inbox-rows; base db8cd5ded04a2d1cb51257378035dff4eeeed45c (from the parent); HEAD 47e4bbcc0abed9b2ece97daa2121c2355f76a01a; task-owned commits 03fe305b6, 2b869379b, 47e4bbcc0 (`git log db8cd5ded..HEAD`; fc1e3c58c only touches `tasks/`)
- Source/test snapshot identifier (including uncommitted inputs): HEAD 47e4bbcc0 + uncommitted `InboxViewModelTest.kt` sha256 `98bf9a821fe629d8bfe4a169c6841fb2dc0b4c804391157bc4a3c91b5b48029a` (same hash before and after the run). The uncommitted `reviews/and-inbox-rows/review.md` change is not mine and is not a test input.
- Exact included files; renamed/deleted/excluded files and reasons: included `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxScreen.kt`, `.../gtd/inbox/InboxViewModel.kt`, `.../newitems/NewScreen.kt`, `.../newitems/NewViewModel.kt`. Excluded `android/app/src/main/res/values/strings_new.xml` and `values-de/strings_new.xml` (resources only, lines removed, nothing executable). No renames or deleted source files.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (Gradle `jacoco` plugin, version from the project's build); `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.inbox.*' --tests 'ch.sonensei.cockpit.newitems.*' createDebugUnitTestCoverageReport --rerun-tasks -q`; working directory `android/`; selection: the inbox and newitems test packages
- Run start/completion time and exit status: 2026-11-08T09:51:14Z to 09:52:59Z (report written 09:52:54Z). Build successful: no failure output from Gradle `-q`, and every test-result XML shows 0 failures. The exit code itself was hidden by an output filter pipe.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 `8648d6ec475db1168faca79d608e876413dc4454fbe9f2a78e2a39440d8f9d76`
- Evidence outputs are fresh and inputs stayed unchanged: yes. `--rerun-tasks` forced new test and report tasks, the report mtime falls inside the run window, and the test-file hash did not change.
- Final run after latest test additions and production fixes: yes (this run includes the CR-1 fix and the 2 new tests)

Values below use covered/total (percentage); "Statements" are JaCoCo INSTRUCTION counters.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxScreen.kt` | 4823/4886 (98.7%) | 124/136 (91.2%) | 103/119 (86.6%) | 395/395 (100.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxViewModel.kt` | 1549/1563 (99.1%) | 116/119 (97.5%) | 69/70 (98.6%) | 159/159 (100.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/newitems/NewScreen.kt` | 3486/3687 (94.5%) | 128/145 (88.3%) | 60/71 (84.5%) | 272/278 (97.8%) |
| `android/app/src/main/java/ch/sonensei/cockpit/newitems/NewViewModel.kt` | 1206/1206 (100.0%) | 68/68 (100.0%) | 51/51 (100.0%) | 142/142 (100.0%) |
| **Aggregate (summed counters, 4 files, none unmeasured)** | 11064/11342 (97.5%) | 436/468 (93.2%) | 283/311 (91.0%) | 968/974 (99.4%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `InboxViewModel.kt` undo path of `refreshInboxAfter` (offline vs. refused undo, waiting while the undo is pending) | AC1 / CR-1: the Inbox reloads after an undo reaches the server, not after an offline failure | Added the 2 tests above. All changed lines were already hit, but the undo-side decisions were never asserted. |
| `InboxScreen.kt:120-122` (default lambdas `complete`/`undo`/`dismissSnackbar` of `InboxCallbacks`) | None: the default no-op bodies are never invoked | Justified gap |
| `InboxScreen.kt:290` (a non-null but blank `notesMd` on an Inbox row: 1 of 2 branches) | Row preview hides blank notes. The logic was moved unchanged from the old row. | Justified gap (pre-existing display logic, low risk) |
| `InboxViewModel.kt:113` (Hilt `@Inject` secondary constructor) | DI wiring, not exercised by JVM unit tests | Justified gap (the Hilt graph is compiled by `hiltJavaCompileDebug`) |
| `NewScreen.kt:169` (default lambda `openTask` of `NewCallbacks`) | None | Justified gap |
| Other missed lines in `InboxScreen.kt` (e.g. 95, 237, 315, 347, 384-392, 483, 517, 572-575) and `NewScreen.kt` (e.g. 76-80, 115, 130-131, 148-174, 197, 203, 269, 278, 413, 432, 465-471) | Code this task did not change (Hilt entry composables, default arguments, date pickers, the mail-open path) | Outside the task's changed lines; no test added |

## Unable to test

- AC3 / AC4 lint (`lintDebug`, `MissingTranslation`) and the full `scripts/check.sh` were not run in this pass, because the parent runs them. Follow-up: the parent records them.
- On-device or instrumented behavior (a real Activity back press, real `hiltViewModel` scoping) is covered only through Robolectric Compose tests inside `CockpitShellFrame` with a `viewModel {}` scoped to the back-stack entry. No emulator was used.

## Summary

Tests added and all validation passed. Two view-model tests close the one real gap: the undo half of the CR-1 Inbox reload. Task-scoped coverage is high (100% of changed executable lines), and no production defects were found. The full Android suite, lint and `scripts/check.sh` remain for the parent.
