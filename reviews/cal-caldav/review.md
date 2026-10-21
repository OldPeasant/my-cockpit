# Code Review

Date: 2026-10-21

Task: cal-caldav - CalDAV adapter

Scope: committed task changes `git diff 1e2a388132d5fa9ac70f8976468b62c351f9eca4..95486262c9cf08048932d7a1df2e2a7ffd926c6b` (commits bf8731c7, 1d2506e5, b99b0209, 1595b669, ccc57dee, 48dbc990, 95486262). Reviewed revision: 95486262c9cf08048932d7a1df2e2a7ffd926c6b. This review replaces my earlier review of the same production code (revision 48428868 before the rebase). Since then, only test code and review files changed.

Requirements:
- tasks/cal-caldav.md acceptance criteria and Notes (assumptions #1-#5)
- SPEC.md §6.2 (sync-collection else ctag/etag, If-Match writes, 412 leads to a refetch and a conflict)
- CalendarProviderAdapter / CalendarProviderRegistry contract (`calendar/sync/`)
- docs/ARCHITECTURE.md (external tests, coverage, conventions), AGENTS.md project notes (lint, fictitious test data, no secrets or event content in logs or messages)
- reviews/cal-caldav/fixes.md, reviews/cal-caldav/tests.md
- Parent instructions: confirm that production code is unchanged, review the new tests, and judge the 303 handling in `CalDavClient.send`

Git range:
- Branch: main
- HEAD: 95486262c9cf08048932d7a1df2e2a7ffd926c6b
- Base/start: 1e2a388132d5fa9ac70f8976468b62c351f9eca4
- Commits reviewed: 7

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Production code unchanged since the previous review

- `git diff --stat ccc57dee..95486262` lists only `reviews/cal-caldav/{review,tests}.md`, `CalDavCalendarAdapterTests.kt`, `CalDavClientTests.kt` and `CalDavEventMappingTests.kt`.
- `git diff 48428868 95486262` for `server/src/main/kotlin/ch/sonensei/cockpit/calendar`, `calendar/sync` tests, `core` tests and `docs/ARCHITECTURE.md` is empty. 48428868 is the revision of my previous review, before the rebase. The only production differences to it are unrelated main commits (`gtd/convert`) that the rebase brought in.
- My earlier findings and verifications still apply to the unchanged code: F1-F4, PRE-1, PRE-2, the origin check on redirects, the XXE-hardened parser, and messages without the password or response body.

## Status of the earlier finding

- **Earlier CR-1 (no Radicale evidence on the final code, P2): resolved.** `server/build/test-results/integrationTest/TEST-...CalDavRadicaleSyncTests.xml` has 10 tests, 0 failures, 0 errors, timestamp 2026-10-21T14:39:37Z. That run came after the fix commit, and the production code it ran is identical to HEAD. tests.md also records `./gradlew check jacocoTestReport` (3408 tests, 0 failures) and `sh scripts/check.sh` (exit 0) on the current production code. `python tasks.py verify cal-caldav` still shows both criteria unticked: the parent has to record this evidence before `done`.

## New tests reviewed (no finding)

The latest unit result files (14:53-14:54Z) show 0 failures: `CalDavCalendarAdapterTests` 44, `CalDavClientTests` 15, `CalDavEventMappingTests` 42, `ICalendarTests` 15.

**CalDavClientTests**
- The origin tests cover default ports, host case, a suffix-host trick, protocol-relative hrefs, `mailto:` and malformed hrefs. They test actual origin rules, not implementation details.
- The off-origin `send` test checks that WireMock received no request at all. This is the meaningful assertion for the password not leaking.
- The redirect-hop arithmetic is right. From `hop0`, six redirects exhaust `MAX_REDIRECTS + 1` iterations and the request fails. From `hop1`, five redirects are followed and the sixth answer is the 200.
- The interrupt test is deterministic and does not depend on timing. The interrupt flag is set before `HttpClient.send`, whose `CompletableFuture.get` throws immediately. The flag is cleared in `finally`. The 5-second WireMock delay only holds a server thread and cannot make other tests fail.
- The XML tests add an internal-entity DOCTYPE case to the adapter's external-entity case. `<html>...</html` is deliberately malformed, so it hits the invalid-XML path. The well-formed non-multistatus case is covered separately.

**CalDavCalendarAdapterTests**
- 403 during sync: the test checks the message and its redaction. It also checks that no listing PROPFIND or REPORT follows. This guards against reporting events as deleted after a refusal.
- 503 on sync-collection: the test checks that no fallback listing happens.
- The 100-round truncation guard asserts exactly 100 REPORTs.
- Off-host and malformed member hrefs are dropped from both the multiget and the cursor.
- Out-of-range and unreadable DURATION values in a server answer lead to `CalendarProviderException`, not a crash.
- A resource that holds only overrides is a conflict, and nothing is PUT.
- The calendar-query fallback for 410, non-iCalendar and other-UID answers asserts the conditional PUT on the server-named resource 3 times.
- In the read-back test for removing an occurrence, the stubbed etag equals the old one. The assertion `verify(2, GET)` is still what distinguishes a real read-back from echoing the If-Match value, so the test is not hollow.
- All passwords, hosts and event data are fictitious.

**CalDavEventMappingTests**
- The DST DURATION test asserts the RFC 5545 semantics in the expected values: nominal days in wall time, then exact hours, and a week counted as 7 nominal days. P1D and PT24H across 29 March 2026 give different ends.
- The two new VTIMEZONE tests use fictitious `ZoneRules`: a 24:00 transition that rolls into November, and a UTC-defined transition that falls on the day before in the previous month. They check the exact RRULEs and also expand them with ical4j for 1971-2100 against `ZoneOffsetTransitionRule.createTransition`. That is an independent oracle, not a re-implementation of the production arithmetic.
- The February "from the end" case confirms the RDATE fallback (no RRULE).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | A 303 redirect repeats the original method, body and conditional headers instead of switching to GET**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/caldav/CalDavClient.kt:107-113` (with `REDIRECTS` at `:155`)
    - Category: Correctness
    - Problem: `send` handles 303 like 307/308: it re-sends `method`, `body` and the caller's headers (`If-Match`, `If-None-Match`, `Depth`) to the Location. RFC 9110 §15.4.4 defines 303 as "the request was handled, the result is at another URI, retrieve it with GET". Suppose a same-origin server answers a PUT or DELETE with 303. The client then repeats the write at a different URI, possibly creating a second resource or deleting another one. No test covers 303: the redirect test lists 301, 302, 307 and 308 only.
    - Why it matters: The impact is small. Redirects stay on the source's origin, and CalDAV servers do not normally answer writes with 303. Still, repeating a write the server has already processed is the wrong default for a client whose writes are conditional and must not be duplicated.
    - Suggested fix: Do one of the following, and add a 303 case to `CalDavClientTests`:
        - follow 303 only as a GET without body or conditional headers;
        - for methods other than GET, return the 303 to the caller, so the adapter reports "failed at the CalDAV server (HTTP 303)".

## Notes (no finding)

- `reviews/cal-caldav/tests.md` names the pre-rebase hashes (base `ec3d6bd9`, HEAD `eab0717f`). The test code it measured is patch-identical to the committed tests: its new test names and counts match HEAD and the latest result files. Under the patch-id freshness rule in `docs/QUALITY.md` this does not need a re-run. The parent must set `reviewed_revision` in `quality.json` to 95486262c9cf08048932d7a1df2e2a7ffd926c6b.

## Review conclusion

No material issues found. Production code is unchanged since my previous review, the earlier evidence finding is resolved, and the new tests are meaningful and deterministic. The only finding, CR-1 (P3), is optional hardening of the 303 handling and does not block completion. The parent still has to record the evidence for both acceptance criteria with `tasks.py verify` before `done`.
