---
title: Microsoft contacts sync
status: todo
owner:
deps: [contacts-api,oauth-connections]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/contacts/microsoft,server/src/test/kotlin/ch/sonensei/cockpit/contacts/microsoft]
prio: 3
---
## Goal
Two-way Graph contacts sync.

## Acceptance criteria
- [ ] Delta sync, writes; WireMock-recorded tests
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.microsoft.*'` passes (Postgres via Testcontainers where persistence is involved)

## Notes
