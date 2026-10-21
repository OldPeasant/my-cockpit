# Code Review

Date: 2026-10-21

Task: cal-caldav - CalDAV adapter

Scope: committed task changes `git diff a8fe02662740b1b60542fb4cd96988739c8533ef..4842886832cfa0e816c86bf1dcb3101c573beb66` (commits c0a6861b, 112d04a5, 002726b4, 9716d7ae, 48428868). This is the final code after the fixes in `reviews/cal-caldav/fixes.md`. Reviewed revision: 4842886832cfa0e816c86bf1dcb3101c573beb66. I ignored uncommitted test files from the concurrently running test-writer. This review replaces the earlier one (CR-1..CR-3 on revision 794a2c54).

Requirements:
- tasks/cal-caldav.md acceptance criteria and Notes (assumptions #1-#5 of cal-caldav, `python tasks.py assumptions`)
- SPEC.md §6.2 (sync-collection else ctag/etag, If-Match writes, 412 leads to a refetch and a conflict)
- CalendarProviderAdapter / CalendarProviderRegistry contract (`calendar/sync/`)
- docs/ARCHITECTURE.md (external tests, coverage, conventions), AGENTS.md project notes (lint, no secrets or event content in logs or messages)
- reviews/cal-caldav/fixes.md (F1-F4, PRE-1, PRE-2)

Git range:
- Branch: main
- HEAD: 4842886832cfa0e816c86bf1dcb3101c573beb66
- Base/start: a8fe02662740b1b60542fb4cd96988739c8533ef
- Commits reviewed: 5

Summary: 0 P0, 0 P1, 1 P2, 0 P3

## Status of the earlier findings

- **Earlier CR-1 (test gap, P2): resolved.** These unit suites are now committed: `CalDavCalendarAdapterTests` (WireMock, 32 tests), `CalDavEventMappingTests` (38) and `ICalendarTests` (15). They cover the paths the earlier finding listed as missing: a cross-origin redirect (no password sent), redirect loops and redirects without a target, DOCTYPE/XXE and non-multistatus XML, 401/5xx/network failures, 507 truncation, a server with no sync token and one with a sync token but no sync-collection, a refused sync token, `If-None-Match` 412 recovery, 412 and 404 on update, delete with If-Match and 412, occurrence removal, folding, unfolding and escaping, TZID fallbacks, and VTIMEZONE generation. The VTIMEZONE test checks every JDK zone against ical4j's expansion for 2027-2040. The latest result files (`server/build/test-results/test/`, 14:26Z) show 32/32 for `CalDavCalendarAdapterTests` with 0 failures.
- **Earlier CR-2 (ArithmeticException from out-of-range durations, P3): resolved.** `duration()` uses exact arithmetic and returns null on `ArithmeticException` or `NumberFormatException` (`CalDavEventMapping.kt:325-341`). When an event has no DTEND and its DURATION cannot be read, `read` throws `IllegalArgumentException`, so the VEVENT is skipped (`:185-192`). `readOrNull` and the adapter's `answer()` also catch `ArithmeticException` (`CalDavEventMapping.kt:92`, `CalDavCalendarAdapter.kt:506`). TRIGGER conversion uses `Math.toIntExact`/`negateExact` and returns null on overflow (`:373-378`). The test at `CalDavEventMappingTests.kt:404` covers timed events, all-day events and alarms.
- **Earlier CR-3 (403 documentation and discovery message, P3): resolved.** The `CalDavClient` class doc now matches `send` (`CalDavClient.kt:20-25`). `send` deliberately still returns 403, because the sync-collection REPORT uses 403 for an invalid token (RFC 6578) and the adapter falls back to a full listing on it. Discovery and the depth-0 PROPFIND helper now report "denied access (HTTP 403)" (`CalDavCalendarAdapter.kt:63-73`, `:130`). A bare host whose root answers 403 is still tried at `/.well-known/caldav`. The tests at `CalDavCalendarAdapterTests.kt:150` and `:167` cover this and assert that the message does not contain the password.

## What I checked in the fixes and found correct (no finding)

- **F1 (nominal DURATION days).** `IcsDuration(days, time)`: days are added with `plusDays` in the event's zone, then the exact time is added (`CalDavEventMapping.kt:219-227`). All-day events use `days + time.toDays()`. When DTEND is present, DURATION is ignored, as before.
- **F2 (VTIMEZONE wall-clock days).**
  - The shift between the rule date (`ruleDate`, which mirrors `ZoneOffsetTransitionRule.createTransition`) and the wall-clock `dateTimeBefore` is the same every year, because it depends only on fixed offsets and the 24:00 flag.
  - `window`/`monthDay` map the shifted days into the correct neighbouring month: negative BYMONTHDAY in the previous month, positive in the next. They fall back to RDATE only when the source month has a variable length (February).
  - Each observance's DTSTART is an actual transition in its month. Over 1970-2100 every day of a 7-day weekday window occurs, so the lookup `onsets.first { it.month == month }` finds a match.
- **F4 control flow.** The captured `forbidden` flag reports 403 only when neither probe returns a multistatus. Other statuses still give "No CalDAV service found".
- **PRE-1 fix (`calendar/sync/FakeCalendarAdapter.kt`).**
  - A static `BeanDefinitionRegistryPostProcessor` removes the `calendarProviderRegistry` definition. That is the default bean name of the `@Component CalendarProviderRegistry`.
  - Without it, the application registry would receive both the real `CalDavCalendarAdapter` and the CALDAV fake and refuse the duplicate. The `@Primary` fake-only registry then is the only registry.
  - The change applies only to the three sync test classes that import `FakeCalendarAdapterConfiguration`. If the bean name changes, the tests fail loudly with the duplicate error rather than passing silently.
  - This is outside the task's `touches`, but it is required by the new `@Component`. It is recorded as assumption #5.
- **PRE-2 fix (`ClockConfigurationTests`).** `Clock.tick(systemUTC, 1µs)` rounds down to the microsecond. So `Instant.now().truncatedTo(MICROS) <= clock.instant() <= Instant.now()` always holds, and the assertion is not weakened.
- **Security in the changed code.** The new 403 messages contain only the operation and the status: no password, no response body. The origin check for redirects, the Basic auth scope and the XXE-hardened parser are unchanged.
- **Docs.** The `docs/ARCHITECTURE.md` note on the Radicale image matches `CalDavRadicaleSyncTests`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | The Radicale integration suite was last run before the fix commit, so there is no evidence for criterion #2 on the final code yet**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/caldav/CalDavRadicaleSyncTests.kt:55`
    - Category: Tests
    - Problem: The only integration result is `server/build/test-results/integrationTest/TEST-...CalDavRadicaleSyncTests.xml`, timestamp 2026-10-21T13:23:25Z (9 tests, 0 failures). Commit 9716d7ae came later, at 16:04+02:00 (14:04Z). It changed code that this suite runs: discovery (`listCalendars` probe/403 handling), DURATION parsing, and VTIMEZONE generation, which is written on every cockpit-created timed event. `fixes.md` states that the integration tests were not re-run. `tasks.py verify cal-caldav` shows both criteria still unticked. I also found no result files for the full server `test` run at HEAD: `test-results/test` holds only the caldav unit suites. So the PRE-1/PRE-2 fixes have not been confirmed by a recorded run either.
    - Why it matters: Criterion #2 names `./gradlew integrationTest --tests '*CalDav*'`. Evidence from an earlier revision does not show that the final code still creates, syncs and detects conflicts against a real Radicale server. In particular, the changed VTIMEZONE output is now parsed by the server.
    - Suggested fix: Before `verify cal-caldav 2`, re-run `cd server && ./gradlew integrationTest --tests '*CalDav*'` on 48428868 (or later) and record the command and the result. Run `.taskcheck` (`sh scripts/check.sh`) to confirm that `CalendarSyncTests`, `CalendarSyncOverrideTests`, `CalendarSyncEdgeCaseTests` and `ClockConfigurationTests` pass with the PRE-1/PRE-2 fixes.

## P3 Low

None.

## Review conclusion

No material issues found in the code. All three earlier findings are resolved, and the fixes (F1-F4, PRE-1, PRE-2) are correct as far as I can verify by reading the code and the committed tests. The only open item, CR-1 (P2), is about evidence, not code: before criterion #2 is ticked, the Radicale suite and `.taskcheck` must be re-run on the final revision.
