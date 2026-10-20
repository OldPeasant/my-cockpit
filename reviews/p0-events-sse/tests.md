# Test Report

Date: 2026-10-20

Task: p0-events-sse - Change events and SSE stream (second test pass, on the final code after fixes CR-1..CR-3)

Scope: `git diff 55cc2905c332b3c7e37c43a1e7ad4dd737d41654..b54539653acd5f86a6f5ba4239f281899897b505` on branch `main`.
The parent supplied the base and four task commits: `7a3bd4f` (implementation), `dbfbc2c` (first-pass tests and
reports), `45549e2` (fixes CR-1..CR-3), `b545396` (tests for the default stream timeout and clean timeout completion).
`git log 55cc290..HEAD` lists exactly these four. I measured at HEAD `b545396` plus one uncommitted test change from this
pass (`SseEventHubTests.kt`). There are no other uncommitted or untracked inputs.

Requirements:
- `tasks/p0-events-sse.md` acceptance criteria and its recorded assumptions, including the 13:58 one that replaces "no
  emitter timeout" with `cockpit.events.stream-timeout` (default 15 min)
- `reviews/p0-events-sse/review.md` (CR-1..CR-3) and `reviews/p0-events-sse/fixes.md`, for the fixed behavior
- SPEC.md §2 and §4.1/§4.2 (via the task and review), `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`
- Parent instructions: second pass, add tests only where meaningful gaps remain, no second `@SpringBootTest` context
  (an extra context had exhausted Postgres `max_connections`)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/events/SseEventHubTests.kt` (1 test added in this pass)

No existing test was changed, weakened or disabled. No production code was changed. No new Spring context was added:
the new test uses the standalone hub of `SseEventHubTests`.

## Tests added or updated

Count: 1 (the `core.events` package now has 28 tests, up from 27 after the first pass and the fixes)

- `SseEventHubTests > a heartbeat alone drops a client that cannot be written to`: one healthy client and one whose
  emitter is already completed (so writing to it throws). No event is published, so only the heartbeat write can find
  the dead client. The test asserts that the client count drops to 1 and that the healthy client still gets heartbeats.
  Why this was a gap: the recorded assumption says dead clients are found when a heartbeat write fails, and AC2 says
  disconnected emitters are cleaned up. Until now only the event path (`onChange`) was shown to drop a broken client in
  isolation. `EventsHttpStreamTests` does publish events while it waits, so it never isolated the heartbeat path.

I reviewed the fixed behavior against the existing tests and found these already covered, so I added no duplicates:
- CR-1 configurable stream timeout: `SseEventHubTests > a stream uses the configured timeout` (90 s, not the default),
  `a stream that times out ends normally instead of with an error` (null async result, no resolved exception, client
  removed), `the stream timeout must be positive` (zero and negative). `EventsStreamTests > without configuration a
  stream ends after 15 minutes` checks the application's default wiring. `EventsAsyncDispatchTests` checks that the
  timeout ends a cookie-authenticated stream cleanly through the real security filter chain (no 401, no
  `AsyncRequestTimeoutException`).
- CR-2 `RejectedExecutionException` after destroy: `SseEventHubTests > an event after the hub is destroyed is dropped
  without failing the publisher`. Without the catch, `onChange` would throw out of the test, so the test would fail.
- CR-3 test stream cleanup: `EventsStreamTests` completes every stream it opens in `@AfterEach`.
  `EventsAsyncDispatchTests` ends its stream through the timeout and asserts `clientCount` returns to the earlier value.
  `EventsHttpStreamTests` disconnects and waits until the count drops. `SseEventHubTests` destroys its own hub after
  each test.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 `ChangeEvent(type: ItemType\|String, id, op, at)`; blank type rejected; Enum constructor (assumption) | ChangeEventTests (all 6) | `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.events.*' --tests '*OpenApiContractTest'` | Pass |
