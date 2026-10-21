# Test Report

Date: 2026-10-21

Task: cal-ics ICS feed adapter

Scope: task commits bdc5b2b03a2321e2881d2f61738ad72cbb39fd28..d04161cff711dcb3654dfd41847c952c6fbaf3d2 on `main`
(production package `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/ics`, tests in
`server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/ics`), plus the one uncommitted test change listed below.
The parent named HEAD 67bb2f98. That commit is not an ancestor of the current HEAD d04161cf (the branch was rebased),
but `git diff 67bb2f98 HEAD` over both scope folders is empty, so the in-scope code is identical.

Requirements:
- `tasks/cal-ics.md` acceptance criteria and its recorded assumptions (config `{"url"}`, webcal as https, SSRF via
  `PublicAddresses` with `allow-private-networks` / `allowed-private-hosts`, tolerant parser, content-hash etag,
  `complete=false` when an event cannot be read)
- SPEC.md §6.1 (read-only ICS subscriptions), as cited by the task
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/ics/IcsFeedSyncTests.kt`

## Tests added or updated

Count: 1

- `IcsFeedSyncTests.an event that ends before it starts cannot be stored, keeps its local copy and is reported, the others are stored`:
  the feed changes an event so that its DTEND is before its DTSTART. The parser reads it, but the sync engine refuses to
  store it. The test checks that the local copy keeps its old content, that the next event in the same download is
  still updated, and that `lastError` reports the event that could not be stored. Before this test, no test covered
  the hand-off between an ICS event the parser reads and an event the store refuses ("parse, upsert").

Earlier test-writer rounds had already covered the rest of the task: 122 tests in 5 classes. I reviewed them against
the criteria and found no other meaningful gap (see Coverage).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: Fetch with ETag/Last-Modified | `IcsFeedClientTests`: `a first download has no validators...`, `the validators are sent back and 304 is not modified`, `only the validators that exist are sent...`; `IcsFeedAdapterTests`: `the cursor is sent back as validators and 304 keeps it...`, `a cursor with only one validator...`, `a feed without validators gives no cursor...`; `IcsFeedSyncTests`: `a later sync sends the validators and changes nothing on 304`, `Last-Modified is sent back as If-Modified-Since` | `cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.calendar.infra.ics.*' jacocoTestReport ktlintCheck` | Pass |
| AC1: Parse (tolerant parser, mapping) | `IcsParserTests` (17), `IcsEventMappingTests` (38), `IcsFeedAdapterTests`: `a body that is not a complete iCalendar file is refused`, `unreadable events are left out...` | same | Pass |
| AC1: Upsert/delete | `IcsFeedSyncTests`: `the first sync creates a read-only calendar...`, `a changed feed updates changed events, deletes removed ones and leaves unchanged ones alone`, `an unreadable event keeps its local copy until the feed can be read again`, `a 200 answer that is not an iCalendar file keeps the events...`, `an event that ends before it starts cannot be stored...` (new) | same | Pass |
| AC1: Calendars read-only | `IcsFeedAdapterTests`: `a feed has one read-only calendar...`, `creating, changing and deleting events is refused without a request`; `IcsFeedSyncTests`: `events of the feed cannot be created or changed through the event API` | same | Pass |
| AC1: SSRF protection (no private/loopback unless allowed in config) | `IcsFeedClientTests`: `hosts resolving to loopback, private, link-local, CGNAT or unspecified addresses are refused`, `IPv6 link-local and IPv4-mapped metadata addresses are refused`, `numeric encodings of the loopback address are refused...`, `a host with a public and a private address is refused`, `a redirect from a public host to a private one is refused before it is requested`, `allowed private hosts skip the check by name...`, `an allowed private host does not allow other private hosts it redirects to`, `allow-private-networks lifts the check for every host`, `URLs with credentials are refused...`; `IcsFeedSyncTests`: `feeds on loopback and private addresses are refused unless their host is allowed`, `a redirect to a private address is refused` | same | Pass |
| AC2: `./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.ics.*'` passes, Postgres via Testcontainers, WireMock feeds | all 123 tests; `IcsFeedSyncTests` extends `PostgresTestBase` (Testcontainers) and serves feeds from WireMock; `IcsFeedClientTests` uses WireMock too | same | Pass: 123 tests, 0 skipped, 0 failures, 0 errors |

