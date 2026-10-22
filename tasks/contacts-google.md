---
title: Google contacts sync
status: in_progress
owner: dell26/agent-1
deps: [contacts-api,oauth-connections]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/contacts/google,server/src/test/kotlin/ch/sonensei/cockpit/contacts/google]
prio: 2
lease_until: 2026-10-22T03:41:42Z
---
## Goal
Two-way People API sync.

## Acceptance criteria
- [ ] Incremental sync with sync tokens, writes with etag; WireMock-recorded tests
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.google.*'` passes (Postgres via Testcontainers where persistence is involved)

## Notes
- ASSUMPTION (2026-10-22 04:48+0200 by dell26/agent-1): Every CONNECTED Google OAuth connection whose scopes include .../auth/contacts is synced (the OAuth purpose is not stored); sync runs every cockpit.contacts.google.sync.interval (PT5M); local edits are pushed by the next run, not immediately
- ASSUMPTION (2026-10-22 04:48+0200 by dell26/agent-1): Sync state lives in new tables contacts_google_account (sync token, status) and contacts_google_link (contact->resourceName, etag, synced_version) via migration db/migration/contacts/V202610221100 (outside touches, needed); a local change = contact.version > synced_version, so no event listener is needed
- ASSUMPTION (2026-10-22 04:48+0200 by dell26/agent-1): Conflicts: Google wins. A stale etag on updateContact (400 FAILED_PRECONDITION or 412) re-reads the person and applies it; a 404 on update trashes the local contact. Expired sync token (400 EXPIRED_SYNC_TOKEN or 410) -> full listing, contacts missing from it go to the Trash
- ASSUMPTION (2026-10-22 04:48+0200 by dell26/agent-1): Deletes: a trashed GOOGLE contact is deleted at Google (deleteContact); a restored one (or one deleted at Google, then restored) is created again with createContact and gets the new resourceName as externalId (set without bumping the version). LOCAL contacts are never pushed to Google
- ASSUMPTION (2026-10-22 04:48+0200 by dell26/agent-1): Mapping: primary/first name, organization (name+title) and biography; all emails/phones/addresses with Google type as label; birthdays only with a year; photos, other organizations and other Person fields are not synced (an update replaces those lists at Google). Invalid emails dropped, values cut to contact limits, phone-only persons named by phone, else 'Unnamed contact'
- ASSUMPTION (2026-10-22 04:48+0200 by dell26/agent-1): On OAuthConnectionDisconnected the Google sync state and links are deleted; the contacts stay as unsynced GOOGLE contacts. Added ContactService.createSynced (contacts/domain, outside touches) and test property cockpit.contacts.google.sync.scheduler.enabled=false
