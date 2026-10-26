# Code Review

Date: 2026-10-26

Task: carddav-sync - CardDAV contacts sync

Scope: `git diff d6a1773e1..HEAD -- server` (committed changes only; `tasks/` ignored as instructed)

Requirements:
- tasks/carddav-sync.md acceptance criterion: "sync-collection + writes; `external` Radicale tests", plus the recorded assumptions in its Notes (no REST API/UI yet, one address book per account, server wins on conflicts, unconditional deletes, photos not synced, new migration and public VCardService.toChanges)
- SPEC.md §8.3 (contacts, source CARDDAV), §4 conventions, §12/§14 as referenced by the parent
- docs/ARCHITECTURE.md (module layout, Flyway naming, `@ExternalTest` / `integrationTest`, coverage)
- AGENTS.md project notes (logging, migrations, lint, shared files)
- Reference implementations: contacts/google/GoogleContactsSync.kt, calendar/infra/caldav/CalDavClient.kt

Git range:
- Branch: main
- HEAD: 37116288bec9c0325e725d5c2918e2c9caf54107
- Base/start: d6a1773e14b8e11dd032faac22eb397789050106
- Commits reviewed: 3 (f9a8d933e implementation, 638b468e6 Radicale tests, 37116288b tasks-only and outside the server diff)
- Uncommitted changes: none

Summary: 0 P0, 0 P1, 1 P2, 3 P3

Not run by the reviewer: Gradle (`./gradlew integrationTest --tests '*CardDavRadicaleSyncTests'`). The parent reports 9/9 passing; this review did not re-execute it and relies on code reading for behaviour.

Verified without findings: the sync logic (push first, then pull; If-Match / If-None-Match writes; 412 and stale-ETag handling let the server win; tombstones via `on delete set null`; adoption of restored contacts mirrors Google), the sync token handling (invalid token falls back to a full listing that does not trash this run's creations; 507 truncation paging), same-origin enforcement for hrefs and redirects (the Authorization header never leaves the account origin), XXE hardening of the multistatus parser (DOCTYPE disallowed, external entities off), logging (ids, hrefs and HTTP status only; no password, no card data; `lastError` holds no response body), password encryption (EncryptedSecret with the auto-applied converter), HTTP calls outside transactions with one transaction per stored card, and the migration (module folder, timestamp version, FK cascade and unique indexes matching the queries).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | CardDAV client, parser and mapping have no fast unit tests; several sync branches untested**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/contacts/carddav/CardDavRadicaleSyncTests.kt:45`
    - Category: Tests
    - Problem: The only tests for the new package are the `@ExternalTest` Radicale tests, which `check` / `.taskcheck` exclude. Nothing covers the security-relevant behaviour of `CardDavClient` (refusing a redirect or href to another origin, `resolve` returning null for a foreign host, 401/5xx mapping, retry only for safe or conditional methods: `CardDavClient.kt:54-156`), `MultiStatus.parse` (DOCTYPE/XXE rejection, per-response 404 status, 507 truncation marker, non-multistatus root: `CardDavClient.kt:235-298`), or `CardDavMapping.merged` (only differing groups replaced, N prefixes kept, `null` when nothing differs: `CardDavMapping.kt:41-76`). The Radicale suite also leaves these sync branches unexercised: adoption of a restored contact (`CardDavContactsSync.kt:512-514`), a 412 on the If-Match PUT (`:293-299`), the PROPFIND fallback for servers without sync-collection (`:439-448`), and discovery from an address-book URL directly (`:147-148`). The CalDAV client has `CalDavClientTests` as a unit-level precedent.
    - Why it matters: The same-origin and XXE protections guard the account password and the server process. A regression there would go unnoticed by `.taskcheck`, and the external suite only runs when someone triggers it.
    - Suggested fix: Add non-external unit tests for `CardDavClient` (a local stub or WireMock: cross-origin redirect refused, retry rules), `MultiStatus.parse` (DOCTYPE payload rejected, 404 and 507 entries), and `CardDavMapping.merged`. Add Radicale cases for adopting a restored contact (active contact with the card's external id and no link) and for a full listing through the PROPFIND path where practical.

## P3 Low

- [ ] **CR-2 | Redundant sync-collection REPORT on every run without a token**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/contacts/carddav/CardDavContactsSync.kt:374`
    - Category: Quality
    - Problem: `syncCollection(token) ?: syncCollection(null) ?: propfindListing()` calls `syncCollection(null)` twice when `token` is null. For a server without sync-collection the token is always null (`propfindListing` stores `token = null`), so every run sends two identical failing REPORTs before the PROPFIND. Behaviour is still correct.
    - Why it matters: It wastes one request per run on exactly the servers that already need a full listing each time, and it makes the fallback chain harder to read.
    - Suggested fix: Call `syncCollection(null)` as a second attempt only when `token != null`, e.g. `(token?.let(::syncCollection) ?: syncCollection(null)) ?: propfindListing()`.

- [ ] **CR-3 | Cards that fail to store are dropped from later runs, and the error is cleared on the next run**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/contacts/carddav/CardDavContactsSync.kt:379-381`
    - Category: Correctness
    - Problem: When `fetch` counts failures (a card the contact validation rejects, a missing `address-data`, or a transient database error in `apply`), `pull` still stores the new sync token. The next incremental run does not list the unchanged card again, so `skipped` is 0 and `sync` clears `lastError` (`:86`, `:93`). The card stays missing from the cockpit until it changes at the server or the token becomes invalid. `GoogleContactsSync` behaves the same way, so this keeps parity, but the user loses the only signal.
    - Why it matters: A silent, lasting difference between the address book and the cockpit, with no lasting error shown.
    - Suggested fix: When `failed > 0`, keep the previous token, or record the failed hrefs and retry them in the next run, so the failure stays visible until the card is stored. Otherwise record the current behaviour as an assumption.

- [ ] **CR-4 | A card re-created under the same href while its contact is in the Trash is ignored, and is lost after a purge**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/contacts/carddav/CardDavContactsSync.kt:529`
    - Category: Correctness
    - Problem: After a card is removed at the server, `removedAtServer` keeps the link with the same href and `etag = null` and trashes the contact (`:548-553`). If a card appears again under that href (undelete or re-import at the server), `store` finds the link, sees the contact in the Trash and returns without updating `link.etag`. If the user then purges the contact, `push` drops the link because `contact == null && etag == null` (`:216-219`). The next incremental pull does not list the unchanged card, so it is never imported. Google avoids this because it clears `resource_name` when the person is gone. Restoring the contact instead recovers correctly (CREATE, then 412, then the server wins).
    - Why it matters: A narrow case, but the card exists at the server and stays absent from the cockpit with no error.
    - Suggested fix: In `store`, when the link's contact is in the Trash and `link.etag` is null (the card had been gone), either record the new ETag so the next push's DELETE path handles it consistently, or release the link and import the card as a new contact, as the no-link branch does for a trashed contact.

## Review conclusion

No material issues found. The acceptance criterion's behaviour (sync-collection pull, If-Match / If-None-Match writes, `external` Radicale tests) is implemented according to the recorded assumptions. CR-1 is a test gap worth closing. CR-2 to CR-4 are minor and do not block the task.
