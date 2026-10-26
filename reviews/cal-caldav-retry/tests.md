# Test Report

Date: 2026-10-26

Task: cal-caldav-retry - CalDAV: survive dropped connections (flaky Radicale integration tests)

Scope: `git diff f21b2bb9801928098c169801b945e1b2efc5a173..49d295b7110dc19a18c198a2438d52b1a31e540b -- server`
(task commits b2afa35f7 and 88d807e24, the rebased forms of 73e33659c and 4c932e201 named by the parent, plus
49d295b71, which commits this report's test additions to `CalDavClientTests.kt`; no uncommitted source or test
changes remain).

Requirements:
- `tasks/cal-caldav-retry.md` acceptance criteria
- AGENTS.md project notes (no secrets or bodies in INFO logs, fictitious test data)
- `docs/ARCHITECTURE.md` "Coverage" (coverage via the existing Gradle tooling)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/caldav/CalDavClientTests.kt`

## Tests added or updated

Count: 5 added (in the "dropped connections" section, next to the parent's 7 tests), plus a test helper
`ScriptedHttpClient` (a real `CalDavClient.httpClient()` whose `send` first runs a scripted step that may throw).

- `the second attempt waits the backoff and its answer goes through the usual checks`: drop, then 503: the retry
  answer is still checked (HTTP 503 provider failure, no body in the message), 2 requests, and at least
  `DROPPED_CONNECTION_BACKOFF` elapsed (lower bound only, deterministic).
- `a drop at a redirect target is sent once more to that target with method, body and password`: 307 to a target
  that drops once: the redirect source is requested once, the target twice, both with the REPORT body, Depth and
  Authorization (the body publisher is reused on retry).
- `an unconditional write that drops at a redirect target is not repeated`: PUT without precondition, redirected,
  target drops: provider failure, target requested once.
- `an interrupt during the backoff is a provider failure, keeps the interrupt flag and sends nothing more`: the
  first send throws `EOFException` with the thread interrupted, so the backoff `Thread.sleep` is interrupted:
  "Reading: interrupted", interrupt flag restored, exactly 1 send, nothing reached the server. Deterministic (no
  timing race), via `ScriptedHttpClient`.
- `a retry is logged with method and host only, never the password or the body`: the INFO retry line contains the
  method and host; no `CalDavClient` log line contains the password, the Basic credential or an event-body marker.

The parent's existing tests were reviewed and kept unchanged; they cover the criterion's method matrix (GET,
PROPFIND, REPORT, PUT/DELETE with If-Match / If-None-Match, header-name case), non-repeatable methods (PUT, DELETE,
MKCALENDAR, POST), `EMPTY_RESPONSE` and `CONNECTION_RESET_BY_PEER`, retry exactly once, no retry after the answer
started (`MALFORMED_RESPONSE_CHUNK`), no retry on timeout, and `repeatable()` directly.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: retry once (short backoff) when the connection fails before any response, only for PROPFIND, REPORT, GET and PUT/DELETE with If-Match/If-None-Match | parent: `reads and conditional writes are sent once more...`, `a connection reset before the answer is retried...`, `a request is retried only once...`, `writes without a precondition and other methods are not repeated`, `only reading methods and writes with a precondition are repeatable`; added: `the second attempt waits the backoff...`, `a drop at a redirect target is sent once more...`, `an unconditional write that drops at a redirect target is not repeated`, `an interrupt during the backoff...`, `a retry is logged with method and host only...` | `cd server && ./gradlew ktlintFormat ktlintCheck test --tests '*CalDavClientTests'` | Pass (parent run on the final tests: 27 tests, 0 failures) |
| AC1: no retry after a response was received | parent: `a connection that fails after the answer started is not repeated` | same | Pass (parent run, 27 tests) |
| AC1: no retry on timeouts | parent: `a timeout is not repeated` | same | Pass (parent run, 27 tests) |
| AC2: `dav()` in `CalDavRadicaleSyncTests` retries the same way | `CalDavRadicaleSyncTests` (all 10, via `dav()` in `@BeforeEach`) | `cd server && ./gradlew integrationTest --tests '*CalDavRadicaleSyncTests' --rerun` | Pass (parent run). The helper reuses `sendRetryingDrop`, so the unit tests above cover its retry logic |
| AC3: integration suite passes 10 times in a row | `CalDavRadicaleSyncTests` | same, 10 runs | Pass 10/10 (parent run; the first container create hit a Podman "Broken pipe" and `withStartupAttempts(2)` recovered). The cause notes in the task file are the parent's to check, not testable |

## Focused test execution

Command:

`cd server && ./gradlew ktlintFormat ktlintCheck test --tests '*CalDavClientTests'`

Result:

Pass: 27 tests, 0 failures, ktlint clean. The parent ran it on the final tests, including the 5 added here
(sub-agents can't run Gradle in this environment). `ktlintFormat` changed nothing: the committed test file is
the blob written here (7e851f5cdc068ff13e13f581dad113c00fe7c1f0).

Before the additions the parent had: 22 tests, 0 failures; and
`cd server && ./gradlew integrationTest --tests '*CalDavRadicaleSyncTests' --rerun` -> 10/10 passed.

## Full test execution

Command:

`sh scripts/check.sh`

Result:

Not run by the test-writer (Gradle not available to sub-agents); the parent runs it as `.taskcheck`.

## .taskcheck

Result:

Not run (same reason; the parent runs it before `done`).

## Bugs found

None. No production defect found while reviewing `sendRetryingDrop`, `repeatable` and `send` against the criteria.

Observations, not defects:
- `ConnectException` (connection refused) is also retried once for repeatable requests. This fits "the connection
  fails before any response arrives".
- `HttpConnectTimeoutException` is a subclass of `HttpTimeoutException`, so connect timeouts are not retried either,
  which matches "no retry on timeouts".

## Coverage

Status: verified. The parent ran the measurement; the test-writer read the generated reports.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: 27/27 executable added or modified lines fully covered, and every branch on them covered.
Method: added/modified lines from `git diff -U0 f21b2bb98..HEAD -- .../CalDavClient.kt` (hunks at lines 4, 15, 18,
34, 78-79, 95, 104, 166-179, 190-227), intersected with the executable lines JaCoCo marks in the HTML report.
All 27 are `fc`, and the 3 with branches (178, 179, 217) are `bfc`. Comment, import and declaration-only lines have
no JaCoCo line and are not counted.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: cal-caldav-retry, base
  f21b2bb9801928098c169801b945e1b2efc5a173, HEAD 49d295b7110dc19a18c198a2438d52b1a31e540b. Task commits b2afa35f7
  and 88d807e24 (the rebased 73e33659c and 4c932e201 from the parent's prompt) and 49d295b71 (tests), taken from
  `git log` and the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 49d295b71, no uncommitted source or test
  changes. Blobs: `CalDavClient.kt` ea573b7d51921f79dda537833f4d06b847013851, `CalDavClientTests.kt`
  7e851f5cdc068ff13e13f581dad113c00fe7c1f0.
- Exact included files; renamed/deleted/excluded files and reasons: included
  `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/caldav/CalDavClient.kt`, the only production file
  changed. Excluded: `CalDavClientTests.kt` and `CalDavRadicaleSyncTests.kt` (tests). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo through the project's existing Gradle
  `jacocoTestReport`. The version is the one pinned in `server/build.gradle.kts`; the test-writer did not read
  it. Command `./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.caldav.*' jacocoTestReport` in `server/`,
  run by the parent. The selection is the unit `test` task for package `calendar.infra.caldav`; the Radicale
  integration tests are not in it.
