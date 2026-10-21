# Code Review

Date: 2026-10-21

Task: cal-ics - ICS feed adapter

Scope: `git diff b32f928d..6099cd36 -- server` (production code in `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/ics`, tests in `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/ics`). This is a re-review after the fixes. It checks the earlier findings CR-1 to CR-5 and looks for new problems caused by the fixes. Only committed changes are reviewed. Test files that a test-writer is adding in parallel (uncommitted) are not part of it.

Requirements:
- tasks/cal-ics.md: acceptance criteria and the recorded assumptions (Notes). Recorded assumptions are not reported as findings.
- SPEC.md §4.2, §6.1, §6.2
- docs/ARCHITECTURE.md and the AGENTS.md project notes
- The sync engine contract: `calendar/sync/CalendarProviderAdapter.kt` (`RemoteEventChanges.complete`) and `calendar/sync/CalendarSyncService.kt` (`mergeEvents`, `upsertOverride`, `removeMissing`)
- The earlier review, reviews/cal-ics/review.md (revision 1255af33), and the fix report, reviews/cal-ics/fixes.md

Git range:
- Branch: main
- HEAD: 6099cd3658cf92bbf2312bc68d743ab621b9f222
- Base/start: b32f928d4c1b4282c2ae95617e71c11dd0ee5e16
- Commits reviewed: 6 (2f2bdf7a, 56a6d8a1, 62c361ce, 58e15660, 5234efd6, 6099cd36). The commits that change code are 2f2bdf7a, 56a6d8a1, 58e15660 and 5234efd6.

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Status of earlier findings

- **CR-1 (P1, a 2xx body that is not iCalendar deleted the whole feed): resolved.** `IcsParser` now sets `IcsComponent.ended` only when a component is closed by its own `END` (`IcsParser.kt:51-62`). `IcsFeedAdapter.fetchEvents` refuses the download unless there is at least one `VCALENDAR` root and every root is ended (`IcsFeedAdapter.kt:64-67`). The cases I traced:
  - An empty body, an HTML page, or a stray `VEVENT` gives no `VCALENDAR` root, so it is refused.
  - A body cut off anywhere before the final `END:VCALENDAR` is refused. This includes a cut inside the `END:VCALENDAR` line, because a partial name such as `END:VCAL` matches nothing and is ignored.
  - A complete `VCALENDAR` with no events is still a complete listing.

  The regression test is `IcsFeedSyncTests` "a 200 answer that is not an iCalendar file keeps the events and records an error", together with the adapter and parser tests.
- **CR-2 (P2, the body read had no deadline): resolved.** `fetch` sets one deadline for the whole fetch (`IcsFeedClient.kt:74`). Each hop uses `sendAsync` and then `future.get(remaining)`, and on timeout or interrupt it calls `future.cancel(true)` (`IcsFeedClient.kt:149-191`). On JDK 21 this cancel aborts the exchange. Bodies are collected inside the future by `LimitedBody`, so the deadline covers headers, body and redirects. The property KDoc was corrected (`IcsFeedProperties.kt:18-22`). It says plainly that host resolution for the SSRF check is outside the deadline. Tests:
  - a trickled body, given up within 3 s at a 500 ms timeout;
  - a chain of redirects that is too slow as a whole;
  - a late answer;
  - interrupt handling.

  The size limit was checked for:
  - Bodies with a `Content-Length` over the limit are refused at subscribe time.
  - Streamed and chunked bodies are refused once they grow past the limit.
  - Gzip bodies are limited both compressed (by `LimitedBody`) and decompressed (by `read`).
  - Bodies of error and redirect answers are discarded with `BodySubscribers.replacing`. An endless body of that kind is still cut off by the deadline.
- **CR-3 (P2, missing tests): resolved by 58e15660 and 5234efd6.** The following have focused tests now:
  - the size limit: Content-Length, chunked, gzip before and after decompression, and exactly at the limit;
  - gzip and broken gzip;
  - webcal and webcals;
  - the redirect limit and redirect loops;
  - a host with mixed public and private addresses;
  - userinfo URLs, `allow-private-networks` and allowed private hosts;
  - charset handling;
  - the parser and the mapping (TZID fallbacks, DURATION, VALARM, UID generation, etag).
- **CR-4 (P3, overrides of a dropped series caused a permanent error): resolved.** `IcsEventMapping.events` keeps an exception only when the same feed has a mapped, non-cancelled master with an `RRULE` (`IcsEventMapping.kt:63-65`). This covers cancelled series, RDATE-only series, a missing series and an unreadable series. The sync regression test checks that there is no `lastError`.
- **CR-5 (P3, unreadable events were deleted silently): resolved as recorded in the task assumption of 16:49.** If any VEVENT cannot be read, the adapter returns `complete = false` (`IcsFeedAdapter.kt:68-71`), so `CalendarSyncService.mergeEvents` does not call `removeMissing`. Earlier local copies and their links are kept. The skip is not shown in `lastError`, and this is a recorded assumption, so it is not reported here.

The new nominal-days handling of `DURATION` (`plusNominal`, `IcsEventMapping.kt:196-210`) is correct for these values:
- `P1D` and `P1W`;
- negative and signed values;
- mixed values such as `P1DT1H30M`. Days are added as calendar days and the rest as exact time.

A value without a leading day or week part falls back to exact time. `duration()` for VALARM triggers is unchanged.

Other things I checked that are fine:
- `LimitedBody` ignores `onNext` after it has refused the body.
- The first failure (the "larger than" message) is the one reported.
- `CalendarProviderException`s thrown inside the future are passed through unwrapped.
- The interrupt flag is restored after an interrupt.
- No error message or log line contains the feed URL or the body.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | A test comment still quotes the old per-request timeout contract**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/ics/IcsFeedClientTests.kt:296`
    - Category: Quality
    - Problem: The trickle test quotes `IcsFeedProperties.timeout` as "Time for one request, from sending it to the end of the body." The fix changed that KDoc to say the timeout covers the whole fetch, including redirects (`IcsFeedProperties.kt:18-21`). The next test (`:312`) checks the new whole-fetch contract, so the comment above it now contradicts it.
    - Why it matters: Someone reading the test is pointed to a contract that no longer exists.
    - Suggested fix: Update the quote to match the current KDoc, or remove it.

- [ ] **CR-2 | `timeout` and `connect-timeout` are not validated like the other properties**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/ics/IcsFeedProperties.kt:32-36` (used at `IcsFeedClient.kt:64,74,155`)
    - Category: Quality
    - Problem: The `init` block checks `max-size`, `max-redirects` and `poll-interval`, but not the two timeouts.
        - With `cockpit.calendar.ics.timeout` set to zero or a negative value, `send` finds `remaining <= 0` and throws "The feed server did not answer in time" on every fetch, without sending a request. That message is misleading.
        - With `connect-timeout` set to zero or a negative value, `HttpClient.Builder.connectTimeout` throws an `IllegalArgumentException` while the bean is being built, and the message does not name the property.
    - Why it matters: A misconfiguration looks like a failing feed server, or it stops startup with an unclear error. The existing checks show that the project wants these values validated at startup with a clear message.
    - Suggested fix: Add `require(timeout.isPositive)` and `require(connectTimeout.isPositive)` with messages that name the property, and extend the existing properties test.

## Review conclusion

No material issues found. The earlier findings CR-1 to CR-5 are resolved (CR-5 as recorded in the task assumption). The two remaining findings are low-priority cleanups and do not block completing the task.