| AC1 published via Spring events, stamped with the application clock | ChangeEventTests `publishing by enum/name stamps ...`, `a complete event is published unchanged` | same | Pass |
| AC1 delivered after commit (`@TransactionalEventListener`), not on rollback, not before commit | EventsStreamTests (committed/rolled back, not before commit), EventsHttpStreamTests (real HTTP) | same | Pass |
| AC1 assumption: an event outside a transaction is delivered right away | SseEventHubTests `an event outside a transaction reaches every connected client ...` | same | Pass |
| AC2 `GET /api/v1/events` requires authentication | EventsStreamTests `the stream needs a login`, EventsHttpStreamTests `without a session cookie the stream answers 401` | same | Pass |
| AC2 SSE format (`event:change`, ChangeEvent JSON data with ISO-8601 `at`, `:connected`, `:heartbeat`) | EventsStreamTests `the event data is the ChangeEvent as JSON ...`, SseEventHubTests `a new stream starts ...` | same | Pass |
| AC2 heartbeat comments every 25 s (interval configurable, must be positive) | SseEventHubTests `connected clients get heartbeat comments`, `the heartbeat interval must be positive` | same | Pass at a 50 ms interval; the 25 s default was checked by reading the code (no test waits 25 s) |
| AC2 disconnected emitters are cleaned up (complete, error, timeout, failed event write, failed heartbeat write, destroy, real disconnect) | SseEventHubTests (6 cleanup tests, including the new `a heartbeat alone drops a client that cannot be written to`), EventsHttpStreamTests | same | Pass |
| CR-1 / assumption: a stream ends after `cockpit.events.stream-timeout` (default 15 min, positive), and ends normally | SseEventHubTests `a stream uses the configured timeout`, `a stream that times out ends normally ...`, `the stream timeout must be positive`; EventsStreamTests `without configuration a stream ends after 15 minutes`; EventsAsyncDispatchTests | same | Pass. A real-time 15-min expiry over HTTP was not run: see "Unable to test" |
| CR-2 an event after `destroy()` does not fail its publisher | SseEventHubTests `an event after the hub is destroyed is dropped without failing the publisher` | same | Pass |
| CR-3 test streams don't stay connected in the shared context | EventsStreamTests `@AfterEach closeStreams`, EventsAsyncDispatchTests count check | same | Pass |
| Assumption: the ASYNC dispatch permit lets the stream-ending dispatch through; an anonymous REQUEST stays 401 | EventsAsyncDispatchTests, plus the two 401 tests | same | Pass |
| AC3 the package's tests pass (Testcontainers Postgres) | whole `core.events` package: 5 suites, 28 tests | same | Pass (28/28, 0 skipped) |
| AC4 `OpenApiContractTest` passes without the flag | OpenApiContractTest | same | Pass (1/1). I did not rerun the `UPDATE_OPENAPI=1` regeneration. `api/openapi.yaml` is unchanged after the run (`git status` clean apart from my test file) |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.events.*' --tests '*OpenApiContractTest'`

Result:

Pass: BUILD SUCCESSFUL in 30 s (2026-10-20 14:00:33-14:01:04 +02:00). ChangeEventTests 6, SseEventHubTests 14,
EventsStreamTests 5, EventsHttpStreamTests 2, EventsAsyncDispatchTests 1, OpenApiContractTest 1: 29 tests, 0 failures,
0 errors, 0 skipped (from `server/build/test-results/test/TEST-*.xml`). ktlintCheck passed with the new test.

## Full test execution

Command:

`cd server && ./gradlew test jacocoTestReport`

Result:

Pass: BUILD SUCCESSFUL in 1m 27s (2026-10-20 14:01:28-14:02:55 +02:00). 55 suites, 693 tests, 0 failures, 0 errors,
0 skipped (summed over `server/build/test-results/test/TEST-*.xml`). `:test` ran again, because the test selection
differed from the focused run.

## .taskcheck

Result:

Pass: `sh scripts/check.sh` (2026-10-20 14:03:24-14:04:00 +02:00) printed "== all checks passed". It covers the TFP gate
tests, server ktlint and tests, web eslint/tests/build, and android ktlint/unit tests/lint.

## Bugs found

None. No test exposed behavior that violates the acceptance criteria, the recorded assumptions or the fixed behavior of
CR-1..CR-3.

## Coverage

