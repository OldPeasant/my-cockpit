# Code Review

Date: 2026-10-26

Task: carddav-sync - CardDAV contacts sync

Scope: `git diff d6a1773e1..f6b0cfde5 -- server` (committed changes only; `tasks/` and `reviews/` ignored as instructed). Focus on the CR-6 change (`git diff fbf43f537..f6b0cfde5 -- server`: CardDavContactsSyncUnitTests.kt only, 9 lines added and 2 removed; production code unchanged since fbf43f537, which the previous review covered in full).

Requirements:
- tasks/carddav-sync.md acceptance criterion: "sync-collection + writes; `external` Radicale tests", plus the recorded assumptions in its Notes (no REST API/UI yet, one address book per account, server wins on conflicts, unconditional deletes from the cockpit, photos not synced, new migration and public VCardService.toChanges, CR-3 accepted, the CR-5 edge case of a contact trashed here whose card was already deleted at the server)
- SPEC.md §8.3 (contacts, source CARDDAV), §4 conventions, §14
- docs/ARCHITECTURE.md (module layout, Flyway naming, `@ExternalTest` / `integrationTest`, coverage)
- AGENTS.md project notes (logging, migrations, lint)
- Previous reviews of this task (CR-1 to CR-6) and reviews/carddav-sync/fixes.md

Git range:
- Branch: main
- HEAD: f6b0cfde5f4c74cb3bf6685de924677c6783c5a2
- Base/start: d6a1773e14b8e11dd032faac22eb397789050106
- Commits reviewed: 7 touching `server/` (f5cf05ae6 implementation, 5324d9dc8 Radicale tests, 6a7b049de and b6de0272b unit tests, 24808c2d6 fixes for CR-2 and CR-4, fbf43f537 fix for CR-5, f6b0cfde5 test fix for CR-6); the other commits in the range touch only `tasks/` or `reviews/`
- Uncommitted changes: none

Not run by the reviewer: Gradle. The parent reports on f6b0cfde5: ktlint clean; carddav unit tests 58, 0 failures; JaCoCo for the carddav package 85.0 % lines (483/568) and 70.9 % branches (314/443), CardDavContactsSync 77.7 % lines and 60.6 % branches. The Radicale `integrationTest` suite passed 9 of 9 on fbf43f537, and the production code is unchanged since. This review relies on those results and on reading the code.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Status of the previous findings

- CR-1 (P2, missing unit tests): resolved in earlier revisions (client, parser, mapping, account service and sync branches unit-tested).
- CR-2 (P3, redundant REPORT): resolved (`CardDavContactsSync.kt:376`), tested.
- CR-3 (P3, failed cards dropped from later runs): accepted as an assumption in the task Notes. Not re-reported.
- CR-4 (P3, card back under the href of a trashed contact): resolved by the CR-5 fix.
- CR-5 (P2, the CR-4 fix deleted the returning card at the server): resolved in fbf43f537. A contact the sync trashed is restored with the returning card, and a contact the user trashed is left for the push to delete. The production code is unchanged in this revision.
- CR-6 (P3, the user-trashed test never reached its branch): resolved. The push's DELETE now answers 403. For that status the client returns the response instead of throwing, `delete` counts the refused write, and the run continues to the pull. The listed ETag `"e-ada.vcf"` differs from the stored `"e-old"`, so the card is fetched by multiget and `store` reaches the guard at `CardDavContactsSync.kt:533` for a trashed contact whose link still has an ETag. The new assertions prove that the pull ran (one sync-collection and one multiget REPORT, token `tok-2`), that only the refused DELETE counted (`lastError` reports 1 contact), and that the contact was neither restored (`contacts.saveAndFlush` never called, still in the Trash) nor updated, with the link keeping `"e-old"`. Without the guard, `store` would call `contacts.saveAndFlush`, so the test would fail. It now protects the branch it is named after.

Verified without new findings: sync-collection pull with token, invalid-token fallback and 507 paging; If-Match / If-None-Match writes and 412 handling; tombstones for purged contacts; restoring a contact the sync trashed; same-origin enforcement and XXE hardening; logging without passwords or card data; HTTP outside transactions with one transaction per stored card; the migration; the `external` Radicale suite.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. The acceptance criterion (sync-collection pull, If-Match / If-None-Match writes, `external` Radicale tests) is implemented and covered. CR-1, CR-2, CR-4, CR-5 and CR-6 are resolved, and CR-3 is an accepted assumption.
