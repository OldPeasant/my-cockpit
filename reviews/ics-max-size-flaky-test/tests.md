# Test Report

Date: 2026-10-26

Task: ics-max-size-flaky-test - IcsFeedClient: refusing an oversized Content-Length sometimes surfaces as IOException ('could not be reached') instead of 'larger than'

Scope: `git diff 1c1b4a592bc7fd87df935362becd5062b9709efb..32cfd157e481140be41ef33009c55de2b340194c` (task commits e071cc836be0f7c45a71ca9dfbda49834e1ad41d and 32cfd157e481140be41ef33009c55de2b340194c, supplied by the parent worker). Branch `main`, working tree clean, no uncommitted inputs.

Requirements:
- `tasks/ics-max-size-flaky-test.md` (Goal and acceptance criteria)
- `AGENTS.md` project notes (test conventions, `.taskcheck` = `sh scripts/check.sh`)
- Parent instructions: Gradle cannot run inside sub-agents. The parent runs the tests.

## Test files changed

- None by the test-writer. The task's own commits changed `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/ics/IcsFeedClientTests.kt`, and this report reviews those tests.

## Tests added or updated

Count: 0 by the test-writer. The task commits added 2 tests, reviewed here:
- `a refused Content-Length is reported as too large every time, not as the failure of the cancelled exchange` (50 refused fetches with one client)
- `a body refused while it is still streaming in is reported as too large every time, not as the cancelled exchange` (20 refused fetches of a 1 MiB dribbled body, limit 16 KB)

The existing test `a feed of exactly the maximum size is read, a larger Content-Length is refused before reading` was not changed or weakened.

