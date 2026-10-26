---
title: CardDAV contacts sync
status: in_progress
owner: dell26/agent-1
deps: [contacts-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/contacts/carddav,server/src/test/kotlin/ch/sonensei/cockpit/contacts/carddav]
prio: 3
lease_until: 2026-10-26T12:12:04Z
---
## Goal
Contacts with CardDAV servers.

## Acceptance criteria
- [x] sync-collection + writes; `external` Radicale tests
  - verified (2026-10-26 12:34+0100 by dell26/agent-1): cd server && ./gradlew integrationTest --tests 'ch.sonensei.cockpit.contacts.carddav.*' --rerun (external tag, Radicale 3.5.4.0 via Testcontainers/Podman; sync-collection pull, If-Match/If-None-Match PUT, DELETE, 412 server-wins, purge) on fbf43f537 (prod code = final f6b0cfde5) -> 9 tests, 0 failures; ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.contacts.carddav.*' on f6b0cfde5 -> 58 tests, 0 failures (client same-origin/XXE, discovery, 507 paging, PROPFIND fallback, adoption, CR-2/4/5/6)

## Notes
- ASSUMPTION (2026-10-26 11:55+0100 by dell26/agent-1): CardDAV accounts are managed through CardDavAccountService (add/remove) and CardDavContactsSync.createContact only; no REST endpoints, OpenAPI or UI yet (touches is the carddav package only). Follow-up task carddav-api added for API, UI and backup of the account config.
- ASSUMPTION (2026-10-26 11:55+0100 by dell26/agent-1): One address book per account: the configured URL if it is an address book, else the first (by path) found via current-user-principal / addressbook-home-set.
- ASSUMPTION (2026-10-26 11:55+0100 by dell26/agent-1): Conflict policy mirrors Google contacts: on a stale ETag (changed at the server and locally) the server's version wins; deleting in the cockpit deletes the card unconditionally (no If-Match). Photos are not synced (cards' photos are ignored, kept on the server when unchanged).
- ASSUMPTION (2026-10-26 11:55+0100 by dell26/agent-1): Schema: new migration contacts/V202610261000__contacts_carddav_sync.sql (account + link tables), and VCardService.toChanges made public for reuse; both are outside the task's touches but small and needed.
- ASSUMPTION (2026-10-26 12:12+0100 by dell26/agent-1): Review CR-3: when a card fails to store, the new sync token is still saved (parity with GoogleContactsSync); the card returns when it changes at the server or the token is reset. Retrying failed hrefs is left for later.
- ASSUMPTION (2026-10-26 12:24+0100 by dell26/agent-1): A card that appears at the server under the href of a contact in the Trash whose link has no ETag (trashed by the sync because the card was removed, or trashed here and already deleted at the server) restores that contact with the card's content: the newer server-side change wins, nothing is deleted at the server (review CR-4/CR-5).
