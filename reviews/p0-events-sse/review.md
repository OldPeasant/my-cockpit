# Code Review

Date: 2026-10-20

Task: p0-events-sse - Change events and SSE stream (second review, on the final code after fixes CR-1..CR-3 and the second test pass)

Scope: `git diff 55cc2905c332b3c7e37c43a1e7ad4dd737d41654..f814f18027a744fd5b376a0dc7dfa14e69b9708a`, committed changes only. The working tree was clean.

Requirements:
- `tasks/p0-events-sse.md` acceptance criteria and recorded assumptions, including the 13:58 assumption that replaces "no emitter timeout" with `cockpit.events.stream-timeout` (default 15 min)
- `SPEC.md` §2 (SSE at `/api/v1/events`, modules talk via Spring application events), §4.1/§4.2 as cited by the first review
- `docs/ARCHITECTURE.md` (API under `/api/v1`, SSE, conventions)
- `AGENTS.md` project notes (API change flow, generated files, logging rules)
- First review `reviews/p0-events-sse/review.md` (previous revision), `reviews/p0-events-sse/fixes.md`, `reviews/p0-events-sse/tests.md`
- Parent instructions: verify CR-1..CR-3 are fixed, look for new validated issues

Git range:
- Branch: main
- HEAD: f814f18027a744fd5b376a0dc7dfa14e69b9708a
- Base/start: 55cc2905c332b3c7e37c43a1e7ad4dd737d41654
- Commits reviewed: 5 (7a3bd4f implementation, dbfbc2c tests and first reports, 45549e2 fixes CR-1..CR-3, b545396 timeout tests, f814f18 second test pass)

Summary: 0 P0, 0 P1, 0 P2, 0 P3 (open). The three earlier findings are resolved, and I found no new validated issues.

## Earlier findings (first review)

- [x] **CR-1 | An open stream keeps receiving events after its login ends (P2, Security). Resolved.**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/events/infra/SseEventHub.kt:34`, `:57-63`
    - Verification: the emitter is now created with `SseEmitter(streamTimeout.toMillis())`. `cockpit.events.stream-timeout` defaults to `PT15M` and is checked to be positive (`:45`). On timeout the hub removes the emitter and completes it, so the response ends normally instead of with `AsyncRequestTimeoutException`. EventSource then reconnects, and the reconnect goes through the session filter again. The trade-off (a stream outlives the end of its login by at most 15 min) is recorded as an assumption in the task file and described in the operation description and the regenerated `api/openapi.yaml`. Tests: `SseEventHubTests` (configured timeout used, timeout ends normally and removes the client, non-positive timeout rejected), `EventsStreamTests > without configuration a stream ends after 15 minutes` (application default wiring), `EventsAsyncDispatchTests` (the timeout-driven ASYNC dispatch passes the real security chain, with no 401 and no resolved exception).

- [x] **CR-2 | `onChange` throws `RejectedExecutionException` into publishers after the hub is destroyed (P3, Correctness). Resolved.**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/events/infra/SseEventHub.kt:73-87`
    - Verification: `sender.execute` is wrapped in a `RejectedExecutionException` catch that logs at debug level (exception text only, no event contents). Test: `SseEventHubTests > an event after the hub is destroyed is dropped without failing the publisher`.

- [x] **CR-3 | MockMvc stream tests leave emitters connected in the shared Spring context (P3, Tests). Resolved.**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/core/events/EventsStreamTests.kt:40-47`, `:116-127`
    - Verification: `connect()` records every `MvcResult`, and an `@AfterEach` completes each `MockAsyncContext`, which runs the emitter's completion callback and removes the client. `EventsAsyncDispatchTests` ends its stream through the timeout, which now also completes the emitter, and asserts that `clientCount` goes back to its earlier value. `EventsHttpStreamTests` disconnects and waits until the count drops. `SseEventHubTests` destroys its own hub after each test.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Verified without findings

- Criterion 1: `ChangeEvent(type, id, op, at)` with `ChangeOp {CREATED, UPDATED, DELETED}`. A blank type is rejected. The Enum constructor and publish overload take the constant's name (String type recorded as an assumption, since `ItemType` does not exist yet). `ChangeEventPublisher` stamps events with the injected `Clock` and publishes them through `ApplicationEventPublisher`. `SseEventHub.onChange` is a `@TransactionalEventListener` (default phase AFTER_COMMIT). `fallbackExecution = true` is recorded as an assumption.
- Criterion 2: `GET /api/v1/events` falls under the `/api/**` authenticated rule. The 401 is tested over MockMvc and over real HTTP. Heartbeat comments default to `PT25S` at a fixed rate, and the interval must be positive. Clients are removed on completion, timeout, error, a failed event write, a failed heartbeat write (new test in the second pass), destroy, and a real TCP disconnect (`EventsHttpStreamTests`).
- Criterion 3: the commit/rollback tests use real Postgres transactions (`PostgresTestBase`, Testcontainers), over MockMvc and over the embedded server. A further test shows that an event is not sent before its transaction commits.
- Criterion 4: `api/openapi.yaml` contains the `Events` tag, `GET /api/v1/events` (`text/event-stream`, 200/401) and the `ChangeEvent` schema with all four fields required. The updated description (15-minute stream end) matches the code default. The fixer reports that the regenerated web client did not change, which fits: the description does not reach the generated code. The `web/src/app/api/generated/` files match the spec.
- Security: the `DispatcherType.ASYNC` permit in `SecurityConfiguration` is limited to dispatches the container starts for a request that was already authorized. An anonymous REQUEST still gets 401, and `EventsAsyncDispatchTests` covers this with a real cookie login. Events carry no item contents, and failure logging stays at debug level with exception text only.
- Concurrency: the client set is a `CopyOnWriteArraySet`. Event and heartbeat writes all run on the single `sse-events` thread. The `:connected` write on the request thread, and the timeout or completion callbacks on container threads, rely on `ResponseBodyEmitter`'s internal synchronization. A send that races with a timeout fails inside `trySend`, which catches it and drops the client.
- Configuration: the new property follows the existing pattern (`@Value` with an inline default, like `cockpit.events.heartbeat`). Nothing was added to the build files.

## Evidence note (not a finding)

`python tasks.py verify p0-events-sse` shows all four criteria still unverified. `tests.md` and `fixes.md` describe runs of `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.events.*' --tests '*OpenApiContractTest'` (pass, 28 package tests), `UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'` (fixer pass) and `sh scripts/check.sh` (pass). The parent must record that evidence with `tasks.py verify` before `done`. This review was read-only and did not re-run the suites.

## Review conclusion

No material issues found.
