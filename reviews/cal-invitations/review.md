# Code Review

Date: 2026-10-21

Task: cal-invitations - Respond to invitations

Scope: commits da27226d5 and f0fe1165f on main (git diff 3925fb00fdb2cb5138e9b41e629e5d79dc9d753c..f0fe1165fda1d36c5e01348bb79129370ebd7fc8); committed changes only. Generated files (api/openapi.yaml, web/src/app/api/generated/) checked for consistency only.

Requirements:
- tasks/cal-invitations.md acceptance criteria and recorded assumptions (#1-#4)
- SPEC.md section 6.2 (invitations, conflict policy) and section 4.2 (ETag/If-Match, Problem Details)
- AGENTS.md project notes (module boundaries, generated files, logging)
- docs/QUALITY.md (report contract)
- Test results supplied by the parent worker (22 invitation tests pass, OpenApiContractTest passes without the flag); not re-run by the reviewer

Git range:
- Branch: main
- HEAD: f0fe1165fda1d36c5e01348bb79129370ebd7fc8
- Base/start: 3925fb00fdb2cb5138e9b41e629e5d79dc9d753c
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 2 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Microsoft series answer is undone by the refetch that runs right after it, and the returned ETag goes stale**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/MicrosoftCalendarAdapter.kt:441-442`, `server/src/main/kotlin/ch/sonensei/cockpit/calendar/invitations/InvitationService.kt:115`
    - Category: Correctness
    - Problem: For Microsoft, the adapter sets the user's attendee to the new status only in the `RemoteEvent` it returns. It matches that attendee by the connection email because, per the code comment and the test `the user's attendee shows the answer even when Graph's copy does not list it yet`, Graph may still list the user's attendee as `none`. The service saves that event. For a series master (`event.isRecurring`) it then calls `sync.refetch(target.calendarId)` in the same request. The refetch maps the master again through `GraphEventMapping` (attendee status read from the `attendees` list only, `GraphEventMapping.kt:243-252`). `RemoteEventMapping.copyFrom` then overwrites `attendees` (`RemoteEventMapping.kt:82`). If Graph still lists `none`, the stored attendee goes back to NEEDS_ACTION and the event version increases. The 200 response still shows the answer and an ETag the event no longer has. The client's next If-Match on that event therefore gets 412, and a reload shows the invitation as unanswered. Single events and overrides have the same problem, but only on the next regular sync (this matches recorded assumption #4). For series masters the reversal happens at once, inside the respond request.
    - Why it matters: Acceptance criterion 1 ("attendee status reflected") then holds only in the HTTP response, not in stored state, for Microsoft series. This is the case the adapter's own email match was written for. The ETag returned to the client is also wrong.
    - Suggested fix: Make the sync mapping show the user's own answer. One way is to apply Graph's event-level `responseStatus` to the attendee matching the connection email in `GraphEventMapping`/the adapter's read path, so that sync and respond give the same result. Alternatively, skip the extra refetch for Microsoft, or return the event as stored after the refetch. Add a test that answers a Microsoft series master through the API and checks the stored attendee status and the ETag after the refetch.

- [ ] **CR-2 | Recurring and occurrence paths have no tests**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/calendar/invitations/InvitationApiTests.kt:124-250`, `server/src/test/kotlin/ch/sonensei/cockpit/calendar/invitations/MicrosoftInvitationAdapterTests.kt:62-146`
    - Category: Tests
    - Problem: The API tests cover only single (non-recurring) Google events. No test covers these paths through the service: answering an override, where the uid comes from the parent master and the key carries a `recurrenceId` (`InvitationService.kt:134-136`); answering a series master, which saves and then refetches (`InvitationService.kt:115`); or an override whose master is in the Trash, which should give 404 (`InvitationService.kt:143`). The Microsoft adapter tests do not cover the occurrence path (`findInstance`, instance id for the action) or the series-master path (`currentSeries`) (`MicrosoftCalendarAdapter.kt:409-412, 434-436`). The task assumption (#2) commits to these behaviours.
    - Why it matters: The documented semantics ("a series master answers the whole series, an override its occurrence") are untested. CR-1 lives in exactly this untested path.
    - Suggested fix: Add an API test for a Google series master and one for an override (checking the instance id, the stored status and the refetch). Add Microsoft adapter tests for an occurrence (action posted on the instance id) and a series master (series read back).

## P3 Low

- [ ] **CR-3 | Microsoft gives 502, not 409, when the user is not an attendee**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/MicrosoftCalendarAdapter.kt:422-432`
    - Category: Integration
    - Problem: The adapter contract (`CalendarProviderAdapter.kt` KDoc) and the API docs (409 "the user is not invited to it") promise `InvitationNotRespondableException`/409 for events the user is not invited to. The Google adapter does this. The Microsoft adapter checks only `isOrganizer`. For an event without the user as attendee (for example, a writable calendar shared by another mailbox), the Graph action fails, which becomes `CalendarProviderException`, which becomes 502 "The calendar provider failed".
    - Why it matters: For the same situation the two providers return different status codes, and the documented contract is not met for Microsoft.
    - Suggested fix: Before posting the action, check that the event has an attendee matching the connection email (or that `responseRequested`/`responseStatus` show an invitation), and throw `InvitationNotRespondableException` if not. Alternatively, map Graph's 400/403 from the action to that exception.

## Review conclusion

Material issues found; fix before completing the task. No P0/P1 issues were found. The Google path, the If-Match handling (428/412), the Problem Details mapping, transaction boundaries, logging (no secrets or bodies) and the regenerated OpenAPI/client look consistent. CR-1 (Microsoft series answer reverted by the immediate refetch, plus a stale ETag) and its missing tests (CR-2) should be fixed or explicitly accepted by the parent worker before `done`.
