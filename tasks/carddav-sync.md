---
title: CardDAV contacts sync
status: in_progress
owner: dell26/agent-1
deps: [contacts-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/contacts/carddav,server/src/test/kotlin/ch/sonensei/cockpit/contacts/carddav]
prio: 3
lease_until: 2026-10-26T11:46:49Z
---
## Goal
Contacts with CardDAV servers.

## Acceptance criteria
- [ ] sync-collection + writes; `external` Radicale tests

## Notes
- ASSUMPTION (2026-10-26 11:55+0100 by dell26/agent-1): CardDAV accounts are managed through CardDavAccountService (add/remove) and CardDavContactsSync.createContact only; no REST endpoints, OpenAPI or UI yet (touches is the carddav package only). Follow-up task carddav-api added for API, UI and backup of the account config.
- ASSUMPTION (2026-10-26 11:55+0100 by dell26/agent-1): One address book per account: the configured URL if it is an address book, else the first (by path) found via current-user-principal / addressbook-home-set.
- ASSUMPTION (2026-10-26 11:55+0100 by dell26/agent-1): Conflict policy mirrors Google contacts: on a stale ETag (changed at the server and locally) the server's version wins; deleting in the cockpit deletes the card unconditionally (no If-Match). Photos are not synced (cards' photos are ignored, kept on the server when unchanged).
- ASSUMPTION (2026-10-26 11:55+0100 by dell26/agent-1): Schema: new migration contacts/V202610261000__contacts_carddav_sync.sql (account + link tables), and VCardService.toChanges made public for reuse; both are outside the task's touches but small and needed.
- ASSUMPTION (2026-10-26 12:12+0100 by dell26/agent-1): Review CR-3: when a card fails to store, the new sync token is still saved (parity with GoogleContactsSync); the card returns when it changes at the server or the token is reset. Retrying failed hrefs is left for later.
- ASSUMPTION (2026-10-26 12:24+0100 by dell26/agent-1): A card that appears at the server under the href of a contact in the Trash whose link has no ETag (trashed by the sync because the card was removed, or trashed here and already deleted at the server) restores that contact with the card's content: the newer server-side change wins, nothing is deleted at the server (review CR-4/CR-5).