I added no further tests. The JDK `HttpClient` is built inside `IcsFeedClient` and cannot be injected. Forcing the race order without a timing dependency (the exchange's IOException completing the future before the body's refusal) would need a production change, which the test-writer is not allowed to make. A test that reuses one client for a refused fetch and then a successful fetch would add little. The `refusal` reference is a local in `send`, created per request, so it cannot leak between requests.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: an oversized Content-Length is always reported as "larger than" (the refusal's error wins over the IOException of the cancelled exchange) | `a refused Content-Length is reported as too large every time, ...`; `a feed of exactly the maximum size is read, a larger Content-Length is refused before reading`; `a body refused while it is still streaming in is reported as too large every time, ...` (the same race on the mid-stream path) | `cd server && ./gradlew test --tests '*IcsFeedClientTests'` (run by the parent on HEAD 32cfd157e) | Pass (reported by the parent: BUILD SUCCESSFUL, no failures). Caveat: the repetitions catch the race only by probability. A pass does not prove that the IOException-first order happened during the run. |
| AC1: errors that are not refusals keep their mapping (`refusal` is null, so the `when` still runs) | `a dropped connection and a closed port are unreachable servers`, the timeout and interrupt tests, the invalid-gzip test (all pre-existing) | same as above | Pass (reported by the parent) |
| AC2: `IcsFeedClientTests` passes reliably inside the full `server/gradlew -p server check` run (several consecutive runs) | whole `IcsFeedClientTests` class within `./gradlew -p server check` | `./gradlew -p server check`, repeated several times | Not run: Gradle cannot run in this sub-agent. The parent must run it and record the evidence. |

## Focused test execution

Command:

`cd server && ./gradlew test --tests '*IcsFeedClientTests'` (run by the parent worker, not by the test-writer)

Result:

Pass (parent's report: BUILD SUCCESSFUL, no failures, HEAD 32cfd157e)

## Full test execution

Command:

`./gradlew -p server check` (several consecutive runs, as AC2 requires)

Result:

Not run by the test-writer: Gradle cannot run in sub-agents in this environment. The parent must run it.

## .taskcheck

Result:

Not run by the test-writer, for the same reason. The parent must run `sh scripts/check.sh`.

## Bugs found

None. Code inspection found no defect.
- `refusal` is set only in the `tooLarge` callback of a 2xx `LimitedBody`. Non-2xx bodies use `replacing`, so a redirect hop cannot set it.
- When the refusal is set, `LimitedBody.result` completes exceptionally, so the future can never succeed with a refused body.
- `refusal.get() ?: when (...)` only takes priority in the `ExecutionException` branch. A timeout or interrupt that arrives before the exchange fails still reports a timeout or an interrupt, which is correct for those cases.
- The decompressed-size check in `text()` (line 247) throws `tooLarge()` directly. It does not depend on `refusal` and the task did not change it.

## Coverage

Status: unavailable. Gradle (and so JaCoCo, the project's coverage tooling, see `docs/ARCHITECTURE.md` "Coverage") cannot run in this sub-agent, and the parent did not run a coverage task. Below is a reasoned analysis of which changed lines the tests exercise. It is not a measurement.

Metric: whole-file coverage of task-changed production files. Not measured.
Changed-line coverage: not measured. Reasoned analysis below.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `ics-max-size-flaky-test`; base 1c1b4a592bc7fd87df935362becd5062b9709efb; HEAD 32cfd157e481140be41ef33009c55de2b340194c; commits e071cc836, 32cfd157e (supplied by the parent and confirmed by `git diff 1c1b4a592..HEAD --stat`: 2 files)
- Source/test snapshot identifier (including uncommitted inputs): clean tree at 32cfd157e. Blob hashes: `IcsFeedClient.kt` 5dbb6197f73393b1b0666e4af300122088b07b00, `IcsFeedClientTests.kt` fc7795c3e31ee1f03c085c36ddf1cee04202ac25
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/ics/IcsFeedClient.kt`. Excluded `IcsFeedClientTests.kt` (test file). No renames or deletions.
- Provider/version, command, working directory, and test selection: none run (Gradle is unavailable to sub-agents)
- Run start/completion time and exit status: N/A
- Generated report paths and report hashes/run identifier: N/A
- Evidence outputs are fresh and inputs stayed unchanged: N/A (no measurement)
- Final run after latest test additions and production fixes: no. No coverage run exists for any revision.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/ics/IcsFeedClient.kt` | not measured | not measured | not measured | not measured |

Reasoned changed-line analysis (from reading the tests and code, not measured):

| File and changed lines | Required behavior or risk | Exercised by / reason |
|---|---|---|
| `IcsFeedClient.kt:159` `val refusal = AtomicReference...` and `:174` `sendAsync(request) { info -> body(info, refusal) }` | every send | Run by every test that reaches `send` |
| `IcsFeedClient.kt:188` `refusal.get() ?:`, non-null side | AC1: the refusal wins | Both refusal tests, the exact-max test, and the existing mid-stream/no-Content-Length refusal tests, whenever the exchange fails with `ExecutionException` |
| `IcsFeedClient.kt:188`, non-null side while the cause is the client's IOException (the actual race) | AC1: the flaky case | Probabilistic only: the 50 and 20 repetitions make it likely but do not force it. It cannot be made deterministic without an injectable `HttpClient` (a production change). Justified gap. |
| `IcsFeedClient.kt:188`, null side (the `when` mapping) | non-refusal errors keep their messages | `a dropped connection and a closed port are unreachable servers`, the invalid-gzip test ("could not be read"), and the HTTP-timeout tests |
| `IcsFeedClient.kt:204-211` `body(info, refusal)` with `tooLarge().also(refusal::set)` | the refusal is recorded at once and mid-stream | Content-Length refusal (`refuseAtOnce`) and mid-stream refusal tests. Non-2xx early return: redirect/404 tests (pre-existing) |
| `IcsFeedClient.kt:342` (comment only) | none | not executable |

Follow-up: if the parent wants measured numbers, run the JaCoCo task from `docs/ARCHITECTURE.md` "Coverage" and add the `IcsFeedClient.kt` counters.

## Unable to test

- Forcing the IOException-first ordering by design: `HttpClient` is created inside `IcsFeedClient` and is not injectable. The repetition tests are probabilistic regression guards. Before the fix, the failure showed up under full-suite load, not when the class ran alone, so passing in isolation is weak evidence for AC1. AC2's repeated full `check` runs are the stronger evidence. Follow-up: only if the flake comes back.
- AC2 (several consecutive full `./gradlew -p server check` runs): not runnable in the sub-agent. The parent must run it.
- Coverage measurement: Gradle is not available to the sub-agent (see Coverage).

## Summary

No additional tests were necessary for the reviewed scope. The task's 2 repetition tests and the existing tests cover AC1's behavior on both sides of the `refusal.get() ?:` change, with no production defects found. The race itself is only exercised by probability. AC2, `.taskcheck` and coverage were not run by the test-writer (Gradle is unavailable in sub-agents) and remain for the parent.