## Focused test execution

Command:

`cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.calendar.infra.ics.*' jacocoTestReport ktlintCheck`

Result:

Pass. BUILD SUCCESSFUL. JUnit XML: IcsEventMappingTests 38, IcsFeedAdapterTests 13, IcsFeedClientTests 44,
IcsFeedSyncTests 12, IcsParserTests 17, so 123 tests with 0 skipped, 0 failures and 0 errors. ktlintCheck passed.
`--rerun` forced the test task to execute, so the results and the coverage are fresh.

I ran this command three times:
1. before adding any test: 122 tests passed;
2. once on `IcsFeedSyncTests` alone after adding the test (the first try failed ktlint `chain-method-continuation`
   in my new line; I reformatted it, then it passed);
3. the final run on the final snapshot: 123 tests passed.

## Full test execution

Command:

`sh scripts/check.sh` (the `.taskcheck`: lint + unit tests of server, web and android)

Result:

Pass. Ended with "== all checks passed", exit 0.

## .taskcheck

Result:

Pass. `sh scripts/check.sh` ran on the final snapshot from 2026-10-21T17:40:09+02:00 to 17:49:25+02:00 (about 9 min).
That is within the 15 min `done` timeout but above the 2 min warning. The long runtime is not caused by this task.

## Bugs found

None. No test exposed a production defect.

Observation (not a defect, no fix required): the KDoc of `IcsFeedProperties.timeout` says "Resolving the host name
for the SSRF check is not part of it". However, `IcsFeedClient.fetch` (line 74) starts the deadline before the first
`checkAddresses`, so resolution time does use up the budget. The resolver call itself is just not interrupted. If
resolution outlasts the whole timeout, the fetch fails with "did not answer in time" before any request is sent
(line 155). It is safe either way. Only the comment is slightly inaccurate. I did not write a test, because it would
pin down one reading of an ambiguous comment.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: equal to the whole-file line coverage below, because all five production files were added by
this task (`git diff --stat bdc5b2b0..HEAD` shows only insertions for new files). I did not compute it as a separate
diff intersection.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `cal-ics`; base
  bdc5b2b03a2321e2881d2f61738ad72cbb39fd28 (given by the parent); measured HEAD d04161cff711dcb3654dfd41847c952c6fbaf3d2;
  ownership comes from the parent's commit range bdc5b2b0..HEAD and the task's `touches` folders. All in-range
  production changes are inside `calendar/infra/ics`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD d04161cf plus one uncommitted file,
  `IcsFeedSyncTests.kt` with blob 61eee15c125ee16b685d9d4abdd13bd54db79eee (HEAD blob 1ca0ffff). The production blobs
  match HEAD: IcsEventMapping.kt bd4b89b0, IcsFeedAdapter.kt 9aed6fad, IcsFeedClient.kt 3aa065c7,
  IcsFeedProperties.kt 822d72c0, IcsParser.kt 5d54097. `git status` was the same before and after the run.
- Exact included files; renamed/deleted/excluded files and reasons: included `IcsEventMapping.kt`, `IcsFeedAdapter.kt`,
  `IcsFeedClient.kt`, `IcsFeedProperties.kt`, `IcsParser.kt` (all under
  `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/ics/`). No renames or deletions. Excluded: `tasks/`,
  `reviews/` and test files (not production code).
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin, with
  the plugin's default tool version (`toolVersion` is not overridden in `server/build.gradle.kts`, and the report
  does not state it); command `./gradlew test --rerun --tests 'ch.sonensei.cockpit.calendar.infra.ics.*' jacocoTestReport ktlintCheck`
  in `server/`. Only the ICS tests were selected, so the numbers show what this task's tests cover.
