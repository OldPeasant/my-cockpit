# Test Report

Date: 2026-10-21

Task: and-gtd-data - Android GTD repositories and read cache

Scope: `git diff c078c80e1b96a2a7b83308dc8f9b0bae55bc2298..dfcd2d89c78837b35ddef4e869044c86861de4be` (task commits fd0c3755 implementation, dfcd2d89 tests), plus the uncommitted test files listed below. Branch `main`, HEAD `dfcd2d89c78837b35ddef4e869044c86861de4be`.

Requirements:
- `tasks/and-gtd-data.md` acceptance criteria and the six recorded assumptions (`python tasks.py assumptions`)
- SPEC.md §9.3 (offline read cache), as referenced by the task
- `docs/ARCHITECTURE.md` "Coverage"; `AGENTS.md` project notes (fictitious test data, ktlint)
- Parent instructions (tests only inside `android/app/src/test/java/ch/sonensei/cockpit/gtd/data/`)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/gtd/data/GtdSyncTest.kt` (3 tests added)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/data/SseParserTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/data/GtdRefreshSupportTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/data/GtdMappersTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/data/ProjectContextRepositoryTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/data/GtdRefreshTriggersTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/data/GtdDataGraphTest.kt` (new; also declares a test-only Hilt `GtdDataGraphEntryPoint`)

No existing test was changed, weakened or removed. No production file was modified.

## Tests added or updated

Count: 36 added (23 existing -> 59 in `ch.sonensei.cockpit.gtd.data.*`).

- SseParserTest (5): colons in values / only one leading space removed, event name reset after dispatch, comments inside an event + unknown fields + repeated blank lines, empty `data:` value and last event name wins, value-less field.
- GtdRefreshSupportTest (5): `MAX_PAGES` guard (exactly 50 calls when the server always sends a cursor), single page, `HttpFailure` carries only the status (non-2xx and null body), classification of IO / 5xx / 4xx (499 boundary) / serialization / IllegalArgument / DateTime failures, offline flag left as is by a rejection, cleared by success, `reset()`.
- GtdMappersTest (6): task with all optional fields null, single context id, every API `list`/`energy`/`creativity` value has a domain value, project with all and with no optional fields, every API project status has a domain value, context without color.
- ProjectContextRepositoryTest (8): projects of every status across pages in server order (no status filter, limit 200), project refresh replaces, 503 keeps cache + shared offline flag reaches task views, 403 / unreadable keep cache without offline, contexts ordered and replaced, contexts 401 / unreadable / 500, contexts offline before anything loaded, observing projects loads on start.
- GtdRefreshTriggersTest (4): a view reloads only on start, Connected and its own change types (CONTEXT/PROJECT/AREA do not trigger TASK views); multi-type trigger (PROJECT + TASK) as used by projects; backoff escalates per failure and resets after a connection the server ended; failures beyond the list keep the last delay.
- GtdDataGraphTest (5, Hilt + Robolectric): graph provides TaskRepository/ProjectRepository/ContextRepository/GtdSync/WorkManagerGtdSyncScheduler and the `GtdSyncEntryPoint`; GtdConnectivity and TaskRepository are singletons; the multibound session caches include the GTD cache (clears the offline flag); `GtdSyncWorker` built by WorkManager's default factory resolves its sync through the entry point; the injected change stream uses the configured server URL and the access token.
- GtdSyncTest (+3): every view is still loaded after one fails, Offline wins over an earlier rejection, first rejection reported when reachable, successful sync reports Refreshed and clears offline.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: repositories for tasks/projects/contexts | TaskRepositoryTest (12), ProjectContextRepositoryTest (8), GtdDataGraphTest `the graph provides the repositories...` | `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.data.*'` | Pass |
| AC1: Inbox/Next/Today/Projects cached in Room | TaskRepositoryTest `refresh stores...`, `views are cached separately`, `today is overdue then due today...`; ProjectContextRepositoryTest `projects of every status...`; GtdMappersTest (6) | same | Pass |
| AC1: served read-only with an offline flag when the server is unreachable | TaskRepositoryTest `unreachable server keeps the cache...`, `offline without anything cached...`, `a server error counts as unreachable`; ProjectContextRepositoryTest `an unreachable project server...`, `contexts served offline...`; GtdRefreshSupportTest (classification, flag) | same | Pass |
| AC2: periodic sync (WorkManager, 15 min) | GtdSyncTest `scheduler enqueues one periodic 15 minute sync...`, `sync refreshes every cached view`, `sync retries while...`, new aggregation tests; GtdDataGraphTest `the worker created by WorkManager's default factory...` | same | Pass |
| AC2: foreground SSE refresh | GtdChangeStreamTest (5), GtdRefreshTriggersTest (4), SseParserTest (5), GtdDataGraphTest `the change stream uses the configured server...` | same | Pass |
| AC3: tests cover cache fallback when offline | see AC1 offline rows | same | Pass |
| AC3: tests cover refresh replaces cache | TaskRepositoryTest `refresh replaces the cached view...`, `a failing page leaves the earlier cache whole`; ProjectContextRepositoryTest `a project refresh replaces...`, `contexts are cached in server order and replaced...` | same | Pass |
| AC3: Robolectric/Compose UI tests for screens | none in this task | n/a | Not testable here: per recorded assumption #6 the screens belong to and-inbox / and-next-today / and-projects-lists; no screens are in this task's touches |
| Assumption #5: stream closed 5 s after the last collector | none | n/a | Not run: needs a 5 s real-time wait (`STOP_TIMEOUT_MS` is a constant, scope not injectable for virtual time); left as gap |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.data.*' ktlintCheck`

