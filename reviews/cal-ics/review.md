# Code Review

Date: 2026-10-21

Task: cal-ics - ICS feed adapter

Scope: `git diff ebe36ae2..1857e058 -- server` (production code in `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/ics`, tests in `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/ics`). This is a re-review after the round-2 fixes. It checks the earlier P3 findings CR-1 and CR-2 (review of 290bd400) and looks again at the whole task for new problems. Only committed changes are reviewed. Uncommitted test files from a test-writer running in parallel are not part of it.

Requirements:
- tasks/cal-ics.md: acceptance criteria and the recorded assumptions (Notes). Recorded assumptions are not reported as findings.
- SPEC.md §4.2, §6.1, §6.2
- docs/ARCHITECTURE.md and the AGENTS.md project notes
- The sync engine contract: `calendar/sync/CalendarProviderAdapter.kt` (`RemoteEventChanges.complete`) and `calendar/sync/CalendarSyncService.kt`
- The earlier review (reviews/cal-ics/review.md at 290bd400) and the fix report reviews/cal-ics/fixes.md (1857e058)

Git range:
- Branch: main
- HEAD: 1857e0582485d510e9b3f537b177514021e0f946
- Base/start: ebe36ae2d85364068ad6e8a3a844c91396ee30e1
- Commits reviewed: 10 (f18a1ab2, c654fd2f, 6c713d95, bf479aae, 5dbc71b2, 22bc4d64, 290bd400, 176a8e3f, 32e389b7, 1857e058). Since the last review, the commits that change code are 176a8e3f (tests only) and 32e389b7 (one production file and one test file).

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Status of earlier findings

- **CR-1 (P3, a test comment quoted the old per-request timeout contract): resolved.** The trickle test comment (`IcsFeedClientTests.kt:296-297`) now quotes the current KDoc of `IcsFeedProperties.timeout` word for word (`IcsFeedProperties.kt:19-20`): "Time for a whole fetch: from sending the first request, through any redirects, to the end of the last body."
- **CR-2 (P3, `timeout` and `connect-timeout` were not validated): resolved.** `IcsFeedProperties.init` now has `require(timeout.isPositive)` and `require(connectTimeout.isPositive)`, and each message names its property (`IcsFeedProperties.kt:36-37`). `Duration.isPositive` exists on the JDK 21 toolchain (`server/build.gradle.kts:16`). Zero and negative values are now refused when the bean is bound, before `HttpClient.Builder.connectTimeout` (`IcsFeedClient.kt:64`) or the deadline in `fetch` (`IcsFeedClient.kt:74`) can see them. The properties test (`IcsFeedClientTests.kt:609-631`) checks zero and negative values of both, with their exact messages, and checks that a 1 ms timeout is accepted.

## New code since the last review

- The test-writer commit 176a8e3f adds these tests. I checked each against the production code:
  - The summer-time `P1D` case: 2027-03-28 is the Europe/Zurich spring change, and `plusNominal` gives 09:00 local, 23 h later.
  - An exception of an unreadable series. `DTSTART:soon` raises a `DateTimeParseException` in `Zones.time`, which the mapping reports as skipped. Its exception is then dropped by the series filter (`IcsEventMapping.kt:64-65`), so only one skip is counted.
  - The whole-fetch deadline across three 500 ms hops.
  - Discarded bodies of error and redirect answers (`BodySubscribers.replacing`, `IcsFeedClient.kt:200`).
  - A gzip body exactly at the size limit after decompression, and one byte over. `read` refuses only when `size > max`, and the `Content-Encoding` check ignores case.

  The assertions check behaviour, not implementation details.
- 32e389b7 changes only what CR-1 and CR-2 asked for. It does not change behaviour for valid configurations.

## Whole-task check

I re-read all five production files in full: `IcsFeedClient.kt`, `IcsFeedAdapter.kt`, `IcsParser.kt`, `IcsEventMapping.kt` and `IcsFeedProperties.kt`. I found no new problems.

- **Acceptance criterion 1 is implemented:**
  - Conditional fetch with `If-None-Match` / `If-Modified-Since`, with the validators kept as the JSON cursor.
  - 304 is treated as no change.
  - Parse and upsert. Only a complete `VCALENDAR` listing is `complete = true`, so only then does the engine delete events.
  - The calendar is read-only, and the write methods refuse.
  - SSRF check of the URL host and of every redirect. All resolved addresses must be public, unless `allow-private-networks` or `allowed-private-hosts` allows them.
- **Acceptance criterion 2:** `IcsFeedSyncTests` uses `PostgresTestBase` (Testcontainers) with a WireMock feed. The SSRF protection stays on in that test, and only `localhost` is allowed. The client, adapter, parser and mapping tests also use WireMock or in-memory feeds.
- **No sensitive data in errors or logs:** no exception message or log line contains the feed URL, its token or the body. The warning about skipped events logs only the count and the source id.

I did not re-run the test suite for this review. A Gradle run would also compile the test-writer's uncommitted files, which are not in scope. The fix report gives `./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.ics.*' ktlintCheck` as 120 tests with 0 failures. The parent worker still has to record the criterion evidence with `tasks.py verify`; both criteria are unrecorded so far.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. The earlier findings CR-1 and CR-2 are resolved, and the whole-task re-read found no new problems.
