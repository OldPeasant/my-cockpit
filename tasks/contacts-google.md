---
title: Google contacts sync
status: todo
owner:
deps: [contacts-api,oauth-connections]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/contacts/google,server/src/test/kotlin/ch/sonensei/cockpit/contacts/google]
prio: 2
---
## Goal
Two-way People API sync.

## Acceptance criteria
- [ ] Incremental sync with sync tokens, writes with etag; WireMock-recorded tests
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.google.*'` passes (Postgres via Testcontainers where persistence is involved)

## Notes
