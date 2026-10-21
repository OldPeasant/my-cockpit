---
title: Respond to invitations
status: in_progress
owner: dell26/agent-1
deps: [cal-google,cal-microsoft]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/invitations,server/src/test/kotlin/ch/sonensei/cockpit/calendar/invitations]
prio: 3
lease_until: 2026-10-21T19:13:09Z
---
## Goal
Accept/decline invitations for Google/Microsoft events (SPEC.md §6.2).

## Acceptance criteria
- [ ] `POST /api/v1/calendar/events/{id}/respond {ACCEPTED|DECLINED|TENTATIVE}` via the adapter; attendee status reflected
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.invitations.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
- ASSUMPTION (2026-10-21 20:57+0200 by dell26/agent-1): The respond hook lives on CalendarProviderAdapter (respondToInvitation, default unsupported) and is implemented in GoogleCalendarAdapter/MicrosoftCalendarAdapter, outside the task's touches, because the criterion says 'via the adapter'
