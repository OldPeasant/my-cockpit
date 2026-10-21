# Code Review

Date: 2026-10-21

Task: cal-google - Google Calendar adapter (SPEC.md §6.2), second review round (final revision)

Scope: `git diff 1d2f349e..f798eb17 -- server`, committed changes only. That covers the production code in `calendar/infra/google`, its tests and WireMock recordings, and `calendar/sync/FakeCalendarAdapter.kt`. Uncommitted test changes in the working tree come from the test-writer running in parallel and were not reviewed.

Requirements:
- tasks/cal-google.md acceptance criteria 1-3, and the recorded assumption about the reminder mapping (CR-2 of round 1)
- SPEC.md §6.2 and §4.2
- AGENTS.md project notes (module boundaries, no secrets or event content in logs or messages)
- The sync engine contract: `calendar/sync/CalendarProviderAdapter.kt`, `CalendarWriteThrough.kt`, `RemoteEventMapping.kt`
- The round-1 review and `reviews/cal-google/fixes.md`
- The parent agent's instructions

Git range:
- Branch: main
- HEAD: f798eb17ff25ce8ec407f654172b76ebf403c3a2
- Base/start: 1d2f349eede723cd1f2f25dda0ade84523830235
- Commits reviewed: 7 (ce10f500, 9db2706b, 996af00b, 3dcc3c77, 9314f3ce, d2324910, f798eb17)

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Verification of round-1 findings

- **CR-1 (cancelled answer read back as CONFIRMED): fixed.** `GoogleEventMapping.toRemote` now maps `"cancelled"` to `EventStatus.CANCELLED` (`GoogleEventMapping.kt:76-81`). The listing path is unaffected:
    - `fetchEvents` checks `isCancelled` before it calls `toRemote` (`GoogleCalendarAdapter.kt:113-114`);
    - `withCancelledInstances` reads only keys, never calls `toRemote`.

  So the change only affects write answers (`answer()`). The write-through then stores the override as CANCELLED (`copyFrom` copies the status, `RemoteEventMapping.kt:84`). The new adapter test `cancelling an edited occurrence keeps it cancelled in Google's answer` covers this.
- **CR-2 (reminders written as popup overrides): accepted.** It is recorded as an assumption in tasks/cal-google.md, and the code is unchanged. This is not reopened.
- **CR-3 (token sent to non-Google connections): fixed.** `connectionId()` looks up `tokens.connection(id)` and refuses any provider other than GOOGLE before a token is requested (`GoogleCalendarAdapter.kt:291-304`). A missing connection still fails through the token path with "was removed". All five adapter operations go through `connectionId`. The test `a source linked to a connection of another provider fails without sending its token to Google` asserts that WireMock receives no requests.
- **TW-2 (sync tests assumed GOOGLE has no adapter): fixed in test code.** `FakeCalendarAdapterConfiguration` adds a `@Primary` registry that contains only the fake adapter. It is imported only by the `calendar/sync` tests. `GoogleCalendarSyncTests` does not import it and uses the real registry. The real registry is still created, so its duplicate check still runs.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Every write replaces Google's attendee list, which drops the attendee flags the cockpit does not model**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/google/GoogleEventMapping.kt:131-138`
    - Category: Correctness
    - Problem: `toBody` always sends `attendees`, built only from email, displayName and responseStatus. The `Attendee` model (`calendar/model/CalendarEvent.kt:206-212`) has no `optional`, `resource`, `comment` or `additionalGuests`. A Google `PATCH` replaces array fields as a whole. So any cockpit edit of an event, even a title change, rewrites the attendee list at Google: optional attendees become required, and their comments and guest counts are lost. This is the same kind of lossy write as round-1 CR-2, but the recorded assumption covers only reminders.
    - Why it matters: Editing an unrelated field changes data that other attendees see in their own Google clients.
    - Suggested fix: Leave `attendees` out of patches when the attendee list is unchanged from Google's copy. Otherwise, record the lossy attendee mapping as an assumption (`tasks.py assume`), as was done for reminders.

- [ ] **CR-2 | The `toRemote` KDoc was garbled by the CR-1 fix**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/google/GoogleEventMapping.kt:51-55`
    - Category: Quality
    - Problem: The sentence added for the cancelled status runs into the existing sentence about `[calendarZone]` on one very long line. The KDoc now reads as one run-on paragraph, unlike the wrapping used in the rest of the file.
    - Why it matters: It makes the only place that documents the listing-versus-answer status rule harder to read.
    - Suggested fix: Re-wrap the KDoc: one sentence on the CANCELLED status for write answers, then the existing sentence on `calendarZone`/`defaultReminders`.

## Notes (not findings)

- Criteria 1-2 hold against the code:
    - calendar list with paging, skipping deleted and freeBusyReader calendars, and the read-only flag from `accessRole`;
    - `sync:` / `page:` / `full:` cursors;
    - 410 restarts with a complete listing during an incremental run or its pages, and is an error during a full listing (no loop);
    - writes send `If-Match`, and 412 (and 404/410 on update) becomes `RemoteEventChangedException`;
    - instance ids `<id>_<UTC basic>` and `<id>_<yyyyMMdd>`;
    - "this and following": the old series ends (UNTIL), a new series is inserted with a UUID-derived id, and its exception becomes a patch of the new instance. This runs end to end against Postgres and WireMock in `GoogleCalendarSyncTests`, together with the 410 resync and the 412 → 409 flow.
- Security: path and query values go through URI-template encoding. Error messages and logs contain no token, response body or event content. The bearer token goes only to the configured API URL, and only for GOOGLE connections.
- `python tasks.py verify cal-google` still shows no evidence for criteria 1-3. The parent worker must record it, including an actual run of `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.google.*'`. The reviewer did not run the suite.

## Review conclusion

No material issues found. The round-1 fixes are correct and tested. The two P3 findings are minor and can be fixed or recorded at the parent worker's discretion.
