---
title: CardDAV accounts: REST API, web settings UI and backup
status: in_progress
owner: dell26/agent-1
deps: [carddav-sync]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/contacts/carddav,web/src/app/features/contacts,api/openapi.yaml,server/src/main/kotlin/ch/sonensei/cockpit/contacts/backup]
prio: 2
lease_until: 2026-10-26T12:47:23Z
---
## Goal
Users can add, list, sync and remove CardDAV accounts (carddav-sync built only the services:
CardDavAccountService, CardDavContactsSync.sync/createContact) and create contacts in a CardDAV address book.

## Acceptance criteria
- [ ] REST endpoints for CardDAV accounts (list/add/remove/sync now, status with lastError; password write-only), OpenAPI regenerated
- [ ] web contacts settings: add/remove account, show sync state; new contact can target a CardDAV account
- [ ] account config (password encrypted or omitted) covered by backup/restore and its round-trip test

## Notes
