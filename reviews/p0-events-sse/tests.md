# Test Report

Date: 2026-10-20

Task: p0-events-sse - Change events and SSE stream

Scope: `git diff 5b8c90a6f1a55c67733e6824fcfeb72afde820c7...e1dd89e4b7057d5d2561104d44d93010c49c6495` on branch `main`.
There is one task commit, `e1dd89e` ("p0-events-sse: ChangeEvent, after-commit SSE stream ..."). The parent supplied the
base and gave the task commit as `d3211b0`. That commit is no longer an ancestor of HEAD because a rebase produced
`e1dd89e` from it. `git diff d3211b0 e1dd89e` only touches `tasks/*.md`, so the production and test content is the same.
I measured at HEAD `e1dd89e` plus my uncommitted test changes (listed below). There are no other uncommitted changes. The
untracked `reviews/p0-events-sse/review.md` comes from the code-reviewer and is not a test input.

Requirements:
- `tasks/p0-events-sse.md` acceptance criteria and its four recorded assumptions (String type + Enum overload,
  `fallbackExecution = true`, ASYNC dispatch permit, SSE format `event:change` / `:connected` / `:heartbeat`, no emitter timeout)
- SPEC.md §2 / §4.2 (ISO-8601 dates) via the task, `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md` (fictitious test data)
- Parent instructions (suggested test areas, commands, no production changes)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/events/ChangeEventTests.kt` (new)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/events/EventsAsyncDispatchTests.kt` (new)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/events/SseEventHubTests.kt` (6 tests added)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/events/EventsStreamTests.kt` (1 test added)

No existing test was changed, weakened or disabled. No production code was changed.

## Tests added or updated

Count: 14 (the package went from 8 to 22 tests)

- `ChangeEventTests` (unit, no Spring; uses a fixed `Clock` and a capturing `ApplicationEventPublisher` lambda):
  - `a blank type is rejected`: covers "", " " and "\t\n".
  - `an enum type becomes the constant's name`
  - `publishing by enum stamps the event with the application clock`
  - `publishing by name stamps the event with the application clock`
  - `a complete event is published unchanged`: the caller's `at` is kept.
  - `publishing a blank type fails and publishes nothing`
- `SseEventHubTests` (standalone MockMvc, 50 ms heartbeat):
  - `a new stream starts with a connected comment and heartbeats are comment lines without data`: checks the exact
    `:connected\n\n` / `:heartbeat\n\n` wire format, with no `data:` or `event:` lines.
  - `a client whose stream times out is removed`: triggers the container's `onTimeout` callback.
  - `an event outside a transaction reaches every connected client as a named JSON event`: two clients, direct
    `onChange` (the `fallbackExecution` path).
  - `a client that cannot be written to is dropped and the others still get the event`: a completed emitter's `send`
    throws, which drives the `trySend` catch path. The healthy client still gets the event.
  - `destroy completes every stream and forgets the clients`: the async requests of both clients end
    (`getAsyncResult` returns) and `clientCount` is 0.
  - `the heartbeat interval must be positive`: covers zero and negative values.
- `EventsStreamTests` (Spring context, Testcontainers Postgres):
  - `the event data is the ChangeEvent as JSON with an ISO-8601 timestamp`: compares the exact `data:` line produced
    by the application's configured JSON mapper. `at` is `"2026-03-14T09:26:53.589Z"`, not a numeric timestamp.
- `EventsAsyncDispatchTests` (Spring context, Testcontainers Postgres, stored login with a real session cookie, no
  `@WithCockpitUser`):
  - `the async dispatch that ends a cookie-authenticated stream is not rejected`: tests the security change. The
    session filter (`OncePerRequestFilter`) does not run on ASYNC dispatches, so the dispatch that ends the stream is
    anonymous. The test ends the stream from the container side, performs the `DispatcherType.ASYNC` dispatch, and
    asserts that it is not answered 401 and reaches `EventsController.stream`. Without the ASYNC permit, the
    authorization filter would stop it before the DispatcherServlet. I only reasoned about that case: I could not run
    the test with the fix reverted, because I must not change production code. The log confirms that the dispatch
    reached MVC (`ProblemDetailsAdvice`: "Response already committed. Ignoring: AsyncRequestTimeoutException").

