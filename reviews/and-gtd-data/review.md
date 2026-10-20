# Code Review

Date: 2026-10-21

Task: and-gtd-data - Android GTD repositories and read cache

Scope: re-review of the final code, `git diff e1895109..6807ed13`, limited to `android/app/src/main/java/ch/sonensei/cockpit/gtd/data/`, `android/app/src/test/java/ch/sonensei/cockpit/gtd/data/` and `android/app/schemas/`. The range contains:
- 1c2d5f2a: implementation;
- 49816b2c and 6f2de142: tests and the earlier reports;
- 8e477049: fixes for the previous findings CR-1..CR-5;
- 6807ed13: the fixer report.

Changes to `tasks/*.md` by other agents in the range are out of scope. A concurrent test-writer may edit test files; this review covers the code committed at 6807ed13 only.

Requirements:
- tasks/and-gtd-data.md acceptance criteria #1-#3 and the recorded assumptions
- SPEC.md §9.3 (read cache shown read-only with an offline flag, 15 min periodic sync, SSE refresh)
- AGENTS.md project notes and docs/QUALITY.md
- Previous review (CR-1..CR-5) and reviews/and-gtd-data/fixes.md
- Existing code: `auth/AuthRepository.kt` (`SessionCache` contract, serialized clears, logout flow)

Git range:
- Branch: main
- HEAD: 6807ed13
- Base/start: e1895109d91ed61a589cf8a0fe113181e576c68a
- Commits reviewed: 5 task commits (1c2d5f2a, 49816b2c, 6f2de142, 8e477049, 6807ed13). The other commits in the range are heartbeat, claim and assume commits from other agents.

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Previous findings

- **CR-1 (concurrent refreshes overwrite newer data): resolved.**
    - What changed: `GtdConnectivity.refreshing(view, load, store)` (GtdRefresh.kt:47-61) holds a mutex per view key around load and store, and all three repositories use it.
    - Fairness: kotlinx `Mutex` is FIFO, so the load that started last writes last.
    - Test: `GtdCacheConsistencyTest."an older load of a view never overwrites a newer one"` is a real regression test. Without the lock, the second request would take "Newer" from the queue, store it first, and then be overwritten by "Older".
- **CR-2 (logout refills the cache): resolved.**
    - How it works: `clearing` increments `clears` and runs `clearAllTables()` under `writeLock`. `refreshing` reads the counter before the load and checks it again under `writeLock` before `store`, so an answer that arrives after a clear is dropped as `SessionEnded`, which maps to `Failed(null)`.
    - Wiring: the `SessionCache` binding runs the clear on `Dispatchers.IO`, which Room's main-thread check requires.
    - Test: covered by `GtdCacheConsistencyTest`.
- **CR-3 (SSE tests pass without change events): resolved.**
    - `GtdChangeStreamTest.loadsWith` delivers events on the one open stream only after the first loads, and refuses all later connections.
    - It asserts exact load counts per path, for both matching and non-matching types.
    - The stop timeout now has its own test.
- **CR-4 (double load on screen start): resolved.**
    - The stream now emits `Connected` for its first connection and `Reconnected` for later ones, and only `Reconnected` and matching `Changed` signals reload.
    - New issue: the fix leaves a small window in which a change can be missed. See CR-1 below.
- **CR-5 (database failure breaks the flow): resolved.** `SQLiteException` maps to `Failed(null)`. The test uses a trigger that makes inserts abort, and checks that the `observe()` collector stays alive and recovers.

## Checked with no findings

- **Deadlocks between the view mutex, the write lock and logout.** There is no deadlock:
    - Lock order is always view lock, then write lock. The write lock is never held while the code waits for a view lock.
    - `clearing` takes only the write lock. `AuthRepository.cacheClearing` takes the write lock through `SessionCache.clear`.
    - Nothing that runs under the write lock (the Room replace, `clearAllTables`) calls back into auth or into the locks.
    - A token refresh that fails during a load (while its view lock is held) logs out through `appScope`, which needs only the write lock.
    - The `Mutex` is not reentrant, and no code path takes the same lock twice.
