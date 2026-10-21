# Code Review

Date: 2026-10-21

Task: cal-google - Google Calendar adapter (SPEC.md §6.2)

Scope: `git diff 04c871d6..31f638f9`, committed changes only (server `calendar/infra/google` main code, tests and WireMock recordings)

Requirements:
- tasks/cal-google.md acceptance criteria 1-3
- SPEC.md §6.1, §6.2, §4.2
- AGENTS.md project notes (module boundaries, no secrets or event content in logs at INFO or above)
- Sync engine contract: `calendar/sync/CalendarProviderAdapter.kt`, `CalendarSyncService.kt`, `CalendarWriteThrough.kt`, `RemoteEventMapping.kt`
- Parent agent instructions

Git range:
- Branch: main
- HEAD: 31f638f96e419a6f5308bed441f0a5e09c0c0eeb
- Base/start: 04c871d6f9a1996de04712dfb6f11ccf850c2e0c
- Commits reviewed: 4 (66e272e2, 54386289, 052654e6, 31f638f9); working tree clean

Summary: 0 P0, 1 P1, 0 P2, 2 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | Google's answer with status "cancelled" is read back as CONFIRMED, so a deleted edited occurrence comes back locally**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/google/GoogleEventMapping.kt:74` (used by `GoogleCalendarAdapter.answer`, `GoogleCalendarAdapter.kt:269-275`)
    - Category: Correctness
    - Problem: `toRemote` maps every status except `"tentative"` to `EventStatus.CONFIRMED`. If the user deletes "this occurrence" and that occurrence already has an override, `CalendarEventService.deleteOccurrence` (`CalendarEventService.kt:249-251`) sets the override's status to `CANCELLED` and records an UPDATE. Write-through then calls `updateEvent`. `toBody` sends `"status": "cancelled"` (`GoogleEventMapping.kt:135`), which cancels the instance at Google. Google answers with the instance and `status: "cancelled"`. `answer()` turns that into a CONFIRMED `RemoteEvent`, and `CalendarWriteThrough.write` calls `event.copyFrom(stored)`, which overwrites the override's status with CONFIRMED and marks it SYNCED. The occurrence the user just deleted is committed as visible and confirmed again. It only goes away after the next incremental sync delivers the cancelled instance as a deletion. If the user edits it in the meantime, the patch sends `status: "confirmed"` and restores the occurrence at Google. The same mapping affects the cancelled overrides that a "this and following" split re-creates on the new series (`createEvent` with a recurrence id), and the 409 path of `createEvent` when the existing id belongs to a deleted event.
    - Why it matters: Criterion 1 requires that recurring instance edits work. Removing an edited occurrence is one of these edits, and here it gives the wrong local state and can resurrect the occurrence at Google.
    - Suggested fix: Map `"cancelled"` to `EventStatus.CANCELLED` when reading a write answer, or keep the local status when the answer is cancelled. Then the override stays CANCELLED, which is how the engine hides removed occurrences. Add an adapter test (patch answer with `status: "cancelled"`) and a write-through test for deleting an occurrence that has an override.

## P2 Medium

None.

## P3 Low

- [ ] **CR-2 | Every write converts Google e-mail reminders and default reminders into popup overrides**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/google/GoogleEventMapping.kt:132-134`
    - Category: Correctness
    - Problem: On read, `minutes()` keeps both `popup` and `email` reminders, and `useDefault` is resolved to the calendar's defaults (`GoogleEventMapping.kt:229-238`). On write, `toBody` always sends `useDefault: false` and makes every minute a `popup` override. So any cockpit edit, even just the title, silently changes the event's reminders at Google: e-mail reminders become popups, and events that use the calendar defaults get fixed overrides. Neither the code nor an assumption documents this.
    - Why it matters: Editing an unrelated field changes provider data the user never touched, and the change shows up in every other Google client.
    - Suggested fix: Leave `reminders` out of patches when the reminder minutes did not change, or record the lossy mapping as an assumption (`tasks.py assume`).

- [ ] **CR-3 | The adapter sends the connection's bearer token without checking that it is a Google connection**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/google/GoogleCalendarAdapter.kt:287-296`, `GoogleCalendarClient.kt:64,75`
    - Category: Security
    - Problem: `connectionId(source)` takes any UUID from the source config, and the client sends that connection's API access token to the Google API. Nothing in the adapter or in the calendar module (`grep` finds no validation of `connectionId` outside `infra/google`) checks that the connection's provider is Google. If a source is misconfigured with a Microsoft connection, a Microsoft Graph access token goes to Google.
    - Why it matters: A credential goes to a third party it was not issued for. The risk is low because the app has a single user, but the check is cheap.
    - Suggested fix: Before using the connection, check `tokens.connection(id)?.provider == OAuthProvider.GOOGLE`, and otherwise fail with a `CalendarProviderException` ("The source is not linked to a Google connection").

## Notes (not findings)

- Criteria 1-2 otherwise hold up against the code:
    - calendar list with paging, and the read-only flag from `accessRole`;
    - `syncToken` cursors, and 410 restarting a full listing (`complete = true`) without looping when the 410 comes during a full listing;
    - writes use `If-Match`, and 412 becomes `RemoteEventChangedException`;
    - instance ids `<id>_<UTC basic>` and `<id>_<date>`;
    - "this and following" goes through the engine's UNTIL plus new-series writes, covered end to end in `GoogleCalendarSyncTests` against Postgres.
- Path and query values go through URI-template encoding (tested with `#` and `@` in calendar ids). Error messages and logs contain no tokens, response bodies or event content. The only INFO log has no content.
- `python tasks.py verify cal-google` shows no evidence recorded yet for criteria 1-3. The parent worker must record it, including an actual run of `./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.google.*'`. The reviewer did not run the suite.

## Review conclusion

Material issues found; fix before completing the task.
