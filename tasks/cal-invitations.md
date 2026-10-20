---
title: Respond to invitations
status: todo
owner:
deps: [cal-google,cal-microsoft]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/invitations,server/src/test/kotlin/ch/sonensei/cockpit/calendar/invitations]
prio: 3
---
## Goal
Accept/decline invitations for Google/Microsoft events (SPEC.md §6.2).

## Acceptance criteria
- [ ] `POST /api/v1/calendar/events/{id}/respond {ACCEPTED|DECLINED|TENTATIVE}` via the adapter; attendee status reflected
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.invitations.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
