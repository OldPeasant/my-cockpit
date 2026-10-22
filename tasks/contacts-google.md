---
title: Google contacts sync
status: in_progress
owner: dell26/agent-1
deps: [contacts-api,oauth-connections]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/contacts/google,server/src/test/kotlin/ch/sonensei/cockpit/contacts/google]
prio: 2
lease_until: 2026-10-22T02:58:14Z
---
## Goal
Two-way People API sync.

## Acceptance criteria
- [ ] Incremental sync with sync tokens, writes with etag; WireMock-recorded tests
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.google.*'` passes (Postgres via Testcontainers where persistence is involved)

## Notes
