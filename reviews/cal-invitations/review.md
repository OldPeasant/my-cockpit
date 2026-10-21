# Code Review

Date: 2026-10-21

Task: cal-invitations - Respond to invitations

Scope: second review round (re-review after fixes). Whole final change of the task: git diff cba4177c4bd01221a608830a3cec18a6a78d5357..654f0968f5d1399be2de0bd675d2f215abbd1444 (commits 967809de6, 121fb209d, 5fe8ce31f, 654f0968f). Committed changes only. Generated files (api/openapi.yaml, web/src/app/api/generated/) were checked for consistency only.

Requirements:
- tasks/cal-invitations.md acceptance criteria and recorded assumptions. The 21:31 assumption replaces the earlier Microsoft-sync assumption.
- SPEC.md section 6.2 (invitations) and section 4.2 (ETag/If-Match, Problem Details)
- AGENTS.md project notes (module boundaries, generated files, logging)
- docs/QUALITY.md (report contract)
- Previous report (first round, CR-1..CR-3) and reviews/cal-invitations/fixes.md
- Test results reported by the parent worker and fixer: `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.calendar.*'` -> 873 tests, 0 failures. The reviewer did not re-run them.

Git range:
- Branch: main
- HEAD: 654f0968f5d1399be2de0bd675d2f215abbd1444
- Base/start: cba4177c4bd01221a608830a3cec18a6a78d5357
- Commits reviewed: 4

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Status of first-round findings

- CR-1 (Microsoft series answer undone by the refetch, stale ETag): Resolved.
    - `GraphEventMapping.attendees` applies the event-level `responseStatus` to the attendee that has the connection's address when that attendee shows no answer.
    - `MicrosoftCalendarAdapter` passes `selfAddress(connection)` on every read path (`fetchEvents`, `readSeries`, `currentSeries`, `answer`), so sync and respond store the same status.
    - `InvitationService.respond` reloads the event after the series refetch and returns its current version/ETag. It falls back to the saved event if the refetch removed it.
    - `MicrosoftInvitationApiTests` (Postgres + WireMock Graph) answers a series through the API. Graph returns a newer version during the refetch. The test checks the stored attendee status (ACCEPTED), the stored external etag, and that the response ETag and a later GET ETag both equal the stored version.
    - Behaviour matches the replacing task assumption.
- CR-2 (recurring/occurrence paths untested): Resolved. The following tests now exist:
    - `InvitationApiTests`: override answered through Google's instance; series answered and calendar refetched; override of a series in the Trash returns 404.
    - `MicrosoftInvitationAdapterTests`: occurrence answered through its instance; missing occurrence/series; series master read back.
    - `MicrosoftInvitationApiTests`: Microsoft series path through the API.
- CR-3 (Microsoft returned 502 instead of 409 for non-attendees): Resolved.
    - `MicrosoftCalendarAdapter.respondToInvitation` checks `invited(current, self)` before posting the action and throws `InvitationNotRespondableException`, which becomes 409.
    - Tests cover both cases: a non-attendee without an answer is refused with no POST sent; an alias address with `responseStatus` notResponded is still answered.
    - When the connection email is unknown, the event is answered as before.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.

Checked in this round:
- Acceptance criterion 1:
    - `POST /api/v1/calendar/events/{id}/respond` with body `{"response": ACCEPTED|DECLINED|TENTATIVE}` answers through `CalendarProviderAdapter.respondToInvitation`.
    - Google patches the `self` attendee with If-Match and `sendUpdates=all`. Microsoft calls the accept/decline/tentativelyAccept action with `sendResponse=true`.
    - The stored event takes the provider's version, with the user's attendee status.
- Error mapping:
    - If-Match is required: 428 when missing, 412 on mismatch.
    - 409 for organizer, non-attendee, read-only, unsynced or non-Google/Microsoft calendars; also for a change at the provider, after which the calendar is refetched.
    - 404 for an event in the Trash or an override of a series in the Trash.
    - 502 for other provider failures, as Problem Details.
- The provider call runs outside any transaction, and the save runs in its own transaction. `CalendarSyncService.refetch` only logs its failures, so a failed refetch after a series answer does not turn the answered request into an error.
- Graph's write body (`toBody`) does not include attendee status, so the new `self` merge cannot cause unwanted patches on edits.
- No secrets or event bodies are logged. The warn log contains only the calendar id and the provider message.
- `api/openapi.yaml` and the generated client (`respond-to-invitation.ts`, `RespondToInvitationRequest`) match the controller: path, POST method, If-Match header, required `response` enum, and the documented status codes.

Observation (not a finding; covered by the recorded assumption and the fixer's notes): if Graph's attendee list ever shows an older real answer for the user while `responseStatus` shows a newer one, sync keeps the attendee-list value. The respond path, however, always sets the new answer in its returned event. For a series, the refetch could then store the older value. Graph usually lists attendees as `none` in an attendee's copy, so this matches the documented precedence. The owner should revisit it if real Graph data shows otherwise.

Changes to `GoogleCalendarAdapter`, `GoogleEventMapping`, `GraphEventMapping`, `MicrosoftCalendarAdapter` and `CalendarProviderAdapter` are outside the task's `touches`. This is recorded in the first task assumption ("via the adapter").
