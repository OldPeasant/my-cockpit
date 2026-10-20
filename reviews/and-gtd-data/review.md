# Code Review

Date: 2026-10-21

Task: and-gtd-data - Android GTD repositories and read cache

Scope: `git diff c078c80e..HEAD` (commits fd0c3755 implementation, dfcd2d89 tests): `android/app/src/main/java/ch/sonensei/cockpit/gtd/data/`, `android/app/src/test/java/ch/sonensei/cockpit/gtd/data/`, `android/app/schemas/`. Uncommitted test changes by the concurrent test-writer were not reviewed.

Requirements:
- tasks/and-gtd-data.md acceptance criteria and recorded assumptions (#1-#6)
- SPEC.md §1 (Android WorkManager 15 min + SSE), §9.3 (read cache shown read-only with an "offline" banner)
- AGENTS.md project notes (no logging of secrets/contents, conventions)
- Existing code: core/network (NetworkModule, ServerUrlInterceptor, AuthSession), auth (SessionCache, AuthRepository), server SseEventHub (event name, comments)

Git range:
- Branch: main
- HEAD: dfcd2d89c78837b35ddef4e869044c86861de4be
- Base/start: c078c80e1b96a2a7b83308dc8f9b0bae55bc2298
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 3 P2, 2 P3

Checked with no findings:
- Hilt wiring: TasksApi and ProjectsApi are provided only here, there is one `GtdSyncScheduler` binding, and `SessionCache` is added `@IntoSet` to the existing `@Multibinds` set.
- Worker creation: the default WorkerFactory works because there is a public `(Context, WorkerParameters)` constructor plus an entry point, and the manifest still keeps the default WorkManager initializer.
- Periodic sync: unique periodic work with KEEP, 15 min and a CONNECTED network constraint.
- Room: the `@Transaction` replace methods delete rows, insert rows and stamp the meta row atomically. A failing page leaves the old cache intact.
- SSE client:
  - cancelling the collector cancels the OkHttp call;
  - `CancellationException` is rethrown;
  - server comments (`:connected`, `:heartbeat`) are skipped, and the `change` event name used by `SseEventHub` is handled;
  - the events path goes through `ServerUrlInterceptor` and the shared authenticated client.
- Logging: nothing logs exceptions, bodies or note contents.
- Logout: it clears all tables, cancels the sync and resets the offline flag.

Running `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.data.*' lintDebug` was not permitted in this review session. The existing results in `android/app/build/test-results/testDebugUnitTest/` (2026-10-20T22:04Z) show the three committed test classes passing (GtdChangeStreamTest 5/5, GtdSyncTest and TaskRepositoryTest with no failures). The reviewer did not re-run lint.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Concurrent refreshes of the same view can overwrite newer data with an older answer**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/data/GtdRepositories.kt:66`
    - Category: Correctness
    - Problem: `TaskRepository.refresh` loads from the network and then calls `dao.replaceTasks`, with nothing that serializes refreshes of the same view. `ProjectRepository.refresh` (line 116) and `ContextRepository.refresh` (line 156) work the same way. Refreshes of one view can run at the same time from independent sources:
        - `GtdSyncWorker` (`GtdSync.refreshAll`);
        - each `observe()` collector (Projects is observed by the Projects screen and by anything that shows a task's project; Inbox, Today and Projects all reload on `TASK` changes);
        - SSE triggers.

      A refresh that started earlier but finishes later (for example the worker's slow multi-page load) writes its older server snapshot over the result of a newer foreground refresh. Nothing corrects it until the next change event.
    - Why it matters: the screen silently shows a stale list (a just-completed task reappears, a new one is missing), and that stale copy is what is served offline.
    - Suggested fix: serialize loads per view inside the repository (e.g. a `Mutex` per `TaskView` or per repository around load + replace), so the load that started last always writes last. Alternatively, use a per-view request generation that discards the answers of superseded loads.

- [ ] **CR-2 | A refresh in flight during logout can refill the cleared cache with the previous user's data**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/data/GtdDataModule.kt:58`
    - Category: Correctness
    - Problem: the `SessionCache` calls `scheduler.cancel()` and `database.clearAllTables()`, but it does not coordinate with refreshes that are already running. A request sent with the old token that answers after the clear still reaches `dao.replaceTasks`/`replaceProjects`/`replaceContexts`. It then writes the previous session's lists (including `notesMd`/`descriptionMd`) back into `gtd_cache.db`. Nothing stops these refreshes:
        - `WorkManager.cancelUniqueWork` is asynchronous and only cancels a running `GtdSyncWorker` cooperatively;
        - the clear does not stop foreground collectors (`cachedView`);
        - `GtdSync.refreshAll` checks `isLoggedIn` only before it starts.
    - Why it matters: after a logout (or a server change, or a rejected refresh token) the device keeps the previous user's GTD data. After the next login on an unreachable server, that data is shown offline as if it belonged to the new session. AGENTS.md and the `SessionCache` contract treat these caches as user data that a logout drops.
    - Suggested fix: make clear and replace mutually exclusive and aware of the session. For example, hold a shared `Mutex` (or the one from CR-1) in both the `replace*` writes and the clear. Also take a session epoch or `isLoggedIn` check before the load and check it again inside the write, skipping the write when the session changed.

- [ ] **CR-3 | The SSE refresh tests pass even when change events have no effect**
    - File: `android/app/src/test/java/ch/sonensei/cockpit/gtd/data/GtdChangeStreamTest.kt:91`
    - Category: Tests
    - Problem: `a task change while the inbox is observed reloads it` and `contexts and projects reload on their own change types` (line 103) wait for a second load. But every connection already emits `ChangeSignal.Connected`, and `GtdRefreshTriggers.on` (GtdRefresh.kt:110) turns that into a reload whatever the type. So the tests pass even if:
        - `Changed` signals were dropped entirely;
        - the `type in types` filter were wrong (e.g. a TASK change not reloading Projects, or a CONTEXT change reloading the Inbox).

      The 5 s stop timeout, which closes the connection after the last collector leaves, is also untested.
    - Why it matters: the foreground SSE refresh is acceptance criterion 2. Its core behavior (reload on a matching change, no reload on a non-matching one) has no test that would catch a regression.
    - Suggested fix:
        1. Let the initial loads and the `Connected` reload settle, then count requests per route.
        2. Deliver a `change` event on a stream that is already open, e.g. a `MockResponse` with a streamed or delayed body. Unit-testing `GtdRefreshTriggers` with a fake `signals` flow also works.
        3. Assert exactly one extra load for a matching type and none for a non-matching type.

## P3 Low

- [ ] **CR-4 | Every screen start loads its view twice**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/data/GtdRefresh.kt:107`
    - Category: Quality
    - Problem: `on()` merges `flowOf(Unit)` with the change stream, and the stream emits `Connected` right after it opens (the server flushes `:connected` immediately). Opening a screen therefore runs the initial load and then, milliseconds later, a full second load (all pages; Today makes two listings each time). `conflate()` only drops triggers while a load is running; it does not merge the two.
    - Why it matters: every visit to a GTD screen doubles network traffic and server work, which costs mobile data.
    - Suggested fix: skip the reload for the first `Connected` when it arrives right after the initial load. For example, ignore `Connected` until one load has completed after it, or reload on `Connected` only after a previous disconnect.

- [ ] **CR-5 | A local database failure during refresh breaks the screen's flow**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/data/GtdRefresh.kt:31`
    - Category: Correctness
    - Problem: `GtdConnectivity.refreshing` maps network, HTTP and parse errors to `RefreshResult`, but an exception from the Room write (e.g. `SQLiteFullException` on a full device) propagates. In `cachedView` (GtdRepositories.kt:31) it is thrown from the launched trigger collector and cancels the whole `observe()` flow. The screen then loses its cached list instead of keeping it.
    - Why it matters: full storage is a realistic device state. The read cache should degrade (keep showing the last data) rather than fail the screen.
    - Suggested fix: catch `SQLiteException` in `refreshing` and map it to `RefreshResult.Failed(null)`, or catch non-cancellation exceptions around `refresh()` in `cachedView`.

## Review conclusion

Material issues found; fix before completing the task.
