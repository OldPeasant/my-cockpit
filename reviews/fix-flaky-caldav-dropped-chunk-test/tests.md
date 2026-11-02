# Test Report

Date: 2026-11-02

Task: fix-flaky-caldav-dropped-chunk-test - Flaky test: CalDavClientTests 'a connection that fails after the answer started is not repeated' sometimes retries

Scope: base b065374b796099708fd0d94b660c06573a9f56c7 .. reviewed revision 98942ff8b2cdd749995ba4eaff9f44e88f5bdb65 (final code; test-only). Task code commits: 482a42fd0 (new raw-socket test), f96d0a580 (test-writer's sleep removal, committed by the parent), 98942ff8b (ServerSocket bound to `InetAddress.getByName("127.0.0.1")`, review finding CR-2, so server and client both use the IPv4 loopback). Branch main, no uncommitted source or test changes during the runs. The base, the commits and "production unchanged by decision" came from the parent worker and were checked with `git show --stat` and `git diff --name-only b065374b7..98942ff8b -- server/src/main web/src android/app/src/main` (empty).

This is the final re-run on 98942ff8b. The earlier run (on f96d0a580's content) is superseded; its results are kept below where they are still informative.

Requirements:
- tasks/fix-flaky-caldav-dropped-chunk-test.md (acceptance criteria, root-cause note, assumption)
- docs/ARCHITECTURE.md "Coverage"; AGENTS.md
- CalDavClient.kt `sendRetryingDrop` KDoc (a GET is retried only if it failed before any answer)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/caldav/CalDavClientTests.kt` (all changes committed; in this final run the test-writer only made temporary edits, reverted, see below)

## Tests added or updated

Count: 1 (updated; `a connection that fails after the answer started is not repeated`)

Final run on 98942ff8b: no new test changes. The only code change since the previous run is the ServerSocket bind address
(`InetAddress.getLoopbackAddress()` -> `InetAddress.getByName("127.0.0.1")`), which matches the client URL
`http://127.0.0.1:${server.localPort}/`. With the loopback address resolved from the system, the server could listen on ::1 while
the client connects to 127.0.0.1 on hosts where IPv6 is preferred; the fixed IPv4 literal removes that dependency. The
determinism argument from the previous run is unchanged:
- The server reads the full request head (GET, no body) before it writes and closes. No unread input is left, so the
  close sends FIN rather than RST, and the client always receives the complete status line and headers before EOF
  (Content-Length 100, 2 bytes sent). The client invokes the BodyHandler (answered=true) and then fails with an
  IOException in the body. No path to "no answer" is left, unlike WireMock's MALFORMED_RESPONSE_CHUNK.
- The server counts a connection before it writes, and `sendRetryingDrop` runs backoff and retry synchronously inside
  `send()`, so `connections.get() shouldBe 1` needs no wait (the sleep was removed in f96d0a580).
- A retry would get a complete 200 "ok". The test then fails twice over: no exception (shouldThrow), and connections=2.
- The accept thread is a daemon and ends when `use` closes the ServerSocket, so no thread or port leaks.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 root cause identified in Notes | n/a (documentation; the parent's probe is described in the task Notes) | n/a | Not testable (not a test concern); Notes checked as present and consistent with the code |
| AC2 deterministic: 200 runs in a row without failure | `a connection that fails after the answer started is not repeated` with a temporary `@RepeatedTest(200)` (reverted; file sha256 back to 29c2ec4a...) | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.caldav.CalDavClientTests.a connection that fails after the answer started is not repeated'` | Pass on 98942ff8b: 200/200, 0 failures, 0 errors, 1.288 s (TEST-...CalDavClientTests.xml, 2026-11-02 20:03:57 +0100). Earlier runs: 300/300 on f96d0a580 content, 300/300 on 482a42fd0 (parent) |
| AC2 still fails if the client retried after the answer started | same test, with the production mutation `if (!repeatable \|\| answered.get()) throw e` -> `if (!repeatable) throw e` in CalDavClient.kt:217 (reverted) | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.caldav.CalDavClientTests'` | Pass (mutation killed) on 98942ff8b: the test FAILED with "Expected exception ch.sonensei.cockpit.calendar.sync.CalendarProviderException but no exception was thrown"; 27 tests, 1 failed. CalDavClient.kt restored: `git diff` empty, sha256 6cfc9bf0... (unchanged) |
| AC3 other CalDavClientTests retry tests pass; `./gradlew check` passes | all 27 CalDavClientTests; full server suite | `cd server && ./gradlew check jacocoTestReport` | Pass on 98942ff8b: exit 0, 5205 tests, 0 failures/errors/skipped; CalDavClientTests 27/27 |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.caldav.CalDavClientTests.a connection that fails after the answer started is not repeated'` (temporary `@RepeatedTest(200)`, reverted)

Result:

Pass (200/200 on 98942ff8b)

## Full test execution

Command:

`cd server && ./gradlew check jacocoTestReport` (2026-11-02 20:04:15 to 20:10:40 +0100, on 98942ff8b)

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
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: fix-flaky-caldav-dropped-chunk-test; base b065374b796099708fd0d94b660c06573a9f56c7; measured code revision 98942ff8b2cdd749995ba4eaff9f44e88f5bdb65 (HEAD moved during the run to ed37e1abf, a reviews/-only commit by the code reviewer: `git diff --stat 98942ff8b ed37e1abf` touches only review.md); code commits 482a42fd0, f96d0a580, 98942ff8b (from the parent, checked with `git show --stat`); production diff base..98942ff8b empty
- Source/test snapshot identifier (including uncommitted inputs): CalDavClientTests.kt sha256 29c2ec4a6e8e6e1657517721a4bf977007423c0735b57134d0a71fbdba20bb0b (committed content of 98942ff8b); CalDavClient.kt sha256 6cfc9bf0c53b04b816ce7ec30410e67dd17527f17eeb1c00fafaf425c2b82ae0 (unchanged); no uncommitted source or test changes during the full run
- Exact included files; renamed/deleted/excluded files and reasons: none included; the only changed file is a test file (excluded by definition)
- Provider/version, command, working directory, and test selection: JaCoCo (Gradle jacoco plugin); `./gradlew check jacocoTestReport`; server/; full server test suite (build/test-results/test removed before the run)
- Run start/completion time and exit status: 2026-11-02 20:04:15 to 20:10:40 +0100, exit 0
- Generated report paths and report hashes/run identifier: server/build/reports/jacoco/test/jacocoTestReport.xml, written 20:10:39, sha256 6097d213eb9e1a3060461f745e844d39643eb30de30512f3124fa2f4ac74c545
- Evidence outputs are fresh and inputs stayed unchanged: yes; the hashes of both files were the same before and after the run
- Final run after latest test additions and production fixes: yes (final code revision 98942ff8b; no production fixes)

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

Tests and task-scoped coverage are sufficient; no production defects found. Final re-run on reviewed revision
98942ff8b: 200/200 repeated runs pass, the production mutation is killed, and the full server check passes.