- **Cancellation while holding locks.** `withLock` releases the lock in `finally`. `CancellationException` is not caught by `refreshing`, because none of the caught types is a supertype of it. A cancelled `refreshing` that is waiting for the view lock or the write lock leaves both consistent. `clears` is only read and written under the write lock.
- **Room `@Transaction` on open suspend DAO methods** (`taskRows`, `projectRows`, `contextRows`, `replace*`, GtdCacheDatabase.kt:96-147). Room supports this in an abstract DAO class and runs the method inside `withTransaction`. The protected abstract queries are generated. The read methods are queries only, and `app/schemas/.../1.json` is unchanged by the fix, as it should be.
- **Flow behaviour of `meta.map { read() }`** (GtdRepositories.kt:35):
    - Room flows emit once when collected and again after every commit that touches `cache_meta`.
    - Each replace upserts its meta row inside the same transaction (also for an empty list), and `clearAllTables` deletes the meta rows. So every change to rows invalidates the meta flow, and `read()` returns rows and meta from one transaction.
    - When several invalidations arrive in a burst, Room conflates them. A read that sees a newer commit is followed by one more equal read, which is harmless.
    - Refreshes of other views also cause re-reads, because invalidation works per table. That was already the case with the earlier per-table row flows.
- **The offline flag and `SessionEnded`.** `Failed` leaves the flag as it is. `clearing` resets it after the clear.
- **The worker.** `refreshAll` runs the refreshes one after another through the same locks. A dropped post-logout answer maps to `Failed`, so the worker returns `success`, not `retry`.
- **Tests.** The existing results in `android/app/build/test-results/testDebugUnitTest/` (2026-10-21 00:35 +0200) show all 10 `gtd.data` classes passing with 0 failures, among them GtdCacheConsistencyTest 4/4, GtdChangeStreamTest 9/9 and GtdRefreshTriggersTest 6/6. This review did not re-run Gradle.
- **Evidence.** `python tasks.py verify and-gtd-data` still shows all three criteria unrecorded. The parent worker must record them before `done`. This is not a code finding.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | A change made between the initial load and the first stream connection is missed**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/data/GtdRefresh.kt:162` (with `GtdChangeStream.kt:82`)
    - Category: Correctness
    - Problem: after the CR-4 fix, the first connection (`ChangeSignal.Connected`) no longer reloads. The initial load (`flowOf(Unit)`) and the stream connection start at the same time and are independent. If the server answers the initial listing before the SSE subscription is registered, a change made by another device in between is never signalled. The view shows the older list until the next matching change, or until the next reconnect, which comes when the server ends the stream after 15 minutes.
    - Why it matters: the window is short, but the stale list then stays visible for up to about 15 minutes, and it is also the copy served offline.
    - Suggested fix: reload on `Connected` only when the view's initial load started before the connection opened. One way: record when the trigger flow started its first load, and reload on `Connected` only if that first load had already started. Another way: delay the initial load until the stream has connected or failed once. Either keeps one load per screen start in the common case. Add a test that holds back the events connection until the first load has answered, then expects one reload.

- [ ] **CR-2 | A load that fails after a logout marks the next session offline**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/data/GtdRefresh.kt:54-60` (flag update at line 96)
    - Category: Correctness
    - Problem: the session check (`clears != clearsAtStart`) runs only on the store path. Take a load that started before the logout and then fails with `IOException` or a 5xx after `clearing` has called `reset()`. It still sets `offline = true`, because `refreshing(block)` records `RefreshResult.Offline` before any session check. Logout does not cancel requests already in flight, so this happens whenever the server or network drops during a logout. The flag is shared and a `@Singleton`, so after the next login the GTD screens first show the empty cache with the offline flag. This lasts until their first load succeeds, or, if that load fails with a 4xx, until a later one does.
    - Why it matters: this is a small leftover of the same session-boundary problem that the previous CR-2 fixed for the cache rows. The previous session's outcome leaks into the new session's state.
    - Suggested fix: also compare the clear counter on the failure path. For example, compute the result inside `refreshing(view, ...)` and update `state` only if `clears == clearsAtStart`, otherwise return `Failed(null)` without touching the flag. Extend `GtdCacheConsistencyTest."a load still running at logout ..."` with a held-back answer that fails (503), and assert that `offline` stays `false` after the clear.

## Review conclusion

No material issues found. All five previous findings are resolved and no deadlock or cancellation problem was found in the fixes. The two P3 items are minor and may be fixed or deferred at the parent worker's discretion.
