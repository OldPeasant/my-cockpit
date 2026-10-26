# Test Report

Date: 2026-10-26

Task: carddav-sync - CardDAV contacts sync

Scope: `git diff d6a1773e14b8e11dd032faac22eb397789050106..f6b0cfde5f4c74cb3bf6685de924677c6783c5a2 -- server`. Task commits: f5cf05ae6 implementation, 5324d9dc8 Radicale tests, 6a7b049de unit tests, b6de0272b sync unit tests by this test-writer, 24808c2d6 fixer changes for CR-2 and CR-4, fbf43f537 fix for CR-5, and the CR-6 change, which only touches a test, in f6b0cfde5. The other commits in the range only touch `tasks/` or `reviews/`. Measured revision f6b0cfde5 (tree dd322037f68b1e2a7796a82a79ec1f70fa7cfda9). Production code is identical to fbf43f537: `git diff fbf43f537 f6b0cfde5 -- server` changes only CardDavContactsSyncUnitTests.kt. No uncommitted source or test changes; only `reviews/` files are modified.

Requirements:
- tasks/carddav-sync.md acceptance criterion: "sync-collection + writes; `external` Radicale tests", and the assumptions in its Notes (account management through CardDavAccountService only, one address book per account found by discovery, server wins on conflicts, deleting in the cockpit deletes the card unconditionally, photos not synced)
- reviews/carddav-sync/review.md findings CR-1 (missing fast unit tests), CR-2, CR-4, CR-5 and CR-6 (fixed, see reviews/carddav-sync/fixes.md); CR-3 accepted as an assumption by the parent
- SPEC.md §8.3, docs/ARCHITECTURE.md "Coverage" (coverage informs, no threshold), AGENTS.md project notes (fictitious data, no secrets in logs or messages)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/carddav/CardDavAccountServiceTests.kt` (6a7b049de)
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/carddav/CardDavClientTests.kt` (6a7b049de)
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/carddav/CardDavMappingTests.kt` (6a7b049de)
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/carddav/MultiStatusTests.kt` (6a7b049de)
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/carddav/CardDavContactsSyncUnitTests.kt` (6a7b049de, b6de0272b, 24808c2d6, fbf43f537, f6b0cfde5)
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/carddav/CardDavRadicaleSyncTests.kt` (5324d9dc8, `@ExternalTest`)

## Tests added or updated

Count: 58 unit tests in the package (AccountService 5, Client 17, Mapping 10, MultiStatus 9, SyncUnit 17), plus 9 Radicale tests.

In CardDavContactsSyncUnitTests, 6 tests by this test-writer (b6de0272b):
- `a configured URL that is an address book is used as it is`
- `discovery follows the principal to the home set and takes the first address book of this origin by path`
- `a server that announces no address book home fails the run with a message`
- `a sync-collection answer truncated with 507 is continued with its token until complete`
- `an active contact restored from a backup with the card's id and no link is adopted, not duplicated`
- `a contact in the Trash with the card's id gives the id up, and the card gets a new contact`

Tests by the fixer, reviewed here:
- `without a token a server that refuses sync-collection gets one REPORT, then PROPFIND` (24808c2d6, CR-2 regression: before the fix 2 REPORTs were sent, so the test would fail)
- `a refused token is retried once without a token` (24808c2d6: the fallback after an invalid token still works)
- `a card back under the href of a contact the sync trashed restores the contact with the card, and nothing is deleted` (fbf43f537; replaces the CR-4 test, which asserted the DELETE that CR-5 reported. CR-5 regression: with the CR-4 code the contact stayed in the Trash and the next push sent DELETE, so this test would fail)
- `a card that changes at the server while the user has its contact in the Trash is left for the push to delete` (fbf43f537, corrected for CR-6 in f6b0cfde5). It checks that the recorded "deleting in the cockpit deletes the card" rule still holds when the link has an ETag. In fbf43f537 the DELETE was answered with 500. `CardDavClient` throws on 500 and above, so the run stopped in the push and the guard was never reached. I missed this in my review of that revision. In f6b0cfde5 the DELETE is answered with 403, which is refused and counted, so the run continues. The test now asserts that the sync-collection and multiget REPORTs were sent, `lastError` counts exactly 1 failed contact, the token moves to tok-2, and the contact stays in the Trash. It also asserts no `contacts.saveAndFlush`, no `service.update` or `createSynced` call, and that the link keeps `"e-old"`. Without the `link.etag != null` guard the contact would be restored and saved, so the test would fail.