Result:

Pass: 59 tests, 0 failures, 0 skipped (9 classes); ktlintCheck clean. The timing-based classes (GtdRefreshTriggersTest, ProjectContextRepositoryTest, GtdDataGraphTest) were re-run twice more with `--rerun`: pass both times.

## Full test execution

Command:

`cd android && ./gradlew createDebugUnitTestCoverageReport` (runs the whole Android debug unit suite)

Result:

Pass: 296 tests, 0 failures, 0 errors, 0 skipped (32 result files).

## .taskcheck

Result:

Pass: `sh scripts/check.sh` exit 0, "== all checks passed" (server, web, android parts). The web build prints an existing bundle-budget warning unrelated to this task.

## Bugs found

No production defect that violates the acceptance criteria or SPEC was found. One low-severity observation, no failing test kept (it is a transient state, not a stated requirement):

### Cached emission can briefly show loaded rows with refreshedAt = null

Finding ID:

`TW-1`

Test:

`ProjectContextRepositoryTest.observing projects loads them once on start` (first version asserted `refreshedAt` on the first non-empty emission and failed once; the test now waits for both values)

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/gtd/data/GtdRepositories.kt:32` (`combine(rows, meta, connectivity.offline)`)

Requirement:

KDoc of `Cached` (`GtdModels.kt:66-70`): "`refreshedAt` is `null` when nothing was ever loaded for this view". Not an acceptance criterion.

Expected behavior:

After the first load of a never-loaded view, a screen sees the rows together with their refresh time.

Actual behavior:

Rows and meta come from two independent Room flows; after `replace*` the rows flow can emit before the meta flow, so one intermediate `Cached(data = [..], refreshedAt = null)` is emitted. A screen that shows "never loaded" for `refreshedAt == null` could flicker once.

Severity:

Low

Fix required:

`No for the acceptance criteria — optional: emit rows and meta from one query/transaction-consistent flow, or treat it as acceptable. Parent decides.`

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: equal to whole-file coverage here, because all eight files were added by this task (fd0c3755); every executable line in them is a task-added line. Not computed separately by tooling.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-gtd-data`; base `c078c80e1b96a2a7b83308dc8f9b0bae55bc2298` and commits fd0c3755, dfcd2d89 supplied by the parent; HEAD `dfcd2d89c78837b35ddef4e869044c86861de4be`; scope from `git diff --name-only c078c80e..HEAD -- android/app/src/main`, consistent with the task's touches.
- Source/test snapshot identifier (including uncommitted inputs): `sha256sum` of all `gtd/data/*.kt` main and test files, hashed again: `2d2315665fe06bce0e5e0ee945a954f8562f413623244869d6763734cfc142b9`, identical before and after the coverage run.
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/gtd/data/{GtdCacheDatabase,GtdChangeStream,GtdDataModule,GtdMappers,GtdModels,GtdRefresh,GtdRepositories,GtdSync}.kt`. Excluded: `android/app/schemas/.../1.json` (generated Room schema, not executable). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`; `./gradlew createDebugUnitTestCoverageReport` in `android/`; whole debug unit suite.
- Run start/completion time and exit status: completed 2026-10-20T22:14:10Z, exit 0 (no failures in any result file).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 `45ca91e16cf524a8f937ac70465d14d74e16902bccc9ff6afeb5d9ff3a4c053c`.
- Evidence outputs are fresh and inputs stayed unchanged: report mtime 22:14:10Z, after all test edits; snapshot hash unchanged.
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage); "Statements" is JaCoCo INSTRUCTION, "Functions" is METHOD; zero-denominator metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `GtdCacheDatabase.kt` | 428/428 (100.0%) | N/A | 46/46 (100.0%) | 59/59 (100.0%) |
| `GtdChangeStream.kt` | 637/652 (97.7%) | 35/36 (97.2%) | 19/20 (95.0%) | 80/83 (96.4%) |
| `GtdDataModule.kt` | 64/67 (95.5%) | N/A | 7/8 (87.5%) | 11/12 (91.7%) |
| `GtdMappers.kt` | 535/535 (100.0%) | 42/42 (100.0%) | 6/6 (100.0%) | 78/78 (100.0%) |
| `GtdModels.kt` | 444/502 (88.4%) | N/A | 33/49 (67.3%) | 50/51 (98.0%) |
| `GtdRefresh.kt` | 263/267 (98.5%) | 15/16 (93.8%) | 11/11 (100.0%) | 49/49 (100.0%) |
| `GtdRepositories.kt` | 647/655 (98.8%) | 5/5 (100.0%) | 30/30 (100.0%) | 82/82 (100.0%) |
| `GtdSync.kt` | 261/261 (100.0%) | 10/10 (100.0%) | 9/9 (100.0%) | 37/37 (100.0%) |
| **Total (summed counters, all 8 files measured, none missing)** | 3279/3367 (97.4%) | 107/109 (98.2%) | 161/179 (89.9%) | 446/451 (98.9%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `GtdModels.kt:29-64,89` | Kotlin-generated data class members (`copy`, `componentN`, `hashCode` of `GtdProject`/`GtdContext`/`RefreshResult.Failed`) | Non-material: generated code, no behavior |
| `GtdChangeStream.kt:76-77` | `CancellationException` rethrown from `readStream` | Non-material defensive path; cancellation of the collector is exercised indirectly by every stream test ending |
| `GtdChangeStream.kt:71,86` | `while (isActive)` exit branch | Non-material: the loop ends by cancellation thrown from `delay`, not by the condition |
| `GtdChangeStream.kt:102` | `awaitCancellation()` in the canceller | JaCoCo counts the suspend body as missed; the `finally { call.cancel() }` path runs when stream tests stop |
| `GtdChangeStream.kt:66,138` | lambda / `DEFAULT_RETRY_DELAYS_MS` partial instructions | Non-material; the production constructor is exercised by GtdDataGraphTest |
| `GtdRefresh.kt:47,50` | compiler-generated exhaustive `when` fallthrough on the sealed `RefreshResult` | Non-material: unreachable |
| `GtdRepositories.kt:31,35` | coroutine-builder partial instructions in `cachedView` | Non-material |
| `GtdDataModule.kt:20` | constructor of the abstract `GtdDataBindings` Hilt module | Non-material: never instantiated by Dagger |
| (behavior, not a line) assumption #5: stream closes 5 s after the last collector | foreground-only SSE connection | Not tested: a 5 s real wait would slow the suite; `STOP_TIMEOUT_MS` is a constant. Follow-up optional |

## Unable to test

- Criterion 3 "Robolectric/Compose UI tests for screens": no screens exist in this task (recorded assumption #6); belongs to the screen tasks.
- 5 s stop timeout of the shared SSE connection (assumption #5): requires a real-time wait or an injectable timeout; not covered. Low risk, optional follow-up.
- Real 15-minute periodic execution by WorkManager on a device: only the enqueued request (interval, KEEP, network constraint) and the worker's `doWork` are verified under Robolectric.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. 36 tests added (59 in the package, 296 in the Android suite), all passing; ktlint and `.taskcheck` pass. One low-severity observation (TW-1, transient `refreshedAt = null` emission) is reported for the parent to decide on; it does not violate an acceptance criterion.
