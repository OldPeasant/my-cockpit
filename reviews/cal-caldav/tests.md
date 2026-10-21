# Test Report

Date: 2026-10-21

Task: cal-caldav - CalDAV adapter

Scope: task commits `ec3d6bd9164615234d543bfbc345bde1bdb60c68..eab0717f1e16ffc8e887ac9ebc43004bdda79f88`
(6a94547b, 49802b9f, c1d27fc5, c625a5a7, f333e5cf, eab0717f), base and commit list supplied by the parent worker
(`git merge-base HEAD origin/main`), plus this run's uncommitted test changes (listed below). Branch `main`, measured
HEAD `eab0717f1e16ffc8e887ac9ebc43004bdda79f88`.

Requirements:
- `tasks/cal-caldav.md` acceptance criteria and the assumptions recorded there (Basic auth sent only to the source's origin, redirects to other hosts refused, own iCalendar model, generated VTIMEZONEs)
- `SPEC.md` §6.2 (CalDAV sources)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md` (test data fictitious, no secrets in messages/logs)
- Parent instructions: continue the interrupted earlier test-writer run (its tests are committed in eab0717f), fill the remaining meaningful gaps without duplicating existing coverage, and don't run integration tests

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/caldav/CalDavClientTests.kt` (2 existing tests kept unchanged, 13 added; own WireMock server)
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/caldav/CalDavEventMappingTests.kt` (1 test added)

The earlier interrupted run's additions (committed in eab0717f, by the earlier test-writer run) are part of the
measured state: CalDavCalendarAdapterTests 44, CalDavEventMappingTests 41 (42 now), ICalendarTests 15, CalDavClientTests 2.

## Tests added or updated

Count: 14 added in this run (0 existing tests modified or removed)

CalDavClientTests (direct tests of `CalDavClient.send` / `propfind` / `report` and `MultiStatus.parse`, which the
adapter tests only reached indirectly):
- `a request to another host is refused before anything is sent`
- `answers other than 401 and 5xx go to the caller with status, body and headers` (403, 404, 409, 410, 412; 201 ok)
- `a body is sent with its content type and the caller's headers, a request without body has no content type`
- `PROPFIND and REPORT send their Depth and an XML body in the WebDAV namespaces`
- `a user name without a password sends an empty password`
- `a redirect within the server keeps the method, body and password, for permanent and temporary redirects` (301, 302, 307, 308)
- `a redirect whose Location is no URI fails as a redirect without a target`
- `a redirect chain of five hops is followed, the sixth fails`
- `401 and 5xx messages hold neither the password nor the answer`
- `an interrupted request is a provider failure and keeps the thread's interrupt flag`
- `a multistatus keeps only properties of 2xx propstats or of propstats without a status code`
- `a multistatus reads response statuses and the sync token, and skips responses without href`
- `empty answers, HTML, entity declarations and other XML are no multistatus` (includes an internal-entity "billion laughs" DOCTYPE, in addition to the adapter's external-entity XXE test)

CalDavEventMappingTests:
- `a wall-clock transition on the day before reaches back into the previous month, counted from the start or the end`: fictitious zone rules ("Sunday on or after 1 April", "Sunday on or before 6 April", "Sunday on or before the 28th day from the end of February", all at 01:00 UTC at UTC-3). The test checks the generated RRULEs, and checks every year 1971..2100 with ical4j's recurrence expansion against `ZoneOffsetTransitionRule.createTransition`. It covers the previously unexecuted `monthDay` branches (previous month counted from the start and from the end, and the leap-year RDATE fallback for the end-of-February case).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: Discovery (principal, calendar-home-set) | CalDavCalendarAdapterTests discovery tests (`discovery goes through the principal...`, `a bare host is looked up at the well-known URL...`, `a URL that names a calendar...`, `discovery fails clearly...`, 403 tests) | `cd server && ./gradlew check jacocoTestReport` | Pass |
| AC1: sync-collection REPORT with fallback to ctag/etag | CalDavCalendarAdapterTests (`a server without sync tokens is listed by etags...`, `a first sync follows truncated (507)...`, `an incremental sync...`, `a sync token the server refuses...`, `...refuses sync-collection is listed by etags`, `...after 100 rounds...`); CalDavClientTests multistatus tests (sync token, response status, propstat filtering) | `cd server && ./gradlew check jacocoTestReport` | Pass |
| AC1: PUT/DELETE with If-Match | CalDavCalendarAdapterTests (`createEvent PUTs ... If-None-Match`, `412 on an update is RemoteEventChangedException...`, `deleteEvent DELETEs the resource with If-Match...`, `removing an occurrence PUTs the series...`); CalDavClientTests `a body is sent with its content type and the caller's headers...`, `answers other than 401 and 5xx go to the caller...` | `cd server && ./gradlew check jacocoTestReport` | Pass |
| Assumption: Basic auth only to the source's origin, redirects to other hosts refused | CalDavClientTests resolve tests, `a request to another host is refused before anything is sent`, redirect tests; CalDavCalendarAdapterTests `a redirect to another host is refused, the password is not sent there`, `a redirect to another host while reading an event is refused` | `cd server && ./gradlew check jacocoTestReport` | Pass |
| No password / response body in error messages (AGENTS.md) | CalDavClientTests `401 and 5xx messages hold neither the password nor the answer`; CalDavCalendarAdapterTests 401/403/503 tests | `cd server && ./gradlew check jacocoTestReport` | Pass |
| XML parsing refuses DTDs (XXE, entity expansion) | CalDavClientTests `empty answers, HTML, entity declarations and other XML are no multistatus`; CalDavCalendarAdapterTests `XML with a DOCTYPE (XXE)...` | `cd server && ./gradlew check jacocoTestReport` | Pass |
| Generated VTIMEZONEs match the Java zone rules (assumption) | CalDavEventMappingTests VTIMEZONE tests incl. the new previous-month test and `the RRULEs of every generated VTIMEZONE give the transitions of the Java zone rules` | `cd server && ./gradlew check jacocoTestReport` | Pass |
| AC2: Radicale Testcontainer, create in cockpit -> on server, change on server -> in cockpit, conflict | CalDavRadicaleSyncTests (10 tests, unchanged by this run) | `cd server && ./gradlew integrationTest --tests '*CalDav*' --rerun` | Not run by this test-writer: the parent asked me not to run integration tests alongside other Gradle runs. The parent ran it at 2026-10-21T14:39Z and got 10/10 passed. This run changed no production code and did not touch that test file. |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.caldav.*'`

Result:

Pass: 116 tests, 0 failures, 0 skipped (CalDavCalendarAdapterTests 44, CalDavClientTests 15, CalDavEventMappingTests 42, ICalendarTests 15).
The first run of the new tests had 3 failures. All 3 were mistakes in my tests, not production defects: 507 is a 5xx
and is meant to throw; a status line with no code is treated as no status; the Saturday window was 1..6, not 1..5. I
fixed the tests before the final run.

## Full test execution

Command:

`cd server && ./gradlew check jacocoTestReport` (ktlint main/test/scripts + the full unit test suite + the JaCoCo report)

Result:

Pass: BUILD SUCCESSFUL in 5m 31s, 3408 tests, 0 failures, 0 errors, 0 skipped (2026-10-21T14:52:14Z to 14:57:45Z).

## .taskcheck

Result:

Pass: `sh scripts/check.sh` ran from 2026-10-21T14:58:10Z to 15:03:08Z with exit 0. That covers the tfp gate tests, server check, web (114 files, 2275 tests passed) and Android ktlint, unit tests and lint, ending with "all checks passed". It took about 5 minutes, above the 2-minute warning in AGENTS.md, but well within the 15-minute timeout.

## Bugs found

None. No test exposed production behavior that contradicts the acceptance criteria or the recorded assumptions.

Observation, not a defect: `CalDavClient.send` re-sends the original method and body on a 303 redirect (HTTP says a
303 should become a GET). This is harmless for CalDAV servers in practice, and no requirement names it, so I did not
encode it in a test either way.

## Coverage

Status: verified (fresh full-suite run, exit 0, inputs unchanged during the run)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: all four production files were added by this task (`git diff --name-status` shows `A`), so the
changed lines are the whole files, and the whole-file numbers below are also the changed-line coverage. I did not
measure changed branches separately from the per-file branch counters.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: cal-caldav; base `ec3d6bd9164615234d543bfbc345bde1bdb60c68`; HEAD `eab0717f1e16ffc8e887ac9ebc43004bdda79f88`; the commits `git log ec3d6bd9..HEAD` (6a94547b, 49802b9f, c1d27fc5, c625a5a7, f333e5cf, eab0717f), supplied by the parent. They match the task's `touches` and `git diff --name-only ec3d6bd9..HEAD -- server/src/main web/src android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD eab0717f plus uncommitted test changes. `git diff HEAD | sha256sum` = `cf6a2ee5c7499f9f31f98cc3674cfa669449ea7d7cf4efd69ef0c188e3a48a27`; CalDavClientTests.kt sha256 `ae59da46f67cf13bea8c29d89bcaa11e4f4f54d01410afdb1b311900c37ae432`; CalDavEventMappingTests.kt sha256 `4eaa0f15e7785ca43a414ff9a7b27804ddbb6b22e2555a1fafd12dfb98e07733`. There are no uncommitted production changes and no untracked files.
- Exact included files; renamed/deleted/excluded files and reasons: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/caldav/{CalDavCalendarAdapter,CalDavClient,CalDavEventMapping,ICalendar}.kt`, all measured. Excluded: the test-support changes `calendar/sync/FakeCalendarAdapter.kt` and `core/persistence/ClockConfigurationTests.kt`, because they are test sources, not production code; also `docs/ARCHITECTURE.md` and `reviews/` (documentation). There are no renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo 0.8.15 (Gradle `jacoco` plugin, `org.jacoco.agent:0.8.15`), JDK 21.0.12.1; `./gradlew check jacocoTestReport` in `server/`; full unit test suite (`test` task, which excludes the `external`/integration tests).
- Run start/completion time and exit status: 2026-10-21T14:52:14Z to 14:57:45Z, exit 0; the `test` task actually executed (not up-to-date).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` (written 2026-10-21 16:57:45 +0200; sha256 `07fccba168386c90cb18e1379fcfba176b3116dd8b55e5d18aee23508074d949`); HTML in `server/build/reports/jacoco/test/html/`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report was rewritten by this run (its hash differs from the pre-addition baseline run's `844423bd...`), and the snapshot hashes above were identical before and after the run.
- Final run after latest test additions and production fixes: yes. This run made no production changes, and the measurement includes all 14 new tests.

Baseline before this run's additions (same command family, `./gradlew test jacocoTestReport`, 14:40-14:48Z, report
sha256 `844423bd...`): CalDavClient 912/971 instructions, 90/108 branches, 133/142 lines; CalDavEventMapping 316/379
branches, 391/393 lines; aggregate branches 822/955 (86.1%).

Values below use covered/total (percentage) from the JaCoCo XML per-sourcefile counters. "Statements" are JaCoCo
INSTRUCTION counters (JaCoCo has no statement counter).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/caldav/CalDavCalendarAdapter.kt` | 2791/2892 (96.5%) | 318/364 (87.4%) | 57/57 (100.0%) | 351/355 (98.9%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/caldav/CalDavClient.kt` | 944/971 (97.2%) | 102/108 (94.4%) | 30/30 (100.0%) | 136/142 (95.8%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/caldav/CalDavEventMapping.kt` | 3326/3488 (95.4%) | 322/379 (85.0%) | 59/59 (100.0%) | 393/393 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/caldav/ICalendar.kt` | 970/973 (99.7%) | 98/104 (94.2%) | 32/32 (100.0%) | 115/115 (100.0%) |
| **Aggregate (summed counters, all 4 scoped files measured, none missing)** | 8031/8324 (96.5%) | 840/955 (88.0%) | 178/178 (100.0%) | 995/1005 (99.0%) |

No project coverage threshold exists (`docs/ARCHITECTURE.md` "Coverage": not a gate).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CalDavClient.kt`: 102-104 (InterruptedException), 110 (unparsable Location), 33 (null password), 86 (initial off-origin target), 228-239 (response without href, propstat status filtering), 260 (unreadable status line) | Origin and redirect safety, failure mapping, multistatus reading | Covered by the new CalDavClientTests; they are no longer uncovered. |
| `CalDavClient.kt:52-53` | `IllegalArgumentException` from `URI.resolve` after a successful parse | Defensive: there is no realistic href that parses but fails to resolve against an http(s) base. Left uncovered. |
| `CalDavClient.kt:217-220` | IOException / ParserConfigurationException while parsing from a `StringReader` | Not reachable with in-memory input and a fixed parser configuration. Left uncovered. |
| `CalDavClient.kt:40, 118, 250` (partial) | Null-safety partials: `Response.ok` below 200, a null HTTP body, a blank root sync-token | Not material; the meaningful sides are covered. |
| `CalDavEventMapping.kt:699-709` (`monthDay`) | VTIMEZONE observances when a rule's wall-clock day falls in the previous month, or depends on leap years when counted from the end of February | Covered by the new test `a wall-clock transition on the day before reaches back into the previous month...` (checked with ical4j for 1971..2100). |
| `CalDavEventMapping.kt:66-67, 107, 116, 135, 176, 291-300, 463-490, 527-537` (partial branches) | Kotlin `?.` / `?:` null-safety partials when reading UID, TZID, DESCRIPTION, RECURRENCE-ID or VTIMEZONE fields | Not material: each line is executed, and the missed sides are null-safety combinations of fields that the existing tests already cover as missing or empty in other places. |
| `CalDavEventMapping.kt:641, 644, 663, 679` (partial) | Date-only (no weekday) transition rules in VTIMEZONE generation | The tzdb has no such rule, and the all-zones ical4j check passes. A synthetic test would only repeat the rule arithmetic. Left uncovered. |
| `CalDavEventMapping.kt:734` (partial) | Offsets with seconds in TZOFFSETFROM/TO | Only historic LMT offsets have seconds, and generated VTIMEZONEs use current transition rules. Not material. |
| `CalDavCalendarAdapter.kt:451-452` | `write` turns `IllegalArgumentException` into `RemoteEventChangedException` | Defensive: `createEvent` and `updateEvent` already refuse a missing master (lines 378-382, 408-412), as `writing to a series whose resource holds only overrides is a conflict` shows. Not reachable through the public API. |
| `CalDavCalendarAdapter.kt` other partials (62, 74, 78, 107, 131, 195-217, 267-286, 306, 321, 360, 429-438, 465, 500-506, 525-551, 584-593, 625) | Null-safety partials in discovery, sync and find, and the `log.warn` on skipped VEVENTs (321) | Not material: each line runs in the WireMock and Radicale tests. The remaining sides are null-safety combinations (for example, a member without calendar-data) or log-only statements. The main paths (4xx versus 5xx on sync-collection, removal of 404 members, truncation) are covered. |
| `ICalendar.kt:77, 87, 181, 192` (partial) | Properties before any BEGIN, a continuation line as the first line, folding partials | Not material: these are malformed-input edge cases that are dropped without effect, and parsing of broken lines is covered by `parse reads nested components ... skips blank and broken lines`. |

## Unable to test

- AC2 (Radicale Testcontainer): not run by this test-writer, as the parent instructed, to avoid concurrent Gradle runs. The parent's run at 2026-10-21T14:39Z passed 10/10, and this run did not change production code. No follow-up is needed unless the code changes again.
- Coverage of `CalDavRadicaleSyncTests` is not included in the JaCoCo numbers, because the coverage command in ARCHITECTURE.md measures only the `test` task, not `integrationTest`. The unit-test numbers above are therefore a lower bound for the package.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. I added 14 tests (direct CalDavClient and
MultiStatus tests, plus one VTIMEZONE previous-month test). The focused caldav tests (116), `./gradlew check` (3408
tests) and `.taskcheck` all pass. Caldav package coverage is 96.5% instructions, 88.0% branches and 99.0% lines, and
the remaining gaps are justified above.