Review of the CR-5 change (`CardDavContactsSync.kt` `store`). A contact in the Trash whose link has an ETag returns unchanged, as before CR-4. With no ETag, the card was gone and is now back, so the contact is restored (`restore()` plus `saveAndFlush`) and the card is applied through `service.update`, which reindexes the contact and publishes the change. These are the same steps as `ContactItems.restore` uses when restoring from the Trash. The link then gets the card's ETag and the version written, so the next push has nothing to send. The second run of the first test confirms this: no PUT or DELETE. The same branch is also taken when a push already deleted the card (the user trashed the contact here) and the card is later re-created at the server. The code comment documents this case, and it is consistent with "server wins". The tests are deterministic, use fictitious data, and check observable effects. After CR-6 no problem remains; this test-writer changed no test in these revisions.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| sync-collection pull (token, incremental, invalid token falls back to full listing, 507 paging) | Radicale: first sync, later sync through the token, token no longer accepted; unit: 507 paging, refused token retried once, 5xx keeps the token, card that cannot be stored is counted | `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.contacts.carddav.*' jacocoTestReport`; `cd server && ./gradlew integrationTest --tests 'ch.sonensei.cockpit.contacts.carddav.*' --rerun` (unit run by the parent on f6b0cfde5; Radicale run by the parent on fbf43f537, with production code identical to f6b0cfde5) | Pass |
| Server without sync-collection: one REPORT, then PROPFIND; hrefs elsewhere ignored (CR-2) | `a server without sync-collection is listed with PROPFIND, and hrefs elsewhere are ignored`; `without a token a server that refuses sync-collection gets one REPORT, then PROPFIND` | unit command above | Pass |
| Writes with If-Match / If-None-Match, 412 lets the server win, deletes | Radicale: local change with If-Match, conflict keeps the server version, created and deleted contact, purged contact; unit: 412 on a write; CardDavClientTests: write headers, retry only for guarded writes | unit and integrationTest commands above | Pass |
| Card back under the href of a contact the sync trashed: contact restored with the card, nothing deleted (CR-4, CR-5) | `a card back under the href of a contact the sync trashed restores the contact with the card, and nothing is deleted` | unit command above | Pass |
| Contact the user trashed: a changed card is left for the push to delete (assumption "deleting in the cockpit deletes the card"; CR-6) | `a card that changes at the server while the user has its contact in the Trash is left for the push to delete` | unit command above (f6b0cfde5) | Pass |
| Address book discovery (address-book URL, principal and home set, none) | 3 unit discovery tests; Radicale: discovery from the server root | unit and integrationTest commands above | Pass |
| Adoption of a restored contact; a contact in the Trash gives up its id | 2 unit tests | unit command above | Pass |
| Same-origin protection, 401/5xx without secrets, retry rules (CR-1) | CardDavClientTests (17) | unit command above | Pass |
| Multistatus parsing, DOCTYPE/XXE refusal, per-response 404, root that is not a multistatus (CR-1) | MultiStatusTests (9) | unit command above | Pass |
| Mapping: only differing groups replaced, unknown properties and N prefixes kept, null when equal (CR-1) | CardDavMappingTests (10) | unit command above | Pass |
| Account add/remove validation (assumption: CardDavAccountService) | CardDavAccountServiceTests (5) | unit command above | Pass |
| `external` Radicale tests exist and pass | CardDavRadicaleSyncTests (9) | `cd server && ./gradlew integrationTest --tests 'ch.sonensei.cockpit.contacts.carddav.*' --rerun` (Radicale container via Testcontainers, run by the parent on fbf43f537; production code and the Radicale test are unchanged in f6b0cfde5) | Pass (9 tests, 0 failures) |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.contacts.carddav.*' jacocoTestReport` (run by the parent on f6b0cfde5)

Result:

Pass: ktlint clean; 58 tests, 0 failures (AccountService 5, Client 17, Mapping 10, MultiStatus 9, SyncUnit 17).

## Full test execution

Command:

`cd server && ./gradlew integrationTest --tests 'ch.sonensei.cockpit.contacts.carddav.*' --rerun` (run by the parent on fbf43f537, Radicale via Testcontainers; f6b0cfde5 changes only a unit test, so production code and the Radicale suite are identical)

Result:

Pass: 9 tests, 0 failures. The full server `check` is covered by `.taskcheck` (below).

## .taskcheck

Result:

Run by the parent (`sh scripts/check.sh`) after this report; the result is recorded in quality.json, not here.

## Bugs found

None found by testing. The code review raised CR-2, CR-4 and CR-5, all fixed by fbf43f537, each with a regression test. CR-6 was a test gap, fixed in f6b0cfde5 (see above).

## Coverage

