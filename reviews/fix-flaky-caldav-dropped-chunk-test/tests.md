# Test Report

Date: 2026-11-02

Task: fix-flaky-caldav-dropped-chunk-test - Flaky test: CalDavClientTests 'a connection that fails after the answer started is not repeated' sometimes retries

Scope: base b065374b796099708fd0d94b660c06573a9f56c7 .. HEAD ca264324d (code commit 482a42fd08e07bf50e9437df5f2653031f07f1c7, test-only), plus one uncommitted test-writer edit to the same test file (see below). Branch main. The base, the code commit and "production unchanged by decision" came from the parent worker and were checked with `git diff --name-only b065374b7..HEAD -- server/src/main web/src android/app/src/main` (empty).

Requirements:
- tasks/fix-flaky-caldav-dropped-chunk-test.md (acceptance criteria, root-cause note, assumption)
- docs/ARCHITECTURE.md "Coverage"; AGENTS.md
- CalDavClient.kt `sendRetryingDrop` KDoc (a GET is retried only if it failed before any answer)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/caldav/CalDavClientTests.kt` (uncommitted, test-writer edit)

## Tests added or updated

Count: 1 (updated)

Test-writer change: in `a connection that fails after the answer started is not repeated`, removed
`Thread.sleep(DROPPED_CONNECTION_BACKOFF.toMillis() * 2)` before `connections.get() shouldBe 1` and added a comment
explaining why no wait is needed. `sendRetryingDrop` runs the backoff and the retry synchronously inside `send()`, and the
raw socket server counts a connection (`incrementAndGet`) before it writes any answer bytes. So by the time `send()`
throws or returns, any retry has already been counted. The fixed sleep could not make the assertion more reliable; it
only cost 400 ms per run.

Review of the test's determinism (commit 482a42fd0):
- The server reads the full request head (GET, no body) before it writes and closes. No unread input is left, so the
  close sends FIN rather than RST, and the client always receives the complete status line and headers before EOF
  (Content-Length 100, 2 bytes sent). The client invokes the BodyHandler (answered=true) and then fails with an
  IOException in the body. No path to "no answer" is left, unlike WireMock's MALFORMED_RESPONSE_CHUNK.
- The default HttpClient (HTTP_2 preferred) sends h2c upgrade headers on plain http. The server ignores them and answers
  HTTP/1.1, which the JDK accepts.
- A retry would get a complete 200 "ok". The test then fails twice over: no exception (shouldThrow), and connections=2.
- The accept thread is a daemon and ends when `use` closes the ServerSocket, so no thread or port leaks.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 root cause identified in Notes | n/a (documentation; the parent's probe is described in the task Notes) | n/a | Not testable (not a test concern); Notes checked as present and consistent with the code |
| AC2 deterministic: 200 runs in a row without failure | `a connection that fails after the answer started is not repeated` with a temporary `@RepeatedTest(300)` (removed again) | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.caldav.CalDavClientTests.a connection that fails after the answer started is not repeated'` | Pass: 300/300, 0 failures, 1.467 s (test-writer, final version without sleep). Parent's earlier run on 482a42fd0 (with sleep): 300/300, 131 s |
| AC2 still fails if the client retried after the answer started | same test, with the production mutation `if (!repeatable \|\| answered.get()) throw e` -> `if (!repeatable) throw e` (reverted) | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.caldav.CalDavClientTests'` | Pass (mutation killed): the test FAILED with "Expected exception CalendarProviderException but no exception was thrown"; 27 tests, 1 failed. Parent ran the same mutation on 482a42fd0 with the same result. CalDavClient.kt reverted (`git diff` empty, sha256 6cfc9bf0...) |
| AC3 other CalDavClientTests retry tests pass; `./gradlew check` passes | all 27 CalDavClientTests; full server suite | `cd server && ./gradlew check jacocoTestReport` | Pass: exit 0, 5205 tests, 0 failures/errors/skipped; CalDavClientTests 27/27 |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.caldav.CalDavClientTests.a connection that fails after the answer started is not repeated'` (temporary `@RepeatedTest(300)`, reverted)

Result:

Pass (300/300)

## Full test execution

Command:

`cd server && ./gradlew check jacocoTestReport` (2026-11-02 19:55:32 to 20:01:42 +0100)

Result:

Pass: BUILD SUCCESSFUL, exit 0; 5205 tests, 0 failed, 0 errors, 0 skipped (summed from build/test-results/test/*.xml); ktlint is included in check.

## .taskcheck

Result:

Not run by the test-writer. The change is a single server test file; the full server check (`./gradlew check`, the server
part of `scripts/check.sh`) passed. The parent runs `.taskcheck` via `tasks.py done`.

## Bugs found

None. Production behavior is consistent with its contract: a GET that gets no status line at all is retried by design
(cal-caldav-retry), and a GET whose answer started is not retried (the mutation run confirms that the test guards this).

## Coverage

Status: not applicable. The task changes no production file, so the task coverage scope is empty.

Metric: whole-file coverage of task-changed production files: N/A (no production files in scope; not reported as 100%).
Changed-line coverage: not measured (no production lines changed).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: fix-flaky-caldav-dropped-chunk-test; base b065374b796099708fd0d94b660c06573a9f56c7; HEAD ca264324d; code commit 482a42fd08e07bf50e9437df5f2653031f07f1c7 (from the parent, checked against `git log`/`git show --stat`); production diff base..HEAD empty
- Source/test snapshot identifier (including uncommitted inputs): CalDavClientTests.kt sha256 765d6a564fd8fad92dbedfeee9a755d6afba6d3f0304db505969768cc1d66569 (uncommitted test-writer edit); CalDavClient.kt sha256 6cfc9bf0c53b04b816ce7ec30410e67dd17527f17eeb1c00fafaf425c2b82ae0 (unchanged); `git diff` sha256 1af306b2cd94fbc25fe36cf08638342c7506d39acfa1117be526a9bfea008344
- Exact included files; renamed/deleted/excluded files and reasons: none included; the only changed file is a test file (excluded by definition)
- Provider/version, command, working directory, and test selection: JaCoCo (Gradle jacoco plugin); `./gradlew check jacocoTestReport`; server/; full server test suite
- Run start/completion time and exit status: 2026-11-02 19:55:32 to 20:01:42 +0100, exit 0
- Generated report paths and report hashes/run identifier: server/build/reports/jacoco/test/jacocoTestReport.xml, written 20:01:41, sha256 c2e9c2bd3af96f3398b2bbf9d2ada1448d8fe3652b4068a82e223a61de37d983
- Evidence outputs are fresh and inputs stayed unchanged: yes; the hashes of both files were the same before and after the run
- Final run after latest test additions and production fixes: yes (no production fixes)

Informational only (not task coverage, as the file is unchanged): in that run, CalDavClient.kt had LINE 162/168,
BRANCH 112/118, INSTRUCTION 1146/1173, METHOD 36/36, and no missed instructions or branches in `sendRetryingDrop`
(lines 201-227), so both the "answered" and the "not answered" branches are exercised.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| (none: no production files changed) | N/A | N/A | N/A | N/A |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| none in scope | n/a | n/a |

## Unable to test

- The original intermittent CI failure (about 1 in 2000 under WireMock) cannot be reproduced against the new test by design:
  the new server never closes before the headers. The parent's probe described in the task Notes covers the old behavior.
  No follow-up needed.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. (One test refined: redundant fixed sleep
removed; the test is uncommitted, so the parent must commit it, and the parallel code review saw the version with the sleep.)
