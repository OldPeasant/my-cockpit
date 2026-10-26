---
title: CardDAV contacts sync
status: in_progress
owner: dell26/agent-1
deps: [contacts-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/contacts/carddav,server/src/test/kotlin/ch/sonensei/cockpit/contacts/carddav]
prio: 3
lease_until: 2026-10-26T11:11:34Z
---
## Goal
Contacts with CardDAV servers.

## Acceptance criteria
- [ ] sync-collection + writes; `external` Radicale tests

## Notes
- ASSUMPTION (2026-10-26 11:55+0100 by dell26/agent-1): CardDAV accounts are managed through CardDavAccountService (add/remove) and CardDavContactsSync.createContact only; no REST endpoints, OpenAPI or UI yet (touches is the carddav package only). Follow-up task carddav-api added for API, UI and backup of the account config.
- ASSUMPTION (2026-10-26 11:55+0100 by dell26/agent-1): One address book per account: the configured URL if it is an address book, else the first (by path) found via current-user-principal / addressbook-home-set.
