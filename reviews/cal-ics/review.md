# Code Review

Date: 2026-10-21

Task: cal-ics - ICS feed adapter

Scope: `git diff 8a2124d4..1255af33 -- server` (production code in `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/ics`, tests in `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/ics`). Committed changes only. Tests the test-writer is adding in parallel (uncommitted) are out of scope.

Requirements:
- tasks/cal-ics.md acceptance criteria and recorded assumptions (Notes)
- SPEC.md §4.1, §4.2, §6.1, §6.2
- docs/ARCHITECTURE.md (code conventions)
- AGENTS.md project notes
- Sync engine contract: `calendar/sync/CalendarProviderAdapter.kt`, `calendar/sync/CalendarSyncService.kt`, `calendar/sync/RemoteEventMapping.kt`
- Existing SSRF conventions: `mail/api/RemoteImageFetcher.kt` (`PublicAddresses`, `HostResolver`)

Git range:
- Branch: main
- HEAD: 1255af332191d5204e743382a46419a01ad2eeb7
- Base/start: 8a2124d43a4a4f14463307934b4b960a247a1bd9
- Commits reviewed: 2 (b5a5a5bc, 1255af33)

Summary: 0 P0, 1 P1, 2 P2, 2 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | A 2xx response that is not an iCalendar file (or is cut short) deletes every event of the feed and its links**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/ics/IcsFeedAdapter.kt:58-62` (with `IcsFeedClient.kt:77`, `IcsParser.kt:26-58`)
    - Category: Correctness
    - Problem: Any status 200..299 is read as the feed. `IcsParser.parse` never fails: an empty body, an HTML page (for example a login, captive-portal or "feed disabled" page served with 200), or a body without `BEGIN:VCALENDAR` gives no `VCALENDAR` root, so `IcsEventMapping.events` returns an empty list. The adapter still returns `RemoteEventChanges(events = [], complete = true)`. In `CalendarSyncService.mergeEvents`, `complete` triggers `removeMissing`, which calls `deleteForGood` on every synced event of the calendar. That deletion skips the Trash and publishes `ItemDeletedEvent`, so `LinkService.onItemDeleted` also removes the user's links to those events (meeting notes, follow-up tasks). A body that ends early without an I/O error behaves the same way, because the parser closes unclosed components at the end. The events that remain are kept and the rest are deleted. Nothing checks that the feed is a complete calendar (a `VCALENDAR` root that was closed by `END:VCALENDAR`) before it is treated as the full listing.
    - Why it matters: The user loses data (links, plus every event until the next good poll) because of a temporary server-side problem. This is not "the feed no longer has them" as described in the adapter KDoc and SPEC §6.2.
    - Suggested fix: Throw `CalendarProviderException("The feed is not an iCalendar file")` unless the parsed text has a `VCALENDAR` root that was properly ended. The parser could, for example, report whether `END:VCALENDAR` was seen. A feed whose `VCALENDAR` is valid but empty should still be allowed to clear the calendar. Add a WireMock test where a 200 HTML/empty body after a good sync keeps the events and records an error.

## P2 Medium

- [ ] **CR-2 | The request timeout does not limit reading the body, so a slow feed server can block calendar sync indefinitely**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/ics/IcsFeedClient.kt:145,155,184,193-206`; `IcsFeedProperties.kt:136-137`
    - Category: Security
    - Problem: The only deadline is `HttpRequest.Builder.timeout(properties.timeout)`. In JDK HttpClient (toolchain JDK 21, `server/build.gradle.kts:16`) this timeout covers the wait for the response headers. With `BodyHandlers.ofInputStream()`, `send` returns once the headers arrive, and the later `input.read(buffer)` calls in `read()` have no deadline. A server that sends headers and then one byte every few seconds, while staying under `max-size`, keeps the fetch running with no end. The KDoc on `timeout` ("Time for one request, from sending it to the end of the body") and the `HttpTimeoutException` catch in `text()` assume a guarantee the client does not give. Name resolution through `InetAddress.getAllByName` (`IcsFeedClient.kt:49,128`) also has no limit. I could not run an empirical check here because running ad-hoc scripts is denied in this sandbox. The finding relies on the JDK HttpClient timeout semantics. `RemoteImageFetcher` (`mail/api/RemoteImageFetcher.kt:131-141`) was written with a per-read deadline for exactly this reason.
    - Why it matters: `CalendarSyncScheduler.run` is a single `@Scheduled` task, and `syncDue` syncs sources one after another while holding the source lock. Any feed URL that someone gets the user to subscribe to can stall every calendar sync (Google, Microsoft, CalDAV), and with the default single scheduler thread it can also stall other `@Scheduled` jobs. A manual `POST /sources/{id}/sync` waits on the same lock.
    - Suggested fix: Enforce a whole-fetch deadline while reading the body. Options: use `sendAsync(...)` with an `ofByteArray`-style limited subscriber and `orTimeout`; read on the caller thread and close the body stream from a timer when the deadline passes; or reuse the deadline-socket approach of `RemoteImageFetcher`, which would also close the documented DNS-rebinding gap. Correct the property KDoc and add a WireMock test with `withChunkedDribbleDelay` or `withFixedDelay` past the configured timeout.

