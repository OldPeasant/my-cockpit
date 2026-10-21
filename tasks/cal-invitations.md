---
title: Respond to invitations
status: in_progress
owner: dell26/agent-1
deps: [cal-google,cal-microsoft]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/invitations,server/src/test/kotlin/ch/sonensei/cockpit/calendar/invitations]
prio: 3
lease_until: 2026-10-21T19:58:24Z
---
## Goal
Accept/decline invitations for Google/Microsoft events (SPEC.md §6.2).

## Acceptance criteria
- [x] `POST /api/v1/calendar/events/{id}/respond {ACCEPTED|DECLINED|TENTATIVE}` via the adapter; attendee status reflected
  - verified (2026-10-21 21:43+0200 by dell26/agent-1): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.invitations.*' (Postgres Testcontainers + WireMock as Google/Graph) -> InvitationApiTests/MicrosoftInvitationApiTests pass: POST /api/v1/calendar/events/{id}/respond ACCEPTED/DECLINED/TENTATIVE answers via Google patch / Graph accept|decline|tentativelyAccept, stored attendee status + new ETag returned; 428/412/404/409/502 cases covered
- [x] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.invitations.*'` passes (Postgres via Testcontainers where persistence is involved)
  - verified (2026-10-21 21:43+0200 by dell26/agent-1): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.invitations.*' --tests '*OpenApiContractTest' -> BUILD SUCCESSFUL, 47 tests, 0 failures (5 invitation classes incl. Postgres Testcontainers API tests); also ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.calendar.*' -> 897 tests, 0 failures
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
- ASSUMPTION (2026-10-21 20:57+0200 by dell26/agent-1): The respond hook lives on CalendarProviderAdapter (respondToInvitation, default unsupported) and is implemented in GoogleCalendarAdapter/MicrosoftCalendarAdapter, outside the task's touches, because the criterion says 'via the adapter'
- ASSUMPTION (2026-10-21 20:57+0200 by dell26/agent-1): POST /respond requires If-Match (428/412) like other event mutations (SPEC 4.2); body is {"response": ACCEPTED|DECLINED|TENTATIVE}; a series master answers the whole series, an override its occurrence; there is no occurrence parameter for unmaterialized occurrences
- ASSUMPTION (2026-10-21 20:57+0200 by dell26/agent-1): The organizer is notified of the answer: Google patch with sendUpdates=all, Graph actions with sendResponse=true. Organizers and non-attendees get 409
- ASSUMPTION (2026-10-21 20:57+0200 by dell26/agent-1): Microsoft: the user's own attendee is matched by the OAuth connection's email and gets the new status in the returned event; regular sync still maps the attendee list as Graph sends it (event-level responseStatus not merged into sync)
- ASSUMPTION (2026-10-21 21:31+0200 by dell26/agent-1): Supersedes the Microsoft-sync assumption: Graph sync now applies the event-level responseStatus to the attendee matching the connection's email when the attendee list shows no answer (none/notResponded); a real answer in the attendee list still wins. A Graph 400/403 from the accept/decline action stays 502; non-attendees are detected before the action and get 409
