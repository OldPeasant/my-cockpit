# Fix Report

Date: 2026-10-21

Task: and-gtd-data - Android GTD repositories and read cache

Scope: current task; findings CR-1..CR-5 of `reviews/and-gtd-data/review.md`. The fixes are in commit 82f1aea6, which an earlier fixer run left uncommitted when it was interrupted; the parent worker committed it. This run reviewed that commit against each finding, found it correct and complete, and ran the full validation. This run changed no further code.

## Fixed findings

### CR-1 — Concurrent refreshes of the same view can overwrite newer data with an older answer

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/data/GtdRefresh.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/data/GtdRepositories.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/data/GtdCacheConsistencyTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/data/GtdTestSupport.kt`

Problem:
Refreshes of one view, from the worker, the collectors and SSE, could overlap. An older load that finished later overwrote a newer one.

Fix:
`GtdConnectivity.refreshing(view, load, store)` holds one `Mutex` per view key (the `TaskView` name, `PROJECTS`, `CONTEXTS`) around load and store. Loads of a view therefore run one after another, and the load that started last writes last. All three repositories now use it. `RoutingDispatcher.beforeAnswer` was added to the test support so a test can hold back a server answer.

Validation:
- `GtdCacheConsistencyTest."an older load of a view never overwrites a newer one"`: the first answer is held back while a second refresh starts, and the cache ends with the newer data — Pass

### CR-2 — A refresh in flight during logout can refill the cleared cache with the previous user's data

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/data/GtdRefresh.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/data/GtdDataModule.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/data/GtdCacheConsistencyTest.kt`

Problem:
A load that answered after the logout clear still reached `replace*`, which wrote the previous session's data back into `gtd_cache.db`.

Fix:
A shared write lock now guards every cache write and the logout clear. Each clear increments a clear counter. `refreshing` reads the counter before it loads and checks it again under the write lock before it stores. If a clear happened in between, it throws the private `SessionEnded`, which maps to `RefreshResult.Failed(null)`, and the answer is dropped. The `SessionCache` runs `clearAllTables()` through `connectivity.clearing { }`, which then resets the offline flag. The answers that get dropped (the previous session's) are never logged.

Validation:
- `GtdCacheConsistencyTest."a load still running at logout does not refill the cleared cache"`: the load is held back over `SessionCache.clear()`, the result is `Failed`, the rows and the meta row stay empty, and a load started after the logout is stored again — Pass

### CR-3 — The SSE refresh tests pass even when change events have no effect

Files changed:
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/data/GtdChangeStreamTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/data/GtdRefreshTriggersTest.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/data/GtdChangeStream.kt` (`stopTimeoutMs` constructor parameter, so the stop timeout can be tested)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/data/GtdTestSupport.kt` (harness passes `stopTimeoutMs`)

Problem:
Every connection emitted `Connected`, and `Connected` caused a reload. A second load therefore happened whether or not change events worked, so the tests could not catch a regression in the change type filter. The stop timeout was also untested.

Fix:
With CR-4 in place, the first connection no longer reloads, so the tests can count loads exactly. `GtdChangeStreamTest.loadsWith` holds back the stream's single connection until every observed view has finished its first load. It then delivers the change events on that open stream, refuses every later connection (so no reconnect reloads anything), and counts the API calls per path. The assertions are exact:
- a TASK change gives inbox loads = 2;
- CONTEXT, PROJECT and AREA changes, plus the first connection, give inbox loads = 1;
- a CONTEXT change gives contexts 2 and projects 1;
- a PROJECT change gives contexts 1 and projects 2;
- a TASK change gives projects 2 and contexts 1.

The `GtdRefreshTriggersTest` checks count trigger emissions exactly in the same way, including a `Reconnected` reload. A new test, "the connection is closed once the last collector left for the stop timeout", checks that the stream keeps reconnecting during a 500 ms stop timeout and opens no new connection after it.

Validation:
- `./gradlew :app:testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.data.*'`: GtdChangeStreamTest 9/9, GtdRefreshTriggersTest 6/6 — Pass. The suite was run 3 times in total (two extra `--rerun` runs) with no flaky failure.

### CR-4 — Every screen start loads its view twice

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/data/GtdChangeStream.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/data/GtdRefresh.kt`

Problem:
The `Connected` signal that comes right after the stream opens started a second full load on every screen start.

Fix:
The stream now emits `ChangeSignal.Connected` for its first connection after it starts and `ChangeSignal.Reconnected` for every later connection, after a refusal, a drop or the server ending it. `GtdRefreshTriggers.on` reloads on `Reconnected` and on matching `Changed` signals only. The load that `flowOf(Unit)` runs at once covers the first connection. Reloading after a reconnect is kept, because the server does not replay changes missed while disconnected. The stream is shared, so a screen that starts while the stream is already open gets no signal, and its own load at start covers it.

Validation:
- `GtdChangeStreamTest."stream reconnects after the server ends it or refuses it"` expects `Connected, Changed, Reconnected, Changed` — Pass
- `GtdRefreshTriggersTest."a view reloads after a reconnect, which may have missed changes"` and "a view reloads at start and on its own change types only" (the first connection does not reload) — Pass
- `GtdChangeStreamTest."other change types and the first connection do not reload the inbox"` (exactly 1 load) — Pass

### CR-5 — A local database failure during refresh breaks the screen's flow

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/data/GtdRefresh.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/data/GtdCacheConsistencyTest.kt`

Problem:
An exception from a Room write, such as `SQLiteFullException`, escaped `refreshing` and cancelled the `observe()` flow.

Fix:
`refreshing` now catches `android.database.sqlite.SQLiteException` (which covers `SQLiteFullException`) and maps it to `RefreshResult.Failed(null)`. The `@Transaction` replace leaves the cache as it was, and the offline flag is not changed.

Validation:
- `GtdCacheConsistencyTest."a failing cache write keeps the cache and the observed view"`: a SQLite trigger aborts inserts. The test checks that:
    - `refresh` returns `Failed`;
    - the cached rows are kept;
    - an `observe()` collector stays active and keeps showing the cached rows;
    - after the trigger is dropped, a refresh succeeds and the observer sees the new rows.

  Result: Pass

### Related change: rows and meta are read together

This is part of the same WIP and not a separate finding. `cachedView` used to combine two independent Room flows, one for the rows and one for the meta row. It now re-reads rows and meta together in a `@Transaction` DAO method (`taskRows` / `projectRows` / `contextRows`) on each change of the meta row. Every replace and the logout clear write or remove the meta row. As a result, a screen never sees new rows with a missing or old refresh time. Covered by `GtdCacheConsistencyTest."loaded rows are never shown without their refresh time"` — Pass. The Room schema did not change: these methods are queries only, and no schema JSON changed.

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew :app:testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.data.*' --rerun` — Pass, run twice in addition to the full run. The 10 classes and 69 tests are:
    - GtdCacheConsistencyTest 4
    - GtdChangeStreamTest 9
    - GtdRefreshTriggersTest 6
    - TaskRepositoryTest 12
    - ProjectContextRepositoryTest 8
    - GtdSyncTest 9
    - GtdDataGraphTest 5
    - GtdRefreshSupportTest 5
    - GtdMappersTest 6
    - SseParserTest 5

Full test suite:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` — BUILD SUCCESSFUL. Lint report: 0 warnings, 0 errors.

Lint/type-check/build:
- Included above. `ktlintFormat` was not needed because ktlintCheck was clean. No lint suppressions were added.

`.taskcheck`:
- `sh scripts/check.sh` — Pass ("all checks passed"). The web bundle budget warning comes from `web/` and is unrelated to this task.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
