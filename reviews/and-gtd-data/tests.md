# Test Report

Date: 2026-10-21

Task: and-gtd-data - Android GTD repositories and read cache

Scope: final revision after the review fixes. `git diff e1895109d91ed61a589cf8a0fe113181e576c68a..6807ed131e9a7ba034f1fe36b7286d26f6bd696f -- android/app/src/{main,test}/java/ch/sonensei/cockpit/gtd/data/`, plus the uncommitted test changes listed below. Branch `main`, HEAD `6807ed131e9a7ba034f1fe36b7286d26f6bd696f`. The task commits on this branch are 1c2d5f2a (implementation), 49816b2c and 6f2de142 (tests and earlier reports), 8e477049 (fixes for CR-1..CR-5) and 6807ed13 (fixer report). The parent named them by their pre-rebase hashes 4804964f, d9bfa451, 8e7a8468 and 82f1aea6, and `git log` subjects match them one to one. Other commits in the range only touch `tasks/` (heartbeats and claims of other agents) and are not part of the scope.

Requirements:
- `tasks/and-gtd-data.md` acceptance criteria and the six recorded assumptions
- SPEC.md §9.3 (offline read cache), as the task references it
- `reviews/and-gtd-data/review.md` (CR-1..CR-5) and `reviews/and-gtd-data/fixes.md`, for the behavior the fixes added
- `docs/ARCHITECTURE.md` "Coverage"; `AGENTS.md` project notes (fictitious test data, ktlint)
- Parent instructions: re-run on the final code and check that per-view load serialization, the logout clear against an in-flight load, the Connected and Reconnected signals, SQLiteException handling and the consistent rows+meta read are tested

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/gtd/data/GtdCacheConsistencyTest.kt` (3 tests added)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/data/GtdChangeStreamTest.kt` (2 tests added)

No existing test was changed, weakened or removed. No production file was modified.

## Tests added or updated

Count: 5 added. The package `ch.sonensei.cockpit.gtd.data.*` goes from 69 to 74 tests.

Review of the existing tests for the fixed behavior. Each fix already had a direct test:
- CR-1 per-view serialization: `GtdCacheConsistencyTest."an older load of a view never overwrites a newer one"`
- CR-2 logout against an in-flight load: `"a load still running at logout does not refill the cleared cache"`
- CR-3 exact reload counts and the stop timeout: `GtdChangeStreamTest` `loadsWith` tests (exact counts), `"the connection is closed once the last collector left for the stop timeout"`, and the exact counts in `GtdRefreshTriggersTest`
- CR-4 Connected vs Reconnected: `"stream reconnects after the server ends it or refuses it"`, `GtdRefreshTriggersTest."a view reloads after a reconnect..."` and `"...at start and on its own change types only"`
- CR-5 SQLiteException: `"a failing cache write keeps the cache and the observed view"`
- Consistent rows+meta read: `"loaded rows are never shown without their refresh time"`

The gaps that were still open are covered by the added tests:
- `GtdCacheConsistencyTest."a load of one view does not wait for a load of another view"`: the inbox answer is held back, and a contexts refresh must still finish within 3 s and be stored. The locks are per view, so a slow view must not block the others. A single global lock would fail this test.
- `GtdCacheConsistencyTest."a failing cache write leaves the offline flag as it is"`: with offline = true, a refresh whose Room insert is aborted by a SQLite trigger returns `Failed(null)`. The flag stays true and no meta row is written. This follows the documented contract that `Failed` leaves the flag alone, as stated in fixes.md CR-5.
- `GtdCacheConsistencyTest."an observed view shows the logout as an empty, never loaded view"`: an `observe(INBOX)` collector that shows loaded rows receives, after `SessionCache.clear()`, an emission with no rows, `refreshedAt = null` and `offline = false`. This checks that the meta-driven re-read also reacts to `clearAllTables()`.
- `GtdChangeStreamTest."a stream that opens only after being refused signals a reconnect, so views loaded offline reload"`: when the first attempt is refused, the first successful connection is `Reconnected` and not `Connected`. A view that loaded while the server was down therefore reloads once the server is back.
- `GtdChangeStreamTest."a stream restarted after the stop timeout signals its first connection as connected again"`: the first run gives `Connected, Changed(TASK), Reconnected`. After the shared stream stopped (stop timeout 100 ms), a new collector gets `Connected` again, so a screen start after the stream stopped does not load twice (no CR-4 regression).

## Requirement coverage