- [ ] **CR-3 | The size limit, gzip decoding, webcal mapping, redirect limit and parser/mapping edge cases have no tests**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/ics/IcsFeedSyncTests.kt` (whole file, as committed in 1255af33)
    - Category: Tests
    - Problem: The committed tests cover the first sync, 304 with ETag and with Last-Modified, update/delete, read-only, a 500 response, loopback/private literals and a redirect to loopback. Nothing exercises `max-size` (the Content-Length rejection or the streamed limit for chunked or gzip bodies), gzip decompression, `webcal://` to `https://`, more than `max-redirects` hops, a resolver that returns both a public and a private address (`HostResolver` is injectable for this), `allow-private-networks`, URLs with userinfo, charset handling, or the mapping rules in `IcsEventMapping` (TZID fallbacks, DURATION/missing DTEND, cancelled events, VALARM triggers, UID generation, the etag excluding DTSTAMP beyond the one case). These are the security-relevant parts of acceptance criterion 1 and the "tolerant parser" assumption.
    - Why it matters: These are regression-prone and security-relevant paths. A broken size limit or a resolver bypass would go unnoticed.
    - Suggested fix: Add focused unit tests for `IcsParser` and `IcsEventMapping`, `IcsFeedClient` tests with a fake `HostResolver` and WireMock (size, gzip, redirects, mixed addresses, userinfo, webcal), and the regression tests named in CR-1 and CR-2. A test-writer is reportedly adding tests now. Check this against its final output.

## P3 Low

- [ ] **CR-4 | Overrides whose series the adapter drops cause a permanent sync error and a full download on every poll**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/ics/IcsEventMapping.kt:84-85,121-131`
    - Category: Correctness
    - Problem: A cancelled series master is left out (`return null`), but its `RECURRENCE-ID` exceptions are still returned. The same happens for exceptions of a master without an `RRULE` (for example an RDATE-only series, which is unsupported). In `CalendarSyncService.upsertOverride` such an override has no matching series, so each one counts as skipped (the status gets "Team holidays: 1 events could not be stored" or similar in `lastError` on every sync). `orphanedOverride` then deletes the cursor, so the feed's ETag/Last-Modified is never sent again and every poll is a full download.
    - Why it matters: The source shows a permanent error to the user, and conditional fetching (criterion 1) stops working for that feed, even though the feed is valid.
    - Suggested fix: In the adapter, drop overrides whose UID has no non-cancelled recurring master in the same feed.

- [ ] **CR-5 | Unreadable feed events are silently deleted locally and not reported in the sync status**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/ics/IcsFeedAdapter.kt:59-62`
    - Category: Quality
    - Problem: Events that `IcsEventMapping` cannot read (for example a malformed DTSTART) are only logged at WARN. Because the result is `complete = true`, their earlier local copies are then removed as if the feed had deleted them, and `lastError` stays null. The engine reports events it could not store ("N events could not be stored"), but events the adapter could not read are invisible to the user.
    - Why it matters: Events and their links disappear with no sign in the UI, and the result differs from events the engine rejects, which do show an error.
    - Suggested fix: Surface the adapter's skip count, for example by keeping the existing local copy of an unreadable UID if it can be identified, or by passing the count to the engine so it appears in `lastError`. At minimum, record the behavior with `tasks.py assume`.

## Review conclusion

Material issues found; fix before completing the task.

Notes on things that were checked and are fine: messages of `CalendarProviderException` and the log lines contain no feed URL, token or body (IOExceptions report only their simple class name). Userinfo URLs and non-http(s) redirect targets are refused. Every redirect hop is checked again, including IPv6 literals, mapped and transition addresses through `PublicAddresses`, and all resolved addresses. The streamed size limit applies after gzip decompression. Writes are refused both by the read-only calendar (409 from the event API) and by the adapter. The DNS-rebinding gap and allowing any port are recorded task assumptions and are not reported as findings.