- Run start/completion time and exit status: completed 2026-10-26 00:46 (+01:00, report file mtimes). Exit status
  0 as reported by the parent (tests passed). The exact start time was not reported.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`
  (sha256 ff574b9f327ac6290606ec6b38029a5febd8be715b7ab571847807f8791e95b8);
  `server/build/reports/jacoco/test/html/ch.sonensei.cockpit.calendar.infra.caldav/CalDavClient.kt.html`
  (sha256 01d1ca4ac8e4d2dd1d4672178a4555ec43c47502b58c28477457259f2ff2050a).
- Evidence outputs are fresh and inputs stayed unchanged: the reports were written after the 5 tests were added
  (00:46, after this report's first version at 00:43). The test blob in HEAD equals the one written here, and
  `CalDavClient.kt` is unchanged since 88d807e24.
- Final run after latest test additions and production fixes: yes. No production fixes were needed.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. JaCoCo has no
statement counter, so the Statements column shows JaCoCo INSTRUCTION counts.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/caldav/CalDavClient.kt` | 1146/1173 (97.7%) | 112/118 (94.9%) | 36/36 (100%) | 162/168 (96.4%) |

Classes: 7/7. Aggregate equals the single file above: there are no unmeasured or excluded production files.

Every remaining gap is in code this task did not change; none is on a task-changed line.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CalDavClient.kt:56-57` (`resolve`: `IllegalArgumentException` catch) | pre-existing defensive path for hrefs `URI.resolve` rejects | not task scope; left uncovered |
| `CalDavClient.kt:44` (1 of 4 branches), `:59` (1 of 10), `:123` (1 of 2, `body()` null) | pre-existing `ok`, origin-check short-circuit, and null-body guard | not task scope; the null body can't happen with `BodyHandlers.ofString` |
| `CalDavClient.kt:275-278` (`MultiStatus.parse`: `IOException` / `ParserConfigurationException` catches) | pre-existing parser failure paths, not reachable with in-memory input | not task scope; left uncovered |
| `CalDavClient.kt:297` (1 of 8), `:308` (2 of 6) | pre-existing propstat status and sync-token edge branches | not task scope; left uncovered |
| `CalDavClient.kt` `sendRetryingDrop` interrupted backoff, retry in redirect loop, retry log line | task behavior (AC1, AGENTS.md logging rule) | covered: `an interrupt during the backoff...`, the two redirect tests, `a retry is logged with method and host only...` |

## Unable to test

- AC3's root-cause note: documentation in the task file, not testable.
- `.taskcheck` (`sh scripts/check.sh`): the test-writer can't run Gradle. The parent runs it before `done`. No
  follow-up beyond that.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. 5 tests were added (plus a scripted
HttpClient helper), and all 27 `CalDavClientTests` pass. Every task-changed executable line and branch of
`CalDavClient.kt` is covered; the remaining whole-file gaps are pre-existing code outside the task.
