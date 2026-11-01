# Test Report

Date: 2026-11-01

Task: and-trash - Android: Trash (list, restore, delete forever)

Scope: `57f2ae75c1ea16aa42c195aa0a665e14502f4519..8e2caf1fd2c59e486b76bed3815c0f1f0fa14da5` (branch `main`,
base = merge-base with origin/main given by the parent; task commits f442e23c, 2c91040c, b7db7f51, d6e92bf8,
0527b945, 576ba919 merged in 8e2caf1f), plus one uncommitted test change from this run
(`TrashRepositoryTest.kt`, sha256 `f58606473cba388ffa098784195ba12cf4bdd6d343ad74a2d8af6b8ef496f477`).

Requirements:
- `tasks/and-trash.md` acceptance criteria and recorded assumptions
- `SPEC.md` (Trash, §4.2), `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/trash/TrashRepositoryTest.kt` (this run)

Already in the task scope from the earlier, interrupted test-writer run and the implementation (reviewed, kept unchanged):
`TrashModelsTest.kt`, `TrashRepositoryTest.kt`, `TrashRouteTest.kt`, `TrashScreenTest.kt`, `TrashViewModelTest.kt`
(all in `android/app/src/test/java/ch/sonensei/cockpit/trash/`), `ui/CockpitShellTest.kt`, `ui/navigation/CockpitDestinationTest.kt`.

## Tests added or updated

Count: 2 (this run), in `TrashRepositoryTest`:
- `an id that is no UUID fails the load` - a malformed id from the server is a failure (`Failed(null)`), not a crash
  or a partial list (covers the `IllegalArgumentException` branch of the repository).
- `a load answered without a body is a failure, not an empty Trash` - a 2xx answer without a body must not show
  "Trash is empty" (covers the `body == null` branch).

The earlier run's unverified test `the Trash loads again when the app comes back to it` (TrashRouteTest) was run
and passes: `TrashRoute` reloads on resume via `LifecycleResumeEffect`.

Trash tests now: 69 (Models 5, Repository 21, Route 7, Screen 19, ViewModel 17) plus the 2 shell/navigation tests.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. Trash reachable from More; lists deleted items of all types with type icon, title, deletion date, days until purge | `CockpitShellTest.trashOpensFromTheMoreListAndBackReturnsToIt`, the More list test listing "Trash", `CockpitDestinationTest` (moreItems order); `TrashScreenTest`: `rows show type, title, deletion date and days until purge`, `an item due today, tomorrow or soon says so`, `an item of an unknown type is labelled as an item`; `TrashModelsTest`: day rounding, type labels for every server type; `TrashRepositoryTest`: `loads every page and maps the items`, paging limit, de-duplication by type+id, malformed answers; `TrashRouteTest`: `the Trash loads when it is shown` | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` | Pass |
| 2. Restore and delete forever per item; delete forever confirms; restored item back in its list | `TrashViewModelTest`: `restore removes the item and confirms it`, `delete forever waits for the confirmation`, `confirming closes the dialog at once, confirming nothing does nothing`, failure / 404 / double-tap / concurrent / load-race tests; `TrashScreenTest`: `restore and delete forever report the item`, `delete forever asks for confirmation`, `the confirmation names the item`, `a busy item's actions are disabled`; `TrashRouteTest`: `restore takes the item out of the list`, `delete forever runs only after the confirmation`; `TrashRepositoryTest`: restore/purge endpoints, `restoring a task or project reloads the GTD cache, other types do not`, background reload tests | same | Pass |
| 3. Loading, empty and error states like the existing list screens | `TrashScreenTest`: `loading shows the progress bar and no empty text`, `an empty Trash says so`, `a failed load says why and retries`, `offline with loaded items says they are the last loaded ones`, `a refused load says so, and retry waits while a load runs`, `nothing is claimed before the first load`, pull-to-refresh tests; `TrashViewModelTest`: `loads the Trash and shows loading meanwhile`, `a failed load keeps the last items and says why`, `a first load that fails shows the error, and retry loads the Trash`; `TrashRouteTest`: `retry after a failed load shows the items`; `TrashRepositoryTest`: `a load answered without a body is a failure, not an empty Trash` | same | Pass |
| 4. All new UI strings in English and German | `TrashModelsTest.every Trash string and plural exists in German with the same placeholders`, `TrashScreenTest.the rows are German on a German phone`; lint `MissingTranslation` in `lintDebug` | same | Pass |
| 5. Unit tests cover the criteria; `ktlintCheck testDebugUnitTest lintDebug` passes | all of the above | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` (no other build running in this clone) | Pass (BUILD SUCCESSFUL, 2549 tests, 0 failed, 0 skipped) |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.trash.*' --tests 'ch.sonensei.cockpit.ui.*' createDebugUnitTestCoverageReport` (before adding tests: 130 tests, 0 failed), then
`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.trash.TrashRepositoryTest'` (after: 21 tests, 0 failed)

Result:

