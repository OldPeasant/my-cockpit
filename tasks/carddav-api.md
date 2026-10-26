---
title: CardDAV accounts: REST API, web settings UI and backup
status: in_progress
owner: dell26/agent-1
deps: [carddav-sync]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/contacts/carddav,web/src/app/features/contacts,api/openapi.yaml,server/src/main/kotlin/ch/sonensei/cockpit/contacts/backup]
prio: 2
lease_until: 2026-10-26T12:52:27Z
---
## Goal
Users can add, list, sync and remove CardDAV accounts (carddav-sync built only the services:
CardDavAccountService, CardDavContactsSync.sync/createContact) and create contacts in a CardDAV address book.

## Acceptance criteria
- [ ] REST endpoints for CardDAV accounts (list/add/remove/sync now, status with lastError; password write-only), OpenAPI regenerated
- [ ] web contacts settings: add/remove account, show sync state; new contact can target a CardDAV account
- [ ] account config (password encrypted or omitted) covered by backup/restore and its round-trip test

## Notes
- ASSUMPTION (2026-10-26 13:28+0100 by dell26/agent-1): CardDAV account API at /api/v1/contacts/carddav-accounts (list/get/add/delete, POST /{id}/sync, POST /{id}/contacts to create a contact there); no edit endpoint: changing URL or password means removing and adding the account again
- ASSUMPTION (2026-10-26 13:28+0100 by dell26/agent-1): Creating a contact in a CardDAV account whose address book cannot be discovered answers 409 with the CardDAV error as detail (no 502 ApiException exists in core/web)
- ASSUMPTION (2026-10-26 13:28+0100 by dell26/agent-1): Backup: CardDAV account config in data/contacts_carddav_accounts.json, passwords only in the encrypted backup secrets, card links (incl. tombstones) in data/contacts_carddav_links.jsonl; sync token/status not backed up so the first sync after restore lists every card
- ASSUMPTION (2026-10-26 13:28+0100 by dell26/agent-1): Web: CardDAV accounts are managed in a panel on the Contacts page (task touches only features/contacts, not settings); adding an account syncs it at once so the discovery outcome shows; ContactRequest.parse moved from ContactController into ContactDtos for reuse
