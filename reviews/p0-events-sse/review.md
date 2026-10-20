# Code Review

Date: 2026-10-20

Task: p0-events-sse - Change events and SSE stream

Scope: `git diff 5b8c90a...HEAD` (one commit, the task's implementation). Uncommitted test-writer changes under `server/src/test/.../core/events` were ignored, as instructed.

Requirements:
- `tasks/p0-events-sse.md` acceptance criteria and recorded assumptions
- `SPEC.md` §2 (SSE `/api/v1/events`), §4.1 (auth, session lifetime, Android token expiry and device revocation), §4.2 (ids, ISO-8601)
- `docs/ARCHITECTURE.md` (code conventions, module boundaries, Clock usage)
- `AGENTS.md` project notes (API change flow, generated files, logging rules)
- Parent agent instructions (focus on auth on the stream, ASYNC permit, thread-safety, resource cleanup)

Git range:
- Branch: main
- HEAD: e1dd89e4b7057d5d2561104d44d93010c49c6495
- Base/start: 5b8c90a6f1a55c67733e6824fcfeb72afde820c7
- Commits reviewed: 1 (e1dd89e "p0-events-sse: ChangeEvent, after-commit SSE stream GET /api/v1/events with heartbeats")

Note: the parent named the task commit `d3211b0`; the repository's HEAD and only commit in the range is `e1dd89e`, which is what was reviewed.

Summary: 0 P0, 0 P1, 1 P2, 2 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | An open stream keeps receiving events after its login ends (logout, expiry, revocation)**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/events/infra/SseEventHub.kt:48`
    - Category: Security
    - Problem: Authorization is checked only when the stream opens. The emitter has no timeout (`NO_TIMEOUT = 0L`), and the hub never checks the session again, so a connected client keeps getting every `ChangeEvent` (type, id, op, time) for as long as the TCP connection lasts. That holds after the session is logged out, after it expires (SPEC.md §4.1: 30-day sliding web sessions), and later for Android devices that get revoked or whose 15-minute access token expires (SPEC.md §4.1). Permitting `DispatcherType.ASYNC` is fine in itself, because the server only starts an async dispatch for a request that was already authorized. The gap is that nothing ends the stream. The recorded assumption ("no emitter timeout") covers detecting dead clients, not ending authorization.
    - Why it matters: Ending a login is supposed to stop access, and here it doesn't for this channel. The leak is metadata only, with no item contents, but a revoked or logged-out client can keep watching what changes and when, with no limit.
    - Suggested fix: Bound the stream's life to the login. One option is a finite emitter timeout (for example a few minutes; EventSource reconnects on its own, and each reconnect goes through the session filter again). Another is to remember the session/principal per emitter and complete those emitters on logout or session deletion, or re-check the session on each heartbeat. Record the choice with `tasks.py assume`, and add a test that a stream does not outlive its session (or is closed by the timeout).

## P3 Low

- [ ] **CR-2 | `onChange` throws `RejectedExecutionException` into publishers after the hub is destroyed**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/events/infra/SseEventHub.kt:60`
    - Category: Correctness
    - Problem: `destroy()` calls `sender.shutdownNow()`. Publishers don't depend on `SseEventHub`, so Spring may destroy the hub while other beans (schedulers, sync jobs) are still running during shutdown. A later `publish` then reaches `sender.execute`, which throws `RejectedExecutionException`. With `fallbackExecution = true` the exception goes straight to a non-transactional caller. In a transaction it is thrown from `afterCommit`, so the caller sees an error for a write that was already committed.
    - Why it matters: During shutdown, callers get spurious failures or noisy errors for changes that did succeed.
    - Suggested fix: In `onChange`, skip events once the executor is shut down (check `sender.isShutdown` or catch `RejectedExecutionException` and log it at debug level).

- [ ] **CR-3 | MockMvc stream tests leave emitters connected in the shared Spring context**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/core/events/EventsStreamTests.kt:77`
    - Category: Tests
    - Problem: `connect()` opens a stream that never times out. Writes to a `MockHttpServletResponse` never fail, so these emitters are never removed. They stay in the cached application context (shared with other `PostgresTestBase` classes) and pile up response content from every later event and heartbeat. `EventsHttpStreamTests` works around this by comparing against `clientCount` before the test, which only hides the leak.
    - Why it matters: Test state leaks between test classes, and `clientCount` can't be asserted absolutely.
    - Suggested fix: Complete the stream at the end of each test (for example `result.request.asyncContext?.complete()` in an `@AfterEach`), or give the hub a way to close all clients that tests can call.

## Verified without findings

- Criterion 1: `ChangeEvent(type, id, op, at)` with `ChangeOp {CREATED, UPDATED, DELETED}`. The String type plus an Enum overload is recorded as an assumption. It is published through `ApplicationEventPublisher` with the injected `Clock` and delivered by a `@TransactionalEventListener` (default phase AFTER_COMMIT). `fallbackExecution = true` is recorded as an assumption.
- Criterion 2: `GET /api/v1/events` falls under `/api/**` `.authenticated()`. The 401 is tested over MockMvc and over real HTTP. The heartbeat defaults to PT25S, is scheduled at a fixed rate and its interval is checked to be positive. Clients are removed on completion, timeout, error and failed writes. Removal after a real disconnect is tested over HTTP.
- Criterion 3: the commit/rollback tests use real Postgres transactions (`PostgresTestBase`) over MockMvc and over the embedded server. One test also checks that the event is not sent before the commit.
- Criterion 4: `api/openapi.yaml` and `web/src/app/api/generated/` contain the endpoint and the `ChangeEvent` schema, and the two match each other. Running the contract test and the Gradle suite was not part of this read-only review. The parent must record that evidence with `tasks.py verify` (the task file shows no evidence recorded yet).
- Thread-safety: `CopyOnWriteArraySet` for the client set. All event and heartbeat writes go through one sender thread. The `:connected` write in `subscribe()` runs on the request thread, but `ResponseBodyEmitter.send` serializes writes internally and buffers writes made before the handler is initialized.
- Security: the `DispatcherType.ASYNC` permit does not let unauthenticated requests through, because only the server starts async dispatches, and only for requests that were authorized. Events carry no item contents. Nothing sensitive is logged (failed writes are logged at debug level, exception text only).

## Review conclusion

Material issues found; fix before completing the task.

CR-1 (P2, security): fix it, or have the parent record an explicit decision (`tasks.py assume`) about stream lifetime versus session lifetime. The leak is bounded: metadata only, and only while a connection is held. CR-2 and CR-3 are small cleanups. All four acceptance criteria are implemented and have matching tests. Their run evidence still has to be recorded.