- Run start/completion time and exit status: started after 2026-10-21T17:39:11+02:00, `test.exec` written 17:39:51,
  report written 17:39:53, exit 0 (BUILD SUCCESSFUL).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`
  (sha256 8b755ba291c573d59b68e6339857eb0275ea06861698a0ac00cf286c2a4dbf3f), `server/build/jacoco/test.exec`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. `--rerun` forced the tests to run, the report
  timestamps are from this run, and the blob hashes and `git status` were the same afterwards.
- Final run after latest test additions and production fixes: yes. The run includes the new test, and no production
  code changed during this round.

Values below use covered/total (percentage) from JaCoCo XML `sourcefile` counters. "Statements" means JaCoCo
INSTRUCTION, and "Functions" means METHOD.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/ics/IcsFeedClient.kt` | 1137/1185 (95.9%) | 108/116 (93.1%) | 32/34 (94.1%) | 182/185 (98.4%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/ics/IcsFeedAdapter.kt` | 365/367 (99.5%) | 47/48 (97.9%) | 16/16 (100%) | 41/41 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/ics/IcsFeedProperties.kt` | 135/135 (100%) | 20/20 (100%) | 9/9 (100%) | 17/17 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/ics/IcsParser.kt` | 569/569 (100%) | 89/90 (98.9%) | 18/18 (100%) | 77/77 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/ics/IcsEventMapping.kt` | 1559/1584 (98.4%) | 228/231 (98.7%) | 19/21 (90.5%) | 175/179 (97.8%) |
| **Package `calendar.infra.ics` (summed counters, all 5 files, none unmeasured or excluded)** | 3765/3840 (98.0%) | 492/505 (97.4%) | 94/98 (95.9%) | 492/499 (98.6%) |

There is no coverage threshold (ARCHITECTURE.md: "It is not a gate"). The new test exercises the sync engine's
refusal path and covers no new lines in the package, so the numbers match the run before it was added.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `IcsFeedClient.kt:155` (deadline already passed before a request) | Timeout spans the whole fetch | Not added. Only reachable when DNS resolution outlasts the whole timeout, or through scheduling races. The fetch-wide deadline is already tested in `the timeout covers the whole fetch, so redirects...`. See the KDoc observation above. |
| `IcsFeedClient.kt:129-130, 171-172` (`IllegalArgumentException` from `URI.resolve` / `HttpRequest.Builder`) | Invalid redirect or request becomes a provider error | Defensive. `URI` parsing and `check()` already reject the inputs that can be built. Invalid redirects are tested via `URISyntaxException` in `a redirect without Location, to an invalid URL...`. |
| `IcsFeedClient.kt:184, 186-188` (null cause, `HttpTimeoutException`, non-IO cause) | Transport error mapping | `HttpTimeoutException` races with the `future.get` timeout, which is tested and gives the same message. A null cause or non-IO cause is defensive. |
| `IcsFeedClient.kt:316` (`onNext` after the body was refused) | Size limit | Race-dependent. Refusal is tested in `a body larger than the maximum is refused when it is sent in chunks`. |
| `IcsFeedClient.kt:35, 88, 114, 200, 288-289` | none | Kotlin synthetic code: data-class default constructor, range-check halves, the `host` null/empty sub-branch, companion initializer. |
| `IcsFeedAdapter.kt:114` (`readTree` returning null) | Unreadable cursor or config | Not reachable with Jackson 3 (it returns a non-object node, which is tested). |
| `IcsParser.kt:114` (`line[i] != ':'` after the parameter loop) | none | Unreachable: the loop only stops at `:` or at the end of the line. |
| `IcsEventMapping.kt:46, 48` (default `skipped = {}` lambda) | none | Synthetic `$default`. Every caller passes `skipped`. |
| `IcsEventMapping.kt:181, 192, 227-228` (`else` of `LocalDate`/`Instant`) | none | Unreachable: `Zones.time` returns only `LocalDate` or `Instant`. |
| `IcsEventMapping.kt:208` (negative-day DURATION sign) | DTEND from DURATION | Not added. RFC 5545 VEVENT durations are positive. A negative one makes the event end before it starts, which the sync engine refuses to store. The new sync test covers that refusal through DTEND. |

## Unable to test

- Nothing that the acceptance criteria require. Docker/Testcontainers was available, and the Postgres-backed
  `IcsFeedSyncTests` ran (12 tests, 0 skipped).
- DNS rebinding between the SSRF check and the connection: the task records it as a known limitation in its
  assumptions (the JDK HttpClient cannot be pinned to an address). It cannot be tested without a DNS test server, and
  it needs no follow-up within this task.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. I added one integration test, and all
validation passed: the focused ICS suite (123 tests), ktlintCheck and `.taskcheck`.
