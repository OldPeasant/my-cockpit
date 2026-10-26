# Code Review

Date: 2026-10-26

Task: carddav-sync - CardDAV contacts sync

Scope: `git diff d6a1773e1..fbf43f537 -- server` (committed changes only; `tasks/` and `reviews/` ignored as instructed). Focus on the CR-5 fix (`git diff 24808c2d6..fbf43f537 -- server`: `CardDavContactsSync.store` and CardDavContactsSyncUnitTests); the whole acceptance criterion was re-checked against the final code.

Requirements:
- tasks/carddav-sync.md acceptance criterion: "sync-collection + writes; `external` Radicale tests", plus the recorded assumptions in its Notes (no REST API/UI yet, one address book per account, server wins on conflicts, unconditional deletes from the cockpit, photos not synced, new migration and public VCardService.toChanges, CR-3 accepted, and the CR-5 edge case: a contact trashed here whose card was already deleted at the server is restored when a card reappears under the same href)
- SPEC.md §8.3 (contacts, source CARDDAV), §4 conventions, §14
- docs/ARCHITECTURE.md (module layout, Flyway naming, `@ExternalTest` / `integrationTest`, coverage)
- AGENTS.md project notes (logging, migrations, lint)
- Previous reviews of this task (CR-1 to CR-5) and reviews/carddav-sync/fixes.md
- Reference implementations: contacts/google/GoogleContactsSync.kt, contacts/infra/ContactItems.kt (ContactTrashContributor.restore)

Git range:
- Branch: main
- HEAD: fbf43f53756057018dd0c85f248064a8f3e497cb
- Base/start: d6a1773e14b8e11dd032faac22eb397789050106
- Commits reviewed: 6 touching `server/` (f5cf05ae6 implementation, 5324d9dc8 Radicale tests, 6a7b049de and b6de0272b unit tests, 24808c2d6 fixes for CR-2 and CR-4, fbf43f537 fix for CR-5); the other commits in the range touch only `tasks/` or `reviews/`
- Uncommitted changes: none

Not run by the reviewer: Gradle. The parent reports on fbf43f537: ktlint clean; carddav unit tests 58, 0 failures; Radicale `integrationTest` 9, 0 failures. This review relies on those results and on reading the code.

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Status of the previous findings

- CR-1 (P2, missing unit tests): resolved. The client, MultiStatus parser, mapping, account service and sync branches that Radicale does not take have unit tests: same-origin redirects and hrefs, XXE and entity expansion, the retry rules, `merged`, discovery, 507 paging, the PROPFIND fallback, a 412 on If-Match, and adoption.
- CR-2 (P3, redundant REPORT): resolved (`CardDavContactsSync.kt:376`). Both paths are tested.
- CR-3 (P3, failed cards dropped from later runs): accepted as an assumption in the task Notes. Not re-reported.
- CR-4 (P3, card back under the href of a trashed contact): resolved by the CR-5 fix (the card is no longer ignored).
- CR-5 (P2, the CR-4 fix deleted the returning card at the server): resolved. In `store` (`CardDavContactsSync.kt:530-538`), a contact in the Trash whose link still has an ETag (trashed here while the card exists) stays untouched, so the next push deletes the card. A contact whose link has no ETag (its card was gone) is restored and gets the card's content. The restore matches `ContactTrashContributor.restore` (`contact.restore()` and `saveAndFlush`), and the `service.update` that follows re-indexes the contact and publishes the change. Restore and update share the `apply` transaction, so a card the validation refuses rolls back the restore as well. The link's `syncedVersion` is set to the updated version, so the next push sends nothing. The unit test `a card back under the href of a contact the sync trashed restores the contact with the card, and nothing is deleted` covers the restore, the link and a second run without PUT or DELETE. The remaining ambiguity (a contact trashed here whose push DELETE already succeeded) is recorded as an assumption.

Verified without new findings: sync-collection pull with token, invalid-token fallback and 507 paging; If-Match / If-None-Match writes and 412 handling; tombstones for purged contacts; same-origin enforcement and XXE hardening; logging without passwords or card data; HTTP outside transactions with one transaction per stored card; the migration; the `external` Radicale suite (9 tests, reported passing).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-6 | The test for the user-trashed branch never reaches that branch**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/contacts/carddav/CardDavContactsSyncUnitTests.kt:470-490`
    - Category: Tests
    - Problem: `a card that changes at the server while the user has its contact in the Trash is left for the push to delete` stubs the push's DELETE with HTTP 500. `CardDavClient.send` throws a `CardDavException` for every status of 500 or more (`CardDavClient.kt:123`), so `sync` stops in `push` and `pull` never runs. The guard the test is named after (`if (link.etag != null) return` at `CardDavContactsSync.kt:533`) is not executed. The assertions (`isDeleted` still true, no `service.update`, ETag `"e-old"`) would also pass if the guard were removed. So the branch that keeps a contact the user trashed from being restored by the sync has no test.
    - Why it matters: Restoring a contact the user deliberately trashed would be a regression no test catches. The test's name suggests otherwise.
    - Suggested fix: Make the DELETE fail without aborting the run, for example with 403 or 412 (refused, counted, run continues). Then assert that the sync-collection and multiget REPORTs were sent, the contact is still in the Trash, `service.update` was not called and the link keeps `"e-old"`. Optionally also assert `lastError` reports the one failed contact.

## Review conclusion

No material issues found. The acceptance criterion (sync-collection pull, If-Match / If-None-Match writes, `external` Radicale tests) is implemented and covered, and CR-1, CR-2, CR-4 and CR-5 are resolved. CR-3 is an accepted assumption. CR-6 is a small test gap that does not block the task.
