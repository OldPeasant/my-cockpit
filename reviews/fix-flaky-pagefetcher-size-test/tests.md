# Test Report

Date: 2026-11-08

Task: fix-flaky-pagefetcher-size-test - Flaky test: PageFetcherSizeLimitTests HTTP/2 reuse case gets TIMEOUT for some of 50 fetches

Scope: base 382ad9732ddb5dcdb02926378b2f3adbbb984cda .. task commit 85d491a81c63b948fd96c5fad7d9b4afde53ab63 (rebased as a62dd2f76a7fa6ccc8ef1b77bc79e2403cea6def = HEAD; `git diff 85d491a81 a62dd2f76 -- server` is empty). Production file: `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/infra/PageFetcher.kt`. Test file: `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/PageFetcherSizeLimitTests.kt`. Plus one uncommitted test-writer change to that test file (below).

Requirements:
- `tasks/fix-flaky-pagefetcher-size-test.md` acceptance criteria
- parent instructions (check test adequacy, add only for a real gap)
- `docs/ARCHITECTURE.md` "Coverage"

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/PageFetcherSizeLimitTests.kt`

## Tests added or updated

Count: 1 (strengthened, not weakened)

`pages are fetched over HTTP 1_1, not HTTP 2, so a cancelled download cannot break a shared connection`
now fetches twice and asserts both WireMock serve events are `HTTP/1.1` (was: one fetch, `.single()`).

Why: the one-fetch version did not detect a regression. A temporary probe test (created, run, then deleted)
used a default `HttpClient.newHttpClient()` against the same WireMock setup and showed
`client=[HTTP_2, HTTP_2] wiremock=[HTTP/1.1, HTTP/2.0]`: the JDK client did talk HTTP/2 (h2c upgrade), but
WireMock records the upgrade request itself as `HTTP/1.1`; only the second request on that connection shows
as `HTTP/2.0`. So removing `.version(HttpClient.Version.HTTP_1_1)` from `PageFetcher` would have left the
original assertion green. With two fetches the second event would be `HTTP/2.0` and the test fails. (I could not
remove the production line to confirm directly, since test-writers must not touch production code; the probe
reproduces exactly the client configuration without the version line.)

Other existing tests reviewed, no gap found:
- HTTP/1.1 truncation still closes the connection: `over HTTP 1_1 a page larger than max-size is cut off and its connection is closed, not left open` (raw socket server, waits for the client close).
- Repeated truncation on one client: `pages larger than max-size are cut off reliably, also many in a row over the same client` (REPEATS=200).
- Exact/below max-size, gzip cut-off, mid-body close, non-HTML, error page and redirect-with-large-body tests still pass over HTTP/1.1.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 root cause fixed (HTTP/1.1 forced, no retry) | `pages are fetched over HTTP 1_1, not HTTP 2, ...` (strengthened); `pages larger than max-size are cut off reliably, also many in a row over the same client`; `over HTTP 1_1 a page larger than max-size is cut off and its connection is closed, not left open` | `cd server && ./gradlew test --tests '*PageFetcher*' jacocoTestReport ktlintTestSourceSetCheck --rerun-tasks -q` | Pass |
| AC2 `./gradlew test --tests '*PageFetcherSizeLimitTests*'` passes 10 runs in a row | whole class | not re-run 10x by the test-writer (parent recorded 10/10 at 85d491a81); 1 run of the class with the strengthened test passed | Pass (1 run here); 10x loop: Not run by test-writer, parent's evidence |
| AC3 `sh scripts/check.sh` passes | all | not run by test-writer (parent runs `.taskcheck`) | Not run: left to the parent worker |

## Focused test execution

Command:

`cd server && ./gradlew test --tests '*PageFetcher*' jacocoTestReport ktlintTestSourceSetCheck --rerun-tasks -q`

Result:

Pass (exit 0): PageFetcherSizeLimitTests 10/10, PageFetcherEdgeCaseTests 12/12, PageFetcherTests 13/13, 0 failures/errors; ktlint on test sources clean.

## Full test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.bookmarks.*' -q`

Result:

Pass (exit 0): 14 test classes, 179 tests, 0 failures/errors. The full server suite / `scripts/check.sh` was not run (parent runs `.taskcheck`).

## .taskcheck

Result:

Not run (left to the parent worker).

## Bugs found

None.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: the only changed executable line is `PageFetcher.kt:76` (`.version(HttpClient.Version.HTTP_1_1)`); JaCoCo line 76: 2/2 instructions covered (1/1 line). Lines 73-75, 208 and 302 changed are comments (non-executable). Method: intersect `git diff 382ad9732..85d491a81` added lines with JaCoCo XML `line` elements.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: fix-flaky-pagefetcher-size-test; base 382ad9732ddb5dcdb02926378b2f3adbbb984cda; HEAD a62dd2f76a7fa6ccc8ef1b77bc79e2403cea6def; task commit 85d491a81 given by parent, server diff base..HEAD equals that commit's diff.
- Source/test snapshot identifier (including uncommitted inputs): sha256 PageFetcher.kt 669a91ef9db59d7970fb218bad2cd84a131db1a8b812d5e6b76c080e311bf01f (committed); PageFetcherSizeLimitTests.kt 1a726c5a577bd749498febedf6db47a1e280df50a92a5bbd407461d64da68cff (uncommitted, includes the strengthened test). Same hashes before and after the run.
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/infra/PageFetcher.kt`; nothing excluded, renamed or deleted.
- Provider/version, command, working directory, and test selection: JaCoCo via Gradle `jacoco` plugin; `./gradlew test --tests '*PageFetcher*' jacocoTestReport ktlintTestSourceSetCheck --rerun-tasks -q` in `server/`; selection: PageFetcherTests, PageFetcherEdgeCaseTests, PageFetcherSizeLimitTests.
- Run start/completion time and exit status: 2026-11-08T23:46:39+01:00 to 23:47:29+01:00, exit 0.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` (mtime 23:47), sha256 70eb8ea1dfc1e4accbe74258c73bd0a4b2a78adf55f96d9ebdd1a3bef755d214.
- Evidence outputs are fresh and inputs stayed unchanged: yes (`--rerun-tasks`, report rewritten by this run, input hashes identical before/after).
- Final run after latest test additions and production fixes: yes (no production fixes; the test change was in place).

Values below use covered/total (percentage).

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/infra/PageFetcher.kt` | 1112/1190 (93.4%) | 83/93 (89.2%) | 36/40 (90.0%) | 169/182 (92.9%) |

Aggregate equals the single file; no missing or unmeasured files.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `PageFetcher.kt:130-131, 160-163, 188-189, 196-199, 201-203 (partial), 215, 227, 290-294, 319, 343, 93, 112, 141, 144` | Pre-existing error paths (interrupted lookups/sends, invalid request URI, deadline exhausted, exception classification, defensive branches) | Not touched by this task (only line 76 changed); no test added, out of scope |

## Unable to test

- Direct mutation check (remove line 76 and see the HTTP/1.1 test fail) was not performed, because test-writers must not edit production code; a temporary probe with a default client gave equivalent evidence (see above). No follow-up needed.
- The original flake is probabilistic (about 1 in 500 fetches); REPEATS=200 makes a regression to HTTP/2 visible only sometimes, which is why the deterministic protocol assertion matters.

## Summary

Tests added and all validation passed. One existing test was strengthened because, as written, it could not detect a regression back to HTTP/2; no production defects found.