Status: verified (unit tests only; the Radicale suite is not instrumented by `jacocoTestReport`), measured by the parent on the final revision f6b0cfde5.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: carddav-sync; base d6a1773e14b8e11dd032faac22eb397789050106; measured revision f6b0cfde5f4c74cb3bf6685de924677c6783c5a2; ownership from the parent's commit list and the task's `touches` (carddav package).
- Source/test snapshot identifier (including uncommitted inputs): commit f6b0cfde5, tree dd322037f68b1e2a7796a82a79ec1f70fa7cfda9, no uncommitted source or test changes.
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/contacts/carddav/{CardDavAccountService,CardDavClient,CardDavContactsSync,CardDavContactsSyncScheduler,CardDavMapping,CardDavState}.kt`. Excluded: `contacts/domain/VCardService.kt` (one-line visibility change, no behaviour), the SQL migration (not executable by JaCoCo), test resources.
- Provider/version, command, working directory, and test selection: JaCoCo (Gradle plugin, version not supplied) via `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.contacts.carddav.*' jacocoTestReport` in `server/`; unit tests of the package only.
- Run start/completion time and exit status: run by the parent on 2026-10-26, exit 0; exact times not supplied.
- Generated report paths and report hashes/run identifier: default `server/build/reports/jacoco/test/` (Git-ignored); hash not supplied.
- Evidence outputs are fresh and inputs stayed unchanged: the parent ran the unit tests and JaCoCo on f6b0cfde5, after the last change to production code (fbf43f537) and to tests (f6b0cfde5).
- Final run after latest test additions and production fixes: yes.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `contacts/carddav/CardDavClient.kt` (incl. MultiStatus) | N/A | 105/118 (89.0%) | N/A | 157/169 (92.9%) |
| `contacts/carddav/CardDavContactsSync.kt` | N/A | 157/259 (60.6%) | N/A | 237/305 (77.7%) |
| `contacts/carddav/CardDavMapping.kt` | N/A | 35/48 (72.9%) | N/A | 33/33 (100%) |
| `contacts/carddav/CardDavAccountService.kt` | N/A | 17/18 (94.4%) | N/A | 16/17 (94.1%) |
| `contacts/carddav/CardDavState.kt` | N/A | 0/0 (N/A) | N/A | 33/34 (97.1%) |
| `contacts/carddav/CardDavContactsSyncScheduler.kt` | N/A | 0/0 (N/A) | N/A | 7/10 (70.0%) |
| Aggregate (all 6 files, summed counters; none unmeasured) | N/A | 314/443 (70.9%) | N/A | 483/568 (85.0%) |

Statements and functions: N/A here because only JaCoCo LINE and BRANCH counters were supplied for this revision.

Compared with fbf43f537 (232/305 lines, 150/259 branches), the CR-6 test now runs the pull after the refused DELETE. That adds the guard for a contact the user trashed and the multiget and store path after a push failure. The successful push DELETE runs only in the Radicale suite, because the replaced CR-4 test no longer has a second run with a DELETE.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CardDavContactsSync.kt` push paths (create with If-None-Match, update and delete, tombstones), invalid-token full listing, `removeMissing` | If-Match / If-None-Match writes and deletes (criterion) | Covered by CardDavRadicaleSyncTests (`integrationTest`, 9 passed), which JaCoCo of `test` does not include; not duplicated as unit tests |
| `CardDavContactsSync.kt` 412 on the If-None-Match create, 404 after a write or re-read in `update` | Narrow conflict paths | Left uncovered: secondary error paths; the server-wins logic they call is covered through the update 412 test |
| `CardDavContactsSync.kt` CR-3 behaviour (token stored despite failed cards) | Accepted assumption | No test: behaviour accepted by the parent as an assumption, not a requirement |
| `CardDavMapping.kt` 13 branches | Null alternatives per field group | Left uncovered: every line runs; the remaining branches are Kotlin null-safety alternatives of field groups that are already tested |
| `CardDavClient.kt` 13 branches | Defensive header and URI handling | Left uncovered: the security-relevant branches (another host, redirects, retries) are tested |
| `CardDavContactsSyncScheduler.kt` 3 lines | Scheduled trigger | Failure path tested; the rest is Spring scheduling glue |

## Unable to test

- Gradle could not be run by this test-writer (sub-agent restriction); all executions above were run by the parent and are reported as such.
- Changed-line coverage and Radicale-suite coverage were not measured (not configured for `integrationTest`); no follow-up needed for the task.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. 58 unit tests pass on f6b0cfde5, and 9 Radicale tests pass on fbf43f537, whose production code is identical. The criterion and CR-1 are covered. CR-2 and CR-5 (which supersedes the CR-4 behaviour) have regression tests that would fail without the fix. Since CR-6 the user-Trash guard is tested.
