---
title: Contact detail aggregation and autocomplete
status: in_progress
owner: dell26/agent-1
deps: [contacts-api,mail-read-api,cal-local-api,gtd-tasks-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/contacts/aggregate,server/src/test/kotlin/ch/sonensei/cockpit/contacts/aggregate]
prio: 2
lease_until: 2026-10-21T21:49:23Z
---
## Goal
Everything about a person (SPEC.md §8.3).

## Acceptance criteria
- [ ] `GET /api/v1/contacts/{id}/related`: recent mails from/to, upcoming events with them, Waiting For items
- [ ] `GET /api/v1/contacts/autocomplete?q=` merges contacts and recent correspondents
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.aggregate.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
