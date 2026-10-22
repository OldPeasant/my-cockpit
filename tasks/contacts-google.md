---
title: Google contacts sync
status: in_progress
owner: dell26/agent-1
deps: [contacts-api,oauth-connections]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/contacts/google,server/src/test/kotlin/ch/sonensei/cockpit/contacts/google]
prio: 2
lease_until: 2026-10-22T03:06:23Z
---
## Goal
Two-way People API sync.

## Acceptance criteria
- [ ] Incremental sync with sync tokens, writes with etag; WireMock-recorded tests
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.google.*'` passes (Postgres via Testcontainers where persistence is involved)

## Notes
- ASSUMPTION (2026-10-22 04:48+0200 by dell26/agent-1): Every CONNECTED Google OAuth connection whose scopes include .../auth/contacts is synced (the OAuth purpose is not stored); sync runs every cockpit.contacts.google.sync.interval (PT5M); local edits are pushed by the next run, not immediately
- ASSUMPTION (2026-10-22 04:48+0200 by dell26/agent-1): Sync state lives in new tables contacts_google_account (sync token, status) and contacts_google_link (contact->resourceName, etag, synced_version) via migration db/migration/contacts/V202610221100 (outside touches, needed); a local change = contact.version > synced_version, so no event listener is needed
