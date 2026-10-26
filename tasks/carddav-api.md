---
title: CardDAV accounts: REST API, web settings UI and backup
status: done
owner: dell26/agent-1
deps: [carddav-sync]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/contacts/carddav,web/src/app/features/contacts,api/openapi.yaml,server/src/main/kotlin/ch/sonensei/cockpit/contacts/backup]
prio: 2
lease_until: 
---
## Goal
Users can add, list, sync and remove CardDAV accounts (carddav-sync built only the services:
CardDavAccountService, CardDavContactsSync.sync/createContact) and create contacts in a CardDAV address book.

## Acceptance criteria
- [x] REST endpoints for CardDAV accounts (list/add/remove/sync now, status with lastError; password write-only), OpenAPI regenerated
  - verified (2026-10-26 14:28+0100 by dell26/agent-1): cd server && ./gradlew test --tests '*OpenApiContractTest' --tests '*CardDavAccountWebTests' -> BUILD SUCCESSFUL (OpenApiContractTest: api/openapi.yaml matches the server incl. /contacts/carddav-accounts paths, password writeOnly; CardDavAccountWebTests 19: list/get/add 201+Location/delete 404/sync now with NEVER/OK/ERROR+lastError, no password in any response)
- [x] web contacts settings: add/remove account, show sync state; new contact can target a CardDAV account
  - verified (2026-10-26 14:08+0100 by dell26/agent-1): cd web && npx ng test --watch=false --include='src/app/features/contacts/**/*.spec.ts' -> 127 passed (carddav-accounts.spec: add/remove/sync now/state+lastError; contact-detail: Save to CardDAV account POSTs /carddav-accounts/{id}/contacts, 409 shows reason; contacts-page shows panel); npm run build:de + messages-de spec pass
- [x] account config (password encrypted or omitted) covered by backup/restore and its round-trip test
  - verified (2026-10-26 14:08+0100 by dell26/agent-1): cd server && ./gradlew test --tests '*CardDav*' --tests '*ContactsBackupContributorTests' --tests '*BackupRoundTrip*' -> 86 passed, 0 failed (BackupRoundTripTest incl. CardDAV accounts+links; ContactsBackupContributorTests 17: password only in encrypted secrets with passphrase, restored+re-encrypted on import, null without passphrase, accounts JSON has no password)

## Notes
- ASSUMPTION (2026-10-26 13:28+0100 by dell26/agent-1): CardDAV account API at /api/v1/contacts/carddav-accounts (list/get/add/delete, POST /{id}/sync, POST /{id}/contacts to create a contact there); no edit endpoint: changing URL or password means removing and adding the account again
- ASSUMPTION (2026-10-26 13:28+0100 by dell26/agent-1): Creating a contact in a CardDAV account whose address book cannot be discovered answers 409 with the CardDAV error as detail (no 502 ApiException exists in core/web)
- ASSUMPTION (2026-10-26 13:28+0100 by dell26/agent-1): Backup: CardDAV account config in data/contacts_carddav_accounts.json, passwords only in the encrypted backup secrets, card links (incl. tombstones) in data/contacts_carddav_links.jsonl; sync token/status not backed up so the first sync after restore lists every card
- ASSUMPTION (2026-10-26 13:28+0100 by dell26/agent-1): Web: CardDAV accounts are managed in a panel on the Contacts page (task touches only features/contacts, not settings); adding an account syncs it at once so the discovery outcome shows; ContactRequest.parse moved from ContactController into ContactDtos for reuse
- ASSUMPTION (2026-10-26 13:54+0100 by dell26/agent-1): CR-5 fix added ContactService.validateNew (contacts/domain, outside touches) so CardDAV contact creation validates fields before address book discovery: invalid input gets 400 without a remote call
- ASSUMPTION (2026-10-26 13:56+0100 by dell26/agent-1): CR-7: German translations for the new contacts.carddav/saveTo messages added in web/src/locale (outside touches), as docs/ARCHITECTURE.md i18n requires for every new message
- done by dell26/agent-1: `sh scripts/check.sh` passed in 83s on 2f345cbe3; criteria verified 3/3
