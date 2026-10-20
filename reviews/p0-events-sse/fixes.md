# Fix Report

Date: 2026-10-20

Task: p0-events-sse - Change events and SSE stream

Scope: findings CR-1, CR-2 and CR-3 of `reviews/p0-events-sse/review.md`. Git range: base 5b8c90a, HEAD fa947f6. The fixes are uncommitted working-tree changes.

## Fixed findings

### CR-1 — An open stream keeps receiving events after its login ends

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/events/infra/SseEventHub.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/core/events/api/EventsController.kt`
- `api/openapi.yaml` (regenerated)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/events/SseEventHubTests.kt`

Problem:
The emitter was created with `NO_TIMEOUT` (0), and authorization was checked only when the stream opened. So a stream could outlive logout, session expiry or revocation for as long as the TCP connection lasted.

Fix:
The emitter timeout is now `cockpit.events.stream-timeout`, a new constructor property next to the heartbeat. It defaults to `PT15M` and must be positive (`require`). On timeout the hub removes the emitter and calls `emitter.complete()`. The response then ends normally, without an `AsyncRequestTimeoutException`/503 on a response that is already committed. EventSource reconnects on its own, and each reconnect passes the authorization filters again, so a stream outlives the end of its login by at most the timeout. I updated the KDoc and the operation description ("The server ends each stream after 15 minutes; clients reconnect ..."), regenerated `api/openapi.yaml` and ran the web client generator. The generated web client did not change: the description does not end up in the generated code.

New tests in `SseEventHubTests`:
- the configured timeout (90 s in the test, not the default) is used for the async request;
- a timed-out stream removes the client and ends with a null async result and no resolved exception;
- a zero or negative stream timeout is rejected.

I checked the timeout test with a mutation: removing `emitter.complete()` from the timeout callback makes it fail, because the async result becomes an `AsyncRequestTimeoutException`. I also wrote a real-HTTP test in a separate `@SpringBootTest` with `stream-timeout=PT1S`. Its extra application context pushed the test JVM's Postgres container over `max_connections` ("sorry, too many clients already" in `ProfileTests`/`FlywayConfigurationTests`), even with `@DirtiesContext` and a pool of 2. I removed it. The baseline `./gradlew check` passes without it.

Validation:
- `cd server && ./gradlew ktlintFormat ktlintCheck test --tests 'ch.sonensei.cockpit.core.events.*' --tests '*OpenApiContractTest'` — Pass
- `cd server && UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'` — Pass (spec regenerated)
- `npm --prefix ../web run api:gen` then `npm --prefix ../web run api:check` — Pass ("src/app/api/generated is up to date.")

### CR-2 — `onChange` throws `RejectedExecutionException` into publishers after the hub is destroyed

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/events/infra/SseEventHub.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/events/SseEventHubTests.kt`

Problem:
After `destroy()` (`sender.shutdownNow()`), `sender.execute` threw `RejectedExecutionException` straight to the publisher, or out of `afterCommit` for a write that was already committed.

Fix:
`onChange` catches `RejectedExecutionException`, logs at debug level (exception text only, no event contents) and drops the event. New test: `an event after the hub is destroyed is dropped without failing the publisher`. Mutation check: catching a different exception type makes it fail with `RejectedExecutionException`.

Validation:
- `cd server && ./gradlew test --tests '*SseEventHubTests'` — Pass (13 tests)

### CR-3 — MockMvc stream tests leave emitters connected in the shared Spring context

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/core/events/EventsStreamTests.kt`

Problem:
Streams opened through MockMvc never ended, so their emitters stayed in the cached application context.

Fix:
`connect()` records each `MvcResult`. An `@AfterEach` completes their `MockAsyncContext`, which runs the emitter's completion callback and removes the client. I checked `EventsAsyncDispatchTests` as well: it already ends its stream by firing the container timeout, and with CR-1 that timeout now also completes the emitter, so it leaves nothing connected and needed no change. `SseEventHubTests` uses its own hub, which is destroyed after each test.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.events.*'` — Pass (EventsStreamTests 4, EventsAsyncDispatchTests 1)

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat ktlintCheck test --tests 'ch.sonensei.cockpit.core.events.*' --tests '*OpenApiContractTest'` — BUILD SUCCESSFUL. ChangeEventTests 6, EventsAsyncDispatchTests 1, EventsHttpStreamTests 2, EventsStreamTests 4, SseEventHubTests 13, OpenApiContractTest 1; 0 failures.

Full test suite:
- `sh scripts/check.sh` (server `./gradlew check`, web `npm run check`, android ktlint/unit/lint, TFP gate tests) — Pass ("all checks passed")

Lint/type-check/build:
- ktlintCheck — Pass; web eslint and build in `scripts/check.sh` — Pass; `npm --prefix web run api:check` — Pass

`.taskcheck`:
- Pass (`sh scripts/check.sh`, "all checks passed")

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