The parent asked for "unauthenticated REQUEST to /api/v1/events still gets 401 after the ASYNC permit". The existing
tests `EventsStreamTests > the stream needs a login` (MockMvc) and `EventsHttpStreamTests > without a session cookie
the stream answers 401` (real HTTP) already cover this, together with `SecurityConfigurationTests` for other API paths.
Both pass on the final run, so I added no duplicate.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 `ChangeEvent(type: ItemType\|String, id, op, at)`; type validated; Enum constructor (assumption) | ChangeEventTests (all 6) | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.events.*'` | Pass |
| AC1 published via Spring events, stamped with the application clock | ChangeEventTests `publishing by enum/name stamps ...`, `a complete event is published unchanged` | same | Pass |
| AC1 delivered after commit (`@TransactionalEventListener`); a rolled-back event is not; not before commit | EventsStreamTests (rolled back / not before commit), EventsHttpStreamTests (real HTTP) | same | Pass |
| AC1 assumption: an event outside a transaction is delivered right away (`fallbackExecution`) | SseEventHubTests `an event outside a transaction reaches every connected client ...`, EventsStreamTests `... not sent before the commit` (marker event) | same | Pass |
| AC2 `GET /api/v1/events` requires authentication | EventsStreamTests `the stream needs a login`, EventsHttpStreamTests `without a session cookie the stream answers 401` | same | Pass |
| AC2 SSE format: `event:change`, data = ChangeEvent JSON with ISO-8601 `at`, `:connected` first | EventsStreamTests `the event data is the ChangeEvent as JSON ...`, SseEventHubTests `a new stream starts ...`, EventsHttpStreamTests | same | Pass |
| AC2 heartbeat comments every 25 s (configurable interval, must be positive) | SseEventHubTests `connected clients get heartbeat comments`, `a new stream starts ... heartbeats are comment lines`, `the heartbeat interval must be positive` | same | Pass at a 50 ms interval. The 25 s default (`cockpit.events.heartbeat:PT25S`) was checked by reading the code; no test waits 25 s |
| AC2 disconnected emitters are cleaned up (complete, error, timeout, failed write, shutdown) | SseEventHubTests (complete, fails, times out, cannot be written to, destroy), EventsHttpStreamTests (real disconnect) | same | Pass |
| Assumption: the ASYNC permit lets the stream-ending dispatch through; anonymous REQUEST stays 401 | EventsAsyncDispatchTests, plus the two 401 tests above | same | Pass |
| AC3 the package's tests pass (Testcontainers Postgres) | whole `core.events` package, 5 suites / 22 tests | same | Pass (22/22, 0 skipped) |
| AC4 `OpenApiContractTest` passes without the flag | OpenApiContractTest (part of the full suite) | `cd server && ./gradlew test jacocoTestReport --rerun-tasks` | Pass. I did not rerun the regeneration with `UPDATE_OPENAPI=1`; `git status` shows `api/openapi.yaml` unchanged after the run |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.events.*'`

Result:

Pass: 22 tests, 0 failures, 0 skipped (ChangeEventTests 6, SseEventHubTests 9, EventsStreamTests 4,
EventsHttpStreamTests 2, EventsAsyncDispatchTests 1). The first attempt failed because my own new test asserted the
content type of the async dispatch's fresh mock response. I corrected the assertion; it was not a production problem.

`cd server && ./gradlew ktlintCheck` -> Pass (BUILD SUCCESSFUL).

## Full test execution

Command:

`cd server && ./gradlew test jacocoTestReport --rerun-tasks`

Result:

Pass: BUILD SUCCESSFUL in 1m 33s. 687 tests, 0 failures, 0 errors, 0 skipped (summed over `build/test-results/test/*.xml`).

## .taskcheck

Result:

Pass: `sh scripts/check.sh` printed "== all checks passed". It covers the TFP gate tests, server `check`, web
lint/tests/build and the android ktlint/unit tests/lint.

## Bugs found

None. No test exposed behavior that violates the acceptance criteria or the recorded assumptions.

Observation (not a defect, no finding ID): after `SseEventHub.destroy()`, a later `onChange` would throw
`RejectedExecutionException` from the shut-down executor. This can only happen while the application context is
shutting down. In a Spring context, the destroyed listener bean would not be invoked anyway, because `getBean` is not
allowed during singleton destruction. The task defines no behavior after shutdown, so I added no test for it.

## Coverage