All rows below were run by `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.data.*' ktlintCheck` (74 tests, 0 failures). They were run again by `./gradlew createDebugUnitTestCoverageReport` and by `sh scripts/check.sh`.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: repositories for tasks/projects/contexts | TaskRepositoryTest (12), ProjectContextRepositoryTest (8), GtdDataGraphTest `the graph provides the repositories...` | focused command above | Pass |
| AC1: Inbox/Next/Today/Projects cached in Room | TaskRepositoryTest `refresh stores...`, `views are cached separately`, `today is overdue then due today...`; ProjectContextRepositoryTest `projects of every status...`; GtdMappersTest (6) | same | Pass |
| AC1: served read-only with an offline flag when the server is unreachable | TaskRepositoryTest `unreachable server keeps the cache...`, `offline without anything cached...`, `a server error counts as unreachable`; ProjectContextRepositoryTest `an unreachable project server...`; GtdRefreshSupportTest (classification, flag) | same | Pass |
| AC1 (CR-1): the load that started last writes last; views do not block each other | GtdCacheConsistencyTest `an older load of a view never overwrites a newer one`, `a load of one view does not wait for a load of another view` (new) | same | Pass |
| AC1 (CR-2): logout drops the cache and in-flight answers | GtdCacheConsistencyTest `a load still running at logout does not refill the cleared cache`, `an observed view shows the logout as an empty, never loaded view` (new); GtdSyncTest `logout clears the cache, the offline flag and the periodic sync` | same | Pass |
| AC1 (CR-5): a local DB failure keeps the cache, the screen flow and the offline flag | GtdCacheConsistencyTest `a failing cache write keeps the cache and the observed view`, `a failing cache write leaves the offline flag as it is` (new) | same | Pass |
| AC1: rows and refresh time are consistent | GtdCacheConsistencyTest `loaded rows are never shown without their refresh time` | same | Pass |
| AC2: periodic sync (WorkManager, 15 min) | GtdSyncTest (9), GtdDataGraphTest `the worker created by WorkManager's default factory...` | same | Pass |
| AC2: foreground SSE refresh (change types, reconnect reload, no double load at start, stop timeout) | GtdChangeStreamTest (11, 2 new), GtdRefreshTriggersTest (6), SseParserTest (5), GtdDataGraphTest `the change stream uses the configured server...` | same | Pass |
| AC3: tests cover cache fallback when offline and refresh replaces the cache | TaskRepositoryTest `refresh replaces the cached view...`, `a failing page leaves the earlier cache whole`, offline rows above; ProjectContextRepositoryTest replace tests | same | Pass |
| AC3: Robolectric/Compose UI tests for screens | none in this task | n/a | Not testable here. Recorded assumption #6 assigns the screens to and-inbox, and-next-today and and-projects-lists, and no screens are in this task's touches |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.data.*' ktlintCheck`

Result:

Pass: BUILD SUCCESSFUL, run 2026-10-21T00:38:47+02:00 to 00:39:26+02:00. The result XMLs list 10 classes and 74 tests, with 0 failures, 0 errors and 0 skipped:
- GtdCacheConsistencyTest 7
- GtdChangeStreamTest 11
- GtdRefreshTriggersTest 6
- TaskRepositoryTest 12
- ProjectContextRepositoryTest 8
- GtdSyncTest 9
- GtdDataGraphTest 5
- GtdRefreshSupportTest 5
- GtdMappersTest 6
- SseParserTest 5

ktlintCheck is clean. The new tests also passed in an earlier focused run of the two changed classes, in the coverage run and in `.taskcheck`, so they passed in 4 runs in total with no flaky failure.

## Full test execution

Command:

`cd android && ./gradlew createDebugUnitTestCoverageReport` (runs the whole Android debug unit suite)

Result:

Pass: BUILD SUCCESSFUL. 311 tests, 0 failures, 0 errors, 0 skipped (summed over all result files).

## .taskcheck

Result:

Pass: `sh scripts/check.sh` printed "== all checks passed" for the server, web and android parts.

## Bugs found

No production defect that violates the acceptance criteria, the SPEC or the fixes' stated contracts was found. All new tests pass.

