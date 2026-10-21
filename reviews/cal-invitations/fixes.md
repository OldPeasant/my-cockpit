# Fix Report

Date: 2026-10-21

Task: cal-invitations - Respond to invitations

Scope: current task; findings CR-1, CR-2, CR-3 of `reviews/cal-invitations/review.md`, as passed on by the parent worker (task commits d9006c8e3, 9ad9c5b9f, 64a9550e8).

## Fixed findings

### CR-1 — Microsoft series answer is undone by the refetch, and the returned ETag goes stale

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/GraphEventMapping.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/MicrosoftCalendarAdapter.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/invitations/InvitationService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/invitations/MicrosoftInvitationAdapterTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/invitations/MicrosoftInvitationApiTests.kt` (new)

Problem:
The Microsoft respond path set the user's attendee status only in the event it returned. The sync mapping read the status only from Graph's `attendees` list. When Graph still listed the user as `none`, the refetch that runs right after answering a series master (and any later sync of a newer Graph version) set the stored attendee back to NEEDS_ACTION and increased the version. The 200 response then carried an answer and an ETag that were no longer stored.

Fix:
- `GraphEventMapping.toRemote` takes an optional `self` (the user's address). The attendee whose email matches it (case-insensitive) gets the status from the event-level `responseStatus.response`, but only when the attendee itself shows no answer (`none`/`notResponded`) and `responseStatus` has one. The status mapping is shared with the attendee mapping (accepted/organizer, declined, tentativelyAccepted). Without `self` the behaviour is unchanged.
- `MicrosoftCalendarAdapter` passes the OAuth connection's email (`tokens.connection(id)?.email`, new helper `selfAddress`) on every read path: `fetchEvents` (single events, series masters and exceptions through `readSeries`), `currentSeries`, `answer` (create/update/occurrence writes and respond). Sync and respond now store the same state. Graph needs no `$select` change: the adapter does not use `$select`, so `responseStatus` is already in every event Graph returns.
- `InvitationService.respond`: after the series refetch, it reloads the event and returns it as stored now, with its current version/ETag (it falls back to the saved event if the refetch removed it). The response can no longer carry an ETag that the event no longer has, even when the refetch stores a newer Graph version.

Tests added:
- `MicrosoftInvitationApiTests` (Spring + Postgres via Testcontainers + WireMock as Graph): the test syncs a Microsoft series master, answers it with ACCEPTED through `POST /respond`, and has Graph return a newer version (`ser-3`) during the refetch. That version still lists the user's attendee as `none` and has `responseStatus: accepted`. The test checks that the refetch ran, that the stored attendee is ACCEPTED, that the stored external etag is `ser-3`, and that the response ETag and a later GET ETag both equal the stored version.
- `MicrosoftInvitationAdapterTests` `sync shows the user's answer from Graph's responseStatus when the attendee list does not show it yet`: `fetchEvents` maps the user's attendee to ACCEPTED, other attendees keep their own status, and without a connection email the attendee stays NEEDS_ACTION.
- Regression check: with the `responseStatus` merge temporarily turned off, both new tests failed. The API test got `$.attendees[1].status` NEEDS_ACTION instead of ACCEPTED, and the adapter test failed the same way. Both pass with the fix.

Validation:
- `./gradlew test --offline --tests 'ch.sonensei.cockpit.calendar.invitations.*' --tests 'ch.sonensei.cockpit.calendar.infra.microsoft.*'` — Pass
- `./gradlew ktlintCheck test --offline --tests 'ch.sonensei.cockpit.calendar.*'` — Pass (873 tests, 0 failures)

### CR-3 — Microsoft gives 502, not 409, when the user is not an attendee

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/MicrosoftCalendarAdapter.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/invitations/MicrosoftInvitationAdapterTests.kt`

Problem:
For an event the user is not invited to, `respondToInvitation` posted the Graph action anyway. Graph's error became `CalendarProviderException`, which the API returns as 502 instead of the documented 409.