Pass

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport`

Result:

Pass - BUILD SUCCESSFUL in 4m 36s; JUnit XML: 2549 tests, 0 failures/errors, 0 skipped. Server and web suites
were not run (task touches only `android/`).

## .taskcheck

Result:

Not run: `.taskcheck` (`sh scripts/check.sh`) also builds server and web; the parent worker runs it before `done`,
and the parent asked not to start other builds concurrently. Its Android part (ktlint, unit tests, lint) passed above.

## Bugs found

None. No production defect was found; no test fails.

## Coverage

Status: verified (fresh JaCoCo run on the final code plus this run's test addition).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (the configured JaCoCo report has no diff mode; not reconstructed by hand).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-trash`; base
  `57f2ae75c1ea16aa42c195aa0a665e14502f4519` (given by the parent); HEAD `8e2caf1fd2c59e486b76bed3815c0f1f0fa14da5`;
  task commits from the parent, checked against `git diff --stat 57f2ae75..HEAD` and the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 8e2caf1f + uncommitted
  `android/app/src/test/java/ch/sonensei/cockpit/trash/TrashRepositoryTest.kt` sha256
  `f58606473cba388ffa098784195ba12cf4bdd6d343ad74a2d8af6b8ef496f477` (same hash before and after the run; no other changes).
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/trash/{TrashModels,TrashRepository,TrashScreen,TrashViewModel}.kt`,
  `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt`, `android/app/src/main/java/ch/sonensei/cockpit/ui/navigation/CockpitDestination.kt`.
  Excluded: `res/values{,-de}/strings_trash.xml` (resources, no executable code; checked by tests and lint), test files,
  `reviews/`. No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`;
  `./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` in `android/`; full debug unit-test suite.
- Run start/completion time and exit status: 2026-11-01T18:00:14+01:00 - 18:04:52+01:00, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`
  (written 18:04:51, sha256 `17193f9fb6a87cce0d4da1d7a4e8af45774b04ede9b708ec616819d8c3fe7afb`), `index.html` alongside.
- Evidence outputs are fresh and inputs stayed unchanged: report mtime is inside the run window; test result XML
  timestamps 17:04 UTC; the snapshot hash matched before and after.
- Final run after latest test additions and production fixes: yes (production code unchanged since d6e92bf8, which is included).

Values below use covered/total (percentage).

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `trash/TrashModels.kt` | 162/162 (100%) | 24/24 (100%) | 11/11 (100%) | 34/34 (100%) |
| `trash/TrashRepository.kt` | 386/397 (97.2%) | 16/16 (100%) | 23/24 (95.8%) | 52/55 (94.5%) |
| `trash/TrashViewModel.kt` | 739/749 (98.7%) | 40/42 (95.2%) | 38/40 (95.0%) | 75/75 (100%) |
| `trash/TrashScreen.kt` | 2280/2410 (94.6%) | 59/77 (76.6%) | 48/56 (85.7%) | 195/199 (98.0%) |
| `ui/CockpitShell.kt` (2 lines changed) | 1562/1597 (97.8%) | 79/92 (85.9%) | 42/42 (100%) | 131/133 (98.5%) |
| `ui/navigation/CockpitDestination.kt` (2 lines changed) | 352/352 (100%) | 9/9 (100%) | 13/13 (100%) | 35/35 (100%) |
| **trash package (4 files, summed)** | 3567/3718 (95.9%) | 139/159 (87.4%) | 120/131 (91.6%) | 356/363 (98.1%) |
| **all 6 files (summed)** | 5481/5667 (96.7%) | 227/260 (87.3%) | 175/186 (94.1%) | 522/531 (98.3%) |

JaCoCo has no separate statement counter; instructions are shown instead. No scoped file is missing from the report.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `TrashRepository.kt:26` | Hilt `@Provides` for `TrashApi` | Left: DI glue, exercised only in a Hilt app graph; non-material |
| `TrashRepository.kt:100-101` | rethrow of `CancellationException` | Left: cancellation propagation; reaching it needs cancelling mid-request, and the view model's load-cancellation tests cover the user-visible effect |
| `TrashRepository.kt:108-109`, branch `:136` (before this run) | malformed id, 2xx without body | Covered now by the 2 tests added |
| `TrashViewModel.kt:139`, `:154` (1 branch each) | 404 check on a non-`Failed` result; `drop` with no load ever started | Left: compiler-generated null/safe-call branch combinations; behaviors are tested |
| `TrashScreen.kt:67-68, 75-78` | `TrashRoute` inside a real Hilt activity with `hiltViewModel()` | Left: Robolectric tests use a non-Hilt host (placeholder path tested) and inject the view model directly; the Hilt path runs only on a device |
| `TrashScreen.kt:111, 134, 141` | Kotlin default-argument synthetic branches | Left: non-material |
| `TrashScreen.kt:273, 277, 325` | exhaustive-`when` fallbacks over enums / sealed types | Left: unreachable `NoWhenBranchMatchedException` paths; every real case is tested |
| `CockpitShell.kt` remaining gaps | not in this task's 2 changed lines (the Trash route line is covered) | Left: outside task scope |

## Unable to test

- `TrashScreen` with the real Hilt-provided view model inside `MainActivity` (needs an instrumented device test; the
  project has no instrumented tests for similar screens). The route is tested with an injected `TrashViewModel`.
- Running on the owner's phone against the real server: not possible here; no follow-up needed for the criteria.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. Two repository tests were added for
malformed server answers; the focused tests and the full Android check (`ktlintCheck testDebugUnitTest lintDebug`)
pass.
