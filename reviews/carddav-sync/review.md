# Code Review

Date: 2026-10-26

Task: carddav-sync - CardDAV contacts sync

Scope: `git diff d6a1773e1..24808c2d6 -- server` (committed changes only; `tasks/` and `reviews/` ignored as instructed). Focus on the fix commit 24808c2d6 and the unit tests added in 6a7b049de and b6de0272b; the whole acceptance criterion was re-checked against the final code.

Requirements:
- tasks/carddav-sync.md acceptance criterion: "sync-collection + writes; `external` Radicale tests", plus the recorded assumptions in its Notes (no REST API/UI yet, one address book per account, server wins on conflicts, unconditional deletes from the cockpit, photos not synced, new migration and public VCardService.toChanges, CR-3 accepted: the sync token is saved even when a card fails to store)
- SPEC.md §8.3 (contacts, source CARDDAV), §4 conventions, §14
- docs/ARCHITECTURE.md (module layout, Flyway naming, `@ExternalTest` / `integrationTest`, coverage)
- AGENTS.md project notes (logging, migrations, lint)
- Previous review of this task (on 37116288b, CR-1 to CR-4) and reviews/carddav-sync/fixes.md
- Reference implementation for parity: contacts/google/GoogleContactsSync.kt

Git range:
- Branch: main
- HEAD: 24808c2d671cb589ebde943ab9b25fe34c05f615
- Base/start: d6a1773e14b8e11dd032faac22eb397789050106
- Commits reviewed: 5 touching `server/` (f5cf05ae6 implementation, 5324d9dc8 Radicale tests, 6a7b049de and b6de0272b unit tests, 24808c2d6 fixes for CR-2 and CR-4); the other commits in the range touch only `tasks/`
- Uncommitted changes: none

Not run by the reviewer: Gradle. The parent reports on 24808c2d6: ktlintCheck clean; `./gradlew test --tests 'ch.sonensei.cockpit.contacts.carddav.*'` 57 tests, 0 failures; `./gradlew integrationTest --tests 'ch.sonensei.cockpit.contacts.carddav.*' --rerun` (Radicale) 9 tests, 0 failures. This review relies on those results and on reading the code.

Summary: 0 P0, 0 P1, 1 P2, 0 P3

## Status of the previous findings

- CR-1 (P2, missing unit tests): resolved. CardDavClientTests covers Basic auth, 401/5xx messages without password or body, same-origin redirects and refused cross-origin redirects and requests, href resolution within the origin, redirect loops and the retry rules (safe and If-Match writes retried, unguarded writes not). MultiStatusTests covers XXE and entity-expansion rejection, non-XML and non-multistatus roots, per-response status, missing hrefs and sync tokens. CardDavMappingTests covers `merged` (null when nothing differs, only changed groups replaced, N prefix kept, cleared fields removed, server vCard version kept) and `newCard`. CardDavContactsSyncUnitTests covers the PROPFIND fallback, foreign hrefs ignored, discovery from an address-book URL and through principal and home set, 507 paging, 412 on If-Match (server wins), adoption of a restored contact and a trashed contact giving up its id.
- CR-2 (P3, redundant REPORT): resolved. `CardDavContactsSync.kt:375` sends the token REPORT only when a token exists; a refused token still falls back once to a token-less REPORT, then PROPFIND. Both paths are tested (`without a token a server that refuses sync-collection gets one REPORT, then PROPFIND`, `a refused token is retried once without a token`).
- CR-3 (P3, failed cards dropped from later runs): accepted as an assumption in the task Notes (parity with GoogleContactsSync); the unit test `a card that cannot be stored is skipped and counted` pins the current behaviour. Not re-reported.
- CR-4 (P3, card back under the href of a trashed contact): the passive loss is fixed, but the chosen fix now deletes that card at the server. Reported again as CR-5 below.

Verified without new findings: sync-collection pull with token, invalid-token fallback and 507 paging; If-Match / If-None-Match writes and 412 handling; tombstones for purged contacts; same-origin enforcement and XXE hardening; logging without passwords or card data; HTTP outside transactions with one transaction per stored card; the migration; the `external` Radicale suite (9 tests, reported passing) covering discovery, incremental and full sync, writes with If-Match, conflicts, create/delete, purge and wrong password.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-5 | The CR-4 fix deletes, at the server, a card the user brought back there**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/contacts/carddav/CardDavContactsSync.kt:532-534`
    - Category: Correctness
    - Problem: When a card reappears under the href of a link that has no ETag and a contact in the Trash, `store` now records the card's ETag (`if (link.etag == null) link.etag = etag`). The next push then sees `contact.isDeleted && link.etag != null` (`:221`) and sends DELETE for that card. The usual way to reach this state is `removedAtServer` (`:545-560`): the card was deleted at the server, so the sync itself moved the contact to the Trash. If the user then undeletes or re-imports the card at the server, the next two runs delete it again and the card's current content is lost (the cockpit Trash only holds the older version). The unit test `a card back under the href of a contact trashed after its removal gets its ETag, and the next push deletes it` (`CardDavContactsSyncUnitTests.kt:433-462`) asserts exactly this DELETE. Before the fix the card was only not imported; now the server data is removed. The fixer's argument ("a contact in the Trash is deleted at the server") applies to contacts the user trashed in the cockpit. Here the Trash state came from the server's own deletion, and the card coming back is the newer user action. The assumption in the task Notes only covers "deleting in the cockpit deletes the card unconditionally". GoogleContactsSync never reaches this case: Google does not reuse resource names, so a returning person goes through the no-link branch and becomes a new contact.
    - Why it matters: A sync that removes data the user just restored at the server is a data-loss path. The case is narrow, but the behaviour is destructive where the previous one was only passive.
    - Suggested fix: Do not turn a returning card into a DELETE. Treat it like the no-link branch (and like Google): import the card as a new contact and let the trashed contact give up its external id. For example, in the `contact.isDeleted && link.etag == null` case, delete the old link, flush, clear the trashed contact's external id and create a new contact and link for the href in the same transaction. Alternatively, restore the trashed contact and apply the card to it ("server wins"). If the current behaviour is kept on purpose, record it as an assumption the human can review, since it goes beyond the recorded "deleting in the cockpit" rule. Update the unit test to match the chosen behaviour.

## P3 Low

None.

## Review conclusion

Material issues found; fix before completing the task. The acceptance criterion (sync-collection pull, If-Match / If-None-Match writes, `external` Radicale tests) is implemented and covered. CR-1 and CR-2 are resolved and CR-3 is an accepted assumption. CR-5 is a P2 finding introduced by the CR-4 fix. It should be fixed, or recorded as an explicit assumption, before `done`.
