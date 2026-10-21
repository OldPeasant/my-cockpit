# Code Review

Date: 2026-10-21

Task: cal-ics - ICS feed adapter

Scope: `git diff 60a868d9..98542708 -- server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/ics server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/ics` (round 4). This re-review covers the final code. Since the round-3 review (d2d1f23e), only test code changed: d04161cf (SSRF and parser edge-case tests) and 98542708 (a sync store-failure test, plus the test report). Production code is the same as in round 3, and I checked the whole task against the acceptance criteria again. Committed changes only; the working tree was clean.

Requirements:
- tasks/cal-ics.md: the acceptance criteria and the recorded assumptions (Notes). Recorded assumptions are not reported as findings.
- SPEC.md §4.2, §6.1, §6.2
- docs/ARCHITECTURE.md and the AGENTS.md project notes
- The sync engine contract: `calendar/sync/CalendarProviderAdapter.kt` and `calendar/sync/CalendarSyncService.kt`
- reviews/cal-ics/tests.md (the test-writer's report, including its note about the timeout KDoc)

Git range:
- Branch: main
- HEAD: 98542708ca522bb186c32bb079cb05987ee0bf0d (the reviewed revision)
- Base/start: 60a868d9055d668f2fa06a752ff6bd7913a7981b (`git merge-base HEAD origin/main`)
- Commits reviewed: 13 (d49e635f through 98542708). New since round 3: d04161cf and 98542708, which change only tests.

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Test-only changes since round 3

- **`IPv6 link-local and IPv4-mapped metadata addresses are refused`** (`IcsFeedClientTests.kt:518-525`): the test refuses `fe80::1` and `::ffff:169.254.169.254` through the injected resolver and checks that no request was sent. Correct.
- **`numeric encodings of the loopback address are refused by the default resolver`** (`IcsFeedClientTests.kt:527-537`): `127.1` and `2130706433` are refused, either because `java.net.URI` gives them no host or because the address check refuses them. `0x7f000001` gives the URI no host. The test asserts only that each one is refused and that WireMock got no request. It does not say which check refused it, but that is enough for the SSRF property.
- **Parser test for a parameter ending at the colon, and for a line that ends inside its parameters** (`IcsParserTests.kt:235-243`): it checks behaviour (the parameter map, the value, which properties survive). It passes.
- **Store failure in the sync** (`IcsFeedSyncTests.kt:261-277`): a DTEND before its DTSTART can be parsed, but the store refuses it. The test checks three things: `lastError` contains the engine's message "could not be stored" (`CalendarSyncService.kt:180`), the earlier local copy is unchanged, and the other event is updated. This runs against Postgres via Testcontainers (`PostgresTestBase`) with a WireMock feed. A real regression test.

## Whole-task check

- **AC1:** the round-3 review read all five production files in full, and they have not changed since. Conditional fetch sends `If-None-Match`/`If-Modified-Since`, and the validators are kept as the JSON cursor (`IcsFeedAdapter.kt:61-71, 102-110`). A 304 means no change. The adapter parses the feed and returns `complete = true` only for a whole `VCALENDAR` with no unreadable events, so only then does the engine delete events. The calendar is read-only and the write methods refuse (`IcsFeedAdapter.kt:51, 76-96`). The SSRF check covers the URL host and every redirect: every resolved address must be public unless config allows it (`IcsFeedClient.kt:76-78, 133-146`). URLs with credentials are refused.
- **AC2:** I ran `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.ics.*' ktlintCheck` at 98542708: BUILD SUCCESSFUL, 124 tests, 0 failures, 0 errors, 0 skipped (IcsFeedAdapterTests 13, IcsFeedClientTests 44, IcsEventMappingTests 38, IcsFeedSyncTests 12, IcsParserTests 17), and ktlintCheck passed. Persistence is tested on Postgres via Testcontainers (`IcsFeedSyncTests`), with the feeds served by WireMock.
- **Security and data exposure:** no exception message or log line contains the feed URL or the body.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | The KDoc of `timeout` says host-name resolution is not counted, but it is**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/ics/IcsFeedProperties.kt:19-20`
    - Category: Quality
    - Problem: The KDoc says "(Resolving the host name for the SSRF check is not part of it.)". But `IcsFeedClient.fetch` sets the deadline at `IcsFeedClient.kt:74`, before the first `checkAddresses(target)` call at line 77. That call resolves the host, and it runs again for every redirect hop. `send` (lines 154-155) then uses only the time left, and fails at once with "did not answer in time" when nothing is left. So resolution time does count against the timeout; the timeout just cannot interrupt a resolution that is still running. I confirmed the test-writer's note: it is correct. The sentence came in with commit 28b598aa (round-2 fix for the earlier CR-1), and I missed it in round 3.
    - Why it matters: An operator who tunes `cockpit.calendar.ics.timeout` from this KDoc expects resolution to come on top of the timeout. With a slow resolver, a fetch can fail with a timeout earlier than the KDoc suggests. The behaviour itself is reasonable; only the KDoc is wrong. This is not a defect against the acceptance criteria.
    - Suggested fix: Change the KDoc, not the behaviour, e.g. "Resolving host names for the SSRF check counts against it, but a resolution that is still running is not interrupted." No test change is needed. The trickle test comment in `IcsFeedClientTests.kt` quotes only the first sentence, so it stays accurate.

## Review conclusion

No material issues found. The one P3 finding (CR-1) is a misleading KDoc sentence and does not block the task. Fixing it is optional, but cheap. If the fix changes only the KDoc comment, the review evidence still has to be refreshed under the docs/QUALITY.md freshness rules, because the comment line is a changed code line.