Status: verified (fresh run, exit status 0).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured for line counters. The four `core/events` files are entirely new, so their changed
lines equal the whole file (41 + 15 + 7 + 4 executable lines, all covered). In `SecurityConfiguration.kt`, the only
changed executable line is 48 (`dispatcherTypeMatchers(... ASYNC)`; lines 46-47 are comments). JaCoCo reports it
covered (ci=18, mi=0). Changed-line coverage: 68/68 executable lines. I did not derive changed-branch coverage from it.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `p0-events-sse`; base
  `5b8c90a6f1a55c67733e6824fcfeb72afde820c7` (from the parent); HEAD `e1dd89e4b7057d5d2561104d44d93010c49c6495`; one task
  commit `e1dd89e`, which is the rebased form of the parent-named `d3211b0`. They differ only in `tasks/*.md`. The file
  scope comes from `git diff --name-only 5b8c90a...HEAD -- server/src/main web/src android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `e1dd89e` plus these sha256 values (prefixes),
  identical before and after the coverage run: ChangeEventTests.kt `84a45ea37810`, EventsAsyncDispatchTests.kt `6622d36f8eee`,
  EventsHttpStreamTests.kt `00e570690965` (committed), EventsStreamTests.kt `a2fb758ac79f`, SseEventHubTests.kt `4ed5877b34a4`;
  production (committed, unchanged): EventsController.kt `295d8c4f447a`, ChangeEvent.kt `380d915d0aed`,
  ChangeEventPublisher.kt `bb60397d9f92`, SseEventHub.kt `d865233716b4`, SecurityConfiguration.kt `514a91fd8b64`.
- Exact included files: the five `server/src/main/kotlin/ch/sonensei/cockpit/...` files in the table below. Excluded:
  `web/src/app/api/generated/**` (5 files, generated by `npm run api:gen`, no hand-written behavior) and
  `api/openapi.yaml` (generated contract, checked by OpenApiContractTest). No files were renamed or deleted.
- Provider/version, command, working directory, and test selection: JaCoCo (Gradle `jacoco` plugin, as configured in
  `server/build.gradle.kts`); `./gradlew test jacocoTestReport --rerun-tasks` in `server/`; the full server test suite.
- Run start/completion time and exit status: about 13:41-13:42 +02:00 on 2026-10-20 (1m 33s); BUILD SUCCESSFUL, exit 0.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`
  (written 2026-10-20 13:42:31 +02:00, sha256 `f921eaba342bdba01742bc55eea497aa61b7c3c887323cae9661829aac361d15`), plus `.../html/index.html`.
- Evidence outputs are fresh and inputs stayed unchanged: `--rerun-tasks` re-executed all 7 tasks, including
  `test` and `jacocoTestReport`. The report timestamp falls inside the run, and the input hashes matched before and after.
- Final run after latest test additions and production fixes: yes. The run came after all four test files were in their
  final state. There have been no production fixes.

Values below use covered/total (percentage). "Statements" are JaCoCo INSTRUCTION counters; zero-denominator metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/core/events/domain/ChangeEvent.kt` | 79/79 (100%) | 4/4 (100%) | 7/7 (100%) | 15/15 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/events/domain/ChangeEventPublisher.kt` | 44/44 (100%) | N/A | 4/4 (100%) | 7/7 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/events/infra/SseEventHub.kt` | 235/249 (94.4%) | 2/2 (100%) | 15/15 (100%) | 41/41 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/events/api/EventsController.kt` | 10/10 (100%) | N/A | 2/2 (100%) | 4/4 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/infra/SecurityConfiguration.kt` | 225/227 (99.1%) | 2/4 (50%) | 10/10 (100%) | 34/34 (100%) |
| **Aggregate (summed counters, 5 files)** | 593/609 (97.4%) | 8/10 (80%) | 38/38 (100%) | 101/101 (100%) |

The aggregate leaves out only the generated web client files and `api/openapi.yaml` (see exclusions). No in-scope file
is missing from the report.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `SseEventHub.kt:87` (`runCatching { emitter.complete() }`, failure path) | Only runs if `complete()` itself throws while dropping a client that failed to write | Not material: a defensive guard. The success path is covered by `a client that cannot be written to is dropped ...` |
| `SseEventHub.kt:93` (`runCatching { it.complete() }` in `destroy()`, failure path) | Only runs if completing a stream throws during shutdown | Not material: a defensive guard. The normal path is covered by `destroy completes every stream ...` |
| `SecurityConfiguration.kt:94` (2 of 4 branches of `(... as? CsrfToken)?.token`) | Pre-existing CSRF-cookie filter code, not changed by this task | Outside the task's change; this task added no gap there |

## Unable to test

- I did not test the 25-second default heartbeat interval in real time. Waiting 25 s in a unit test is not worth the
  cost. The same code path is tested with a configured 50 ms interval, and the default
  `@Value("\${cockpit.events.heartbeat:PT25S}")` was checked by reading the code. No follow-up needed.
- I could not run `EventsAsyncDispatchTests` with the ASYNC permit reverted, because I must not change production
  code. Its claim that it would fail without the fix rests on reasoning: `OncePerRequestFilter` skips ASYNC dispatches,
  and Spring Security's `AuthorizationFilter` filters them. No follow-up needed unless a reviewer wants that check done.

## Summary

Tests added and all validation passed. Task-scoped coverage is sufficient, and the remaining gaps are justified. No
production defects were found.