The earlier low-severity observation TW-1 (an emission with rows but `refreshedAt = null`) is resolved by the consistent rows+meta read. `"loaded rows are never shown without their refresh time"` covers it.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: equal to whole-file line coverage. All eight files were added by this task (`git diff --name-status e1895109..HEAD -- android/app/src/main` lists every file as `A`), so every executable line in them is a task-added line. The tooling did not compute it separately.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-gtd-data`. Base `e1895109d91ed61a589cf8a0fe113181e576c68a`, which the parent supplied and which is an ancestor of HEAD. HEAD `6807ed131e9a7ba034f1fe36b7286d26f6bd696f`. Task commits 1c2d5f2a, 49816b2c, 6f2de142, 8e477049 and 6807ed13. The scope comes from `git diff --name-status e1895109..HEAD -- android/app/src/main` and matches the task's touches.
- Source/test snapshot identifier (including uncommitted inputs): `sha256sum` of all `gtd/data/*.kt` main and test files (including the two modified test files), hashed again: `45e01b6b3a4c7918c88edba84e6a2798df61ec4572462019ea007fb8836926ba`. The hash was identical before the focused run and after the coverage and `.taskcheck` runs.
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/gtd/data/{GtdCacheDatabase,GtdChangeStream,GtdDataModule,GtdMappers,GtdModels,GtdRefresh,GtdRepositories,GtdSync}.kt`. Excluded: the Room schema `android/app/schemas/.../1.json`, which is generated and not executable. No files were renamed or deleted.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`. Command `./gradlew createDebugUnitTestCoverageReport` in `android/`, covering the whole debug unit suite.
- Run start/completion time and exit status: 2026-10-21T00:39:35+02:00 to 00:40:58+02:00, BUILD SUCCESSFUL, with no failures in any result file.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 `64c5c9b5d478fbef03388f89343a2f0c94f8b96bbac2e6e5228c1ac6ccf79a44`, mtime 2026-10-21 00:40:57 +0200.
- Evidence outputs are fresh and inputs stayed unchanged: the report mtime falls inside this run and after the last test edit, and the snapshot hash did not change.
- Final run after latest test additions and production fixes: yes. The run measures the post-fix code (8e477049) together with the five tests added here.

The values below are covered/total (percentage). "Statements" is the JaCoCo INSTRUCTION counter and "Functions" is METHOD. N/A means the denominator is zero.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `GtdCacheDatabase.kt` | 559/559 (100.0%) | N/A | 52/52 (100.0%) | 65/65 (100.0%) |
| `GtdChangeStream.kt` | 686/701 (97.9%) | 37/38 (97.4%) | 20/21 (95.2%) | 84/87 (96.6%) |
| `GtdDataModule.kt` | 73/78 (93.6%) | N/A | 8/9 (88.9%) | 10/11 (90.9%) |
| `GtdMappers.kt` | 535/535 (100.0%) | 42/42 (100.0%) | 6/6 (100.0%) | 78/78 (100.0%) |
| `GtdModels.kt` | 441/502 (87.8%) | N/A | 32/49 (65.3%) | 50/51 (98.0%) |
| `GtdRefresh.kt` | 465/469 (99.1%) | 17/18 (94.4%) | 15/15 (100.0%) | 73/73 (100.0%) |
| `GtdRepositories.kt` | 677/685 (98.8%) | 5/5 (100.0%) | 34/34 (100.0%) | 86/86 (100.0%) |
| `GtdSync.kt` | 261/261 (100.0%) | 10/10 (100.0%) | 9/9 (100.0%) | 37/37 (100.0%) |
| **Total (summed counters; all 8 files measured, none missing or excluded)** | 3697/3790 (97.5%) | 111/113 (98.2%) | 176/195 (90.3%) | 483/488 (99.0%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `GtdChangeStream.kt:84-85` | `CancellationException` rethrown from `readStream` | Non-material defensive path. Cancellation normally arrives in `delay`. The stop-timeout and restart tests show that the loop stops and restarts correctly |
| `GtdChangeStream.kt:79` (1 of 2 branches), `:95` | `while (isActive)` exits through its condition | Non-material: the loop ends through cancellation, not through the condition |
| `GtdRefresh.kt:97` (1 of 2 branches) | exhaustive `when` over the sealed `RefreshResult`, compiler-generated fallthrough | Non-material: unreachable |
| `GtdModels.kt:64` and other generated members (17 methods) | data-class `copy`/`componentN`/`hashCode` of the models | Non-material: generated code with no behavior |
| `GtdDataModule.kt:20` | constructor of the abstract Hilt module `GtdDataBindings` | Non-material: Dagger never instantiates it |
| `GtdRepositories.kt` (8 instructions, no line) | partial instructions of the coroutine builders in `cachedView` | Non-material |

## Unable to test

- Criterion 3 "Robolectric/Compose UI tests for screens": no screens exist in this task (recorded assumption #6). They belong to the screen tasks.
- The real 15-minute periodic run of WorkManager on a device: Robolectric verifies only the enqueued request (interval, KEEP, network constraint) and the worker's `doWork`.
- A real `SQLiteFullException` (device out of space) is simulated by a SQLite `RAISE(ABORT)` trigger. Both raise an `android.database.sqlite.SQLiteException` subclass inside the `@Transaction`.
- Observation, not tested and not a defect against any stated requirement: a load that is still running at logout and then fails with a network error or 5xx still sets the shared offline flag after the clear reset it (`GtdRefresh.kt:55-58`: `load()` throws before the clear-counter check). The next successful load clears the flag, and every screen start triggers that load at once. Low impact. The parent decides whether this is worth a follow-up.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. 5 tests were added for the fixed behavior: views do not block each other, the offline flag is unchanged by a cache write failure, an observer sees the logout clear, the first successful connection after a refusal is a reconnect, and a restarted stream signals Connected again. The package has 74 tests and the Android suite 311, all passing. ktlint and `.taskcheck` pass. Task-scoped coverage is 99.0% of lines and 98.2% of branches over all eight files. The remaining gaps are generated or unreachable code.