Status: verified (fresh run, exit status 0).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured for line counters, by intersecting JaCoCo line counters with the task diff. The four
`core/events` files are new in the task range, so every executable line is a changed line (49 + 15 + 7 + 4, all
covered). In `SecurityConfiguration.kt` the only changed executable line is 48 (`dispatcherTypeMatchers(... ASYNC)`;
lines 46-47 are comments). JaCoCo reports it covered (`ci=18, mi=0`). Total: 76/76 changed executable lines. I did not
derive changed-branch coverage from these counters.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `p0-events-sse`; base
  `55cc2905c332b3c7e37c43a1e7ad4dd737d41654` and commits `7a3bd4f`, `dbfbc2c`, `45549e2`, `b545396` from the parent,
  which match `git log 55cc290..HEAD`; HEAD `b54539653acd5f86a6f5ba4239f281899897b505`. File scope:
  `git diff --name-only 55cc290..HEAD -- server/src/main web/src android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `b545396` plus the uncommitted
  `SseEventHubTests.kt` (sha256 `21091d931465fbeb4bf3a69a44b6808f4242512bab4882424e97cb0871f5970d`). The `git diff`
  sha256 was `2a0177215cc6...`, the same before and after the runs. Other inputs are committed, sha256 prefixes:
  EventsController.kt `8a5153d80c84`, ChangeEvent.kt `380d915d0aed`, ChangeEventPublisher.kt `bb60397d9f92`,
  SseEventHub.kt `78c1b01be8d3`, SecurityConfiguration.kt `514a91fd8b64`, ChangeEventTests.kt `84a45ea37810`,
  EventsAsyncDispatchTests.kt `3fc17bac6605`, EventsHttpStreamTests.kt `00e570690965`, EventsStreamTests.kt `db4ba042f2b2`.
- Exact included files: the five `server/src/main/kotlin/ch/sonensei/cockpit/...` files in the table below. Excluded:
  `web/src/app/api/generated/**` (5 files generated by `npm run api:gen`, no hand-written behavior) and
  `api/openapi.yaml` (generated contract, checked by OpenApiContractTest). No files were renamed or deleted.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin as
  configured in `server/build.gradle.kts` (no toolVersion override); `./gradlew test jacocoTestReport` in `server/`;
  the full server test suite.
- Run start/completion time and exit status: 2026-10-20 14:01:28-14:02:55 +02:00; BUILD SUCCESSFUL, exit 0.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`
  (written 14:02:55.06 +02:00, sha256 `f67d8c9281ef115ae5601c6c6510b64192a69a63d6df9def39b0461c784293e2`), from
  `server/build/jacoco/test.exec` (written 14:02:53.61), plus `.../html/index.html`.
- Evidence outputs are fresh and inputs stayed unchanged: `:test` and `:jacocoTestReport` both ran in this invocation
  (neither was UP-TO-DATE), and both artifacts' timestamps fall inside the run. The report has 693 test results, the
  full suite and not the earlier 29-test focused run. The input hashes matched before and after.
- Final run after latest test additions and production fixes: yes. It ran on the final test files and on the
  production code after CR-1..CR-3 (`45549e2`). The first-pass report's numbers described the pre-fix revision
  `e1dd89e` and are replaced by these.

Values below use covered/total (percentage). "Statements" are JaCoCo INSTRUCTION counters; zero-denominator metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/core/events/domain/ChangeEvent.kt` | 79/79 (100%) | 4/4 (100%) | 7/7 (100%) | 15/15 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/events/domain/ChangeEventPublisher.kt` | 44/44 (100%) | N/A | 4/4 (100%) | 7/7 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/events/infra/SseEventHub.kt` | 274/295 (92.9%) | 4/4 (100%) | 15/15 (100%) | 49/49 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/events/api/EventsController.kt` | 10/10 (100%) | N/A | 2/2 (100%) | 4/4 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/infra/SecurityConfiguration.kt` | 225/227 (99.1%) | 2/4 (50%) | 10/10 (100%) | 34/34 (100%) |
| **Aggregate (summed counters, 5 files)** | 632/655 (96.5%) | 10/12 (83.3%) | 38/38 (100%) | 109/109 (100%) |

The aggregate leaves out only the generated web client files and `api/openapi.yaml` (see exclusions). No in-scope file
is missing from the report.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `SseEventHub.kt:62` (`runCatching { emitter.complete() }` in `onTimeout`, failure path; 7 instructions) | Only runs if `complete()` itself throws on a timed-out stream | Not material: a defensive guard. The success path (clean end, no 503) is covered by `a stream that times out ends normally ...` and EventsAsyncDispatchTests |
| `SseEventHub.kt:105` (`runCatching { emitter.complete() }` after a failed write, failure path; 7 instructions) | Only runs if completing a client that already failed to write throws | Not material: a defensive guard. The success path is covered by the event and heartbeat drop tests |
| `SseEventHub.kt:111` (`runCatching { it.complete() }` in `destroy()`, failure path; 7 instructions) | Only runs if completing a stream throws during shutdown | Not material: a defensive guard. The normal path is covered by `destroy completes every stream ...` |
| `SecurityConfiguration.kt:94` (2 of 4 branches of `(... as? CsrfToken)?.token`) | Pre-existing CSRF-cookie filter code, not changed by this task | Outside the task's change; this task added no gap there |

## Unable to test

- A real-time stream expiry over HTTP after the default 15 minutes, or after a short configured timeout on the embedded
  server. A short timeout needs its own `@SpringBootTest` context with a property override. The fixer tried that, and
  the extra context pushed the test JVM's Postgres container over `max_connections`, so the parent ruled it out. The
  behavior is covered instead by: the hub using the configured timeout, the application wiring the 15-min default,
  the timeout callback ending the stream cleanly, and the ASYNC dispatch after a timeout passing the real security chain.
  Follow-up only if a shared, low-timeout context becomes available.
- I did not test the 25 s default heartbeat in real time. The same code path runs at 50 ms, and the default
  (`cockpit.events.heartbeat:PT25S`) was checked by reading the code. No follow-up needed.
- I could not run `EventsAsyncDispatchTests` with the ASYNC permit reverted, because I must not change production code.
  Its claim that it would fail without the permit rests on reasoning (see the first-pass notes in `git show
  dbfbc2c:reviews/p0-events-sse/tests.md`). No follow-up needed.

## Summary

Tests added and all validation passed. This second pass found the CR-1..CR-3 fixes already covered by tests. It added
one test for the remaining gap: cleanup driven only by the heartbeat. Task-scoped coverage is sufficient, the remaining
gaps are justified defensive guards, and no production defects were found.