Fix:
After the organizer check and before any action is posted, the adapter throws `InvitationNotRespondableException("You are not invited to this event")` (the Google adapter's message). It does this when the connection's email is known, no attendee has that address (case-insensitive), and Graph's `responseStatus` shows no invitation answer (none of `notResponded`, `accepted`, `declined`, `tentativelyAccepted`). The service maps this exception to 409. If the connection email is unknown, the event is answered as before. If the user is invited under another address but `responseStatus` shows an invitation, the event is answered as well. Graph 400/403 from the action is still a provider error (502). The existing test `the organizer cannot answer, and a failing action is a provider error` covers that.

Tests added (`MicrosoftInvitationAdapterTests`):
- `an event the user is not an attendee of and has not answered is not an invitation, nothing is sent`: throws `InvitationNotRespondableException` with "You are not invited to this event", and no POST reaches Graph.
- `an invitation Graph shows the user's answer to is answered even when no attendee has the connection's address`: with `responseStatus: notResponded` and an alias address, the accept action is posted.

Validation:
- `./gradlew test --offline --tests 'ch.sonensei.cockpit.calendar.invitations.*' ...` — Pass

## Not fixed

### CR-2 — Recurring and occurrence paths have no tests

Status:
Invalid (no longer valid)

Reason:
Tests added after the review cover it: `InvitationApiTests` (override answered through Google's instance, series answered and refetched, override of a series in the Trash gives 404) and `MicrosoftInvitationAdapterTests` (occurrence answered through its instance, missing occurrence/series, series master read back). All of them still pass. The new `MicrosoftInvitationApiTests` adds the Microsoft series-master path through the API. No code change was needed.

## Validation summary

Focused tests:
- `./gradlew test --offline --tests 'ch.sonensei.cockpit.calendar.invitations.*' --tests 'ch.sonensei.cockpit.calendar.infra.microsoft.*'` — Pass (InvitationApiTests 15, MicrosoftInvitationAdapterTests 15, MicrosoftInvitationApiTests 1, GoogleInvitationAdapterTests 11, CalendarProviderAdapterInvitationDefaultsTests 1, MicrosoftCalendarAdapterTests 67, MicrosoftCalendarSyncTests 5, GraphEventMappingTests 44, GraphRecurrenceTests 37, WindowsZonesTests 20; 0 failures)
- `./gradlew ktlintCheck test --offline --tests 'ch.sonensei.cockpit.calendar.*'` — Pass (873 tests, 0 failures)

Full test suite:
- `sh scripts/check.sh` — Fail: 3635 server tests, 1 failure in `IcsFeedClientTests` (`"The feed server could not be reached (IOException)" should include "larger than"`). That class and the ICS code are not touched by these fixes. The failure did not repeat: `./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.ics.IcsFeedClientTests'` passed 44/44, and the class also passed in the calendar.* run above, so it is an unrelated flaky test. The server tests stopped the script, so the web and android parts did not run. The run took about 6.5 minutes, above the 2-minute guideline.

Lint/type-check/build:
- `./gradlew ktlintCheck --offline` — Pass. The compile shows only two existing Kotlin warnings (unnecessary `!!` in `InvitationService.kt` lines 90 and 115); this fix did not add them.

`.taskcheck`:
- Fail (only the unrelated flaky `IcsFeedClientTests` described above); the parent worker should run it again.

Remaining unresolved findings:
0

Notes for the parent worker:
- Task assumption #4 says sync does not merge the event-level `responseStatus`. That is no longer true: sync now applies it to the user's own attendee when the attendee shows no answer. Record an updated assumption.
- One limit remains: if Graph's `attendees` shows an older non-`none` answer (e.g. accepted) while `responseStatus` shows a newer one (e.g. declined), the attendee list wins. This follows the scope the parent set (merge only when the attendee shows none/notResponded).
- `GraphEventMapping.kt` and `MicrosoftCalendarAdapter.kt` are outside the task's `touches`, as already recorded in task assumption #1.

## Final status

Fixes completed, but validation still fails: every validated finding is fixed and all focused tests and ktlint pass. The only failure is the unrelated, flaky `IcsFeedClientTests`, which made the one full `.taskcheck` run fail; it passed when re-run on its own.
