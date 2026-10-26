# Test Report

Date: 2026-10-26

Task: carddav-sync - CardDAV contacts sync

Scope: `git diff d6a1773e14b8e11dd032faac22eb397789050106..24808c2d671cb589ebde943ab9b25fe34c05f615 -- server` (task commits after the rebase: f5cf05ae6 implementation, 5324d9dc8 Radicale tests, 6a7b049de unit tests, b6de0272b sync unit tests by this test-writer, 24808c2d6 fixer changes for CR-2 and CR-4 with 3 tests; the other commits in the range only touch `tasks/`). Working tree clean at 24808c2d6 (tree fa1737687353f17cb84722dd8cb67646ca251104).

Requirements:
- tasks/carddav-sync.md acceptance criterion: "sync-collection + writes; `external` Radicale tests", and the assumptions in its Notes (account management through CardDavAccountService only, one address book per account found by discovery, server wins on conflicts, unconditional deletes, photos not synced)
- reviews/carddav-sync/review.md findings CR-1 (missing fast unit tests), CR-2 and CR-4 (fixed, see reviews/carddav-sync/fixes.md); CR-3 accepted as an assumption by the parent
- SPEC.md §8.3, docs/ARCHITECTURE.md "Coverage" (coverage informs, no threshold), AGENTS.md project notes (fictitious data, no secrets in logs or messages)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/carddav/CardDavAccountServiceTests.kt` (6a7b049de)
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/carddav/CardDavClientTests.kt` (6a7b049de)
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/carddav/CardDavMappingTests.kt` (6a7b049de)
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/carddav/MultiStatusTests.kt` (6a7b049de)
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/carddav/CardDavContactsSyncUnitTests.kt` (6a7b049de, b6de0272b, 24808c2d6)
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/carddav/CardDavRadicaleSyncTests.kt` (5324d9dc8, `@ExternalTest`)

## Tests added or updated

Count: 57 unit tests in the package (AccountService 5, Client 17, Mapping 10, MultiStatus 9, SyncUnit 16), plus 9 Radicale tests.

In CardDavContactsSyncUnitTests, 6 tests by this test-writer (b6de0272b):
- `a configured URL that is an address book is used as it is`
- `discovery follows the principal to the home set and takes the first address book of this origin by path`
- `a server that announces no address book home fails the run with a message`
- `a sync-collection answer truncated with 507 is continued with its token until complete`
- `an active contact restored from a backup with the card's id and no link is adopted, not duplicated`
- `a contact in the Trash with the card's id gives the id up, and the card gets a new contact`

3 tests added by the fixer (24808c2d6), reviewed here:
- `without a token a server that refuses sync-collection gets one REPORT, then PROPFIND` (CR-2 regression: before the fix, 2 REPORTs were sent; the test would fail)
- `a refused token is retried once without a token` (keeps the invalid-token fallback: one REPORT with the old token, one without, no PROPFIND)
- `a card back under the href of a contact trashed after its removal gets its ETag, and the next push deletes it` (CR-4 regression: before the fix the ETag stayed null and no DELETE followed; the test would fail)

Review result: the fixer's tests are deterministic, use fictitious data, and check observable effects: requests sent, the stored link ETag, and which contact-service calls were made. The CR-4 test checks the ETag on the in-memory link object. In production, the change is persisted because `store` updates a managed entity inside `tx.executeWithoutResult`, as the unchanged path below it already does, and the Radicale suite covers that path. No problem found, no test changed.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| sync-collection pull (token, incremental, invalid token falls back to full listing, 507 paging) | Radicale: first sync, later sync through the token, token no longer accepted; unit: 507 paging, refused token retried once, 5xx keeps the token, card that cannot be stored is counted | `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.contacts.carddav.*' jacocoTestReport`; `cd server && ./gradlew integrationTest --tests 'ch.sonensei.cockpit.contacts.carddav.*' --rerun` (both run by the parent on 24808c2d6) | Pass |
| Server without sync-collection: one REPORT, then PROPFIND; hrefs elsewhere ignored (CR-2) | `a server without sync-collection is listed with PROPFIND, and hrefs elsewhere are ignored`; `without a token a server that refuses sync-collection gets one REPORT, then PROPFIND` | unit command above | Pass |
| Writes with If-Match / If-None-Match, 412 lets the server win, deletes | Radicale: local change with If-Match, conflict keeps the server version, created and deleted contact, purged contact; unit: 412 on a write; CardDavClientTests: write headers, retry only for guarded writes | unit and integrationTest commands above | Pass |
| Card back under the href of a trashed contact is tracked and deleted by the next push (CR-4) | `a card back under the href of a contact trashed after its removal gets its ETag, and the next push deletes it` | unit command above | Pass |
| Address book discovery (address-book URL, principal and home set, none) | 3 unit discovery tests; Radicale: discovery from the server root | unit and integrationTest commands above | Pass |
| Adoption of a restored contact; a contact in the Trash gives up its id | 2 unit tests | unit command above | Pass |
| Same-origin protection, 401/5xx without secrets, retry rules (CR-1) | CardDavClientTests (17) | unit command above | Pass |
| Multistatus parsing, DOCTYPE/XXE refusal, per-response 404, root that is not a multistatus (CR-1) | MultiStatusTests (9) | unit command above | Pass |
| Mapping: only differing groups replaced, unknown properties and N prefixes kept, null when equal (CR-1) | CardDavMappingTests (10) | unit command above | Pass |
| Account add/remove validation (assumption: CardDavAccountService) | CardDavAccountServiceTests (5) | unit command above | Pass |
| `external` Radicale tests exist and pass | CardDavRadicaleSyncTests (9) | `cd server && ./gradlew integrationTest --tests 'ch.sonensei.cockpit.contacts.carddav.*' --rerun` (Radicale 3.5.4.0 via Testcontainers/Podman, run by the parent) | Pass (9 tests, 0 failures) |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.contacts.carddav.*' jacocoTestReport` (run by the parent on 24808c2d6)

Result:

Pass: ktlint clean; 57 tests, 0 failures (AccountService 5, Client 17, Mapping 10, MultiStatus 9, SyncUnit 16).

## Full test execution

Command:

`cd server && ./gradlew integrationTest --tests 'ch.sonensei.cockpit.contacts.carddav.*' --rerun` (run by the parent on 24808c2d6, Radicale 3.5.4.0 via Testcontainers/Podman)

Result:

Pass: 9 tests, 0 failures. The full server `check` is covered by `.taskcheck` (below).

## .taskcheck

Result:

Run by the parent (`sh scripts/check.sh`) after this report; the result is recorded in quality.json, not here.

## Bugs found

None found by testing. CR-2 and CR-4 come from the code review and are fixed in 24808c2d6, with regression tests (see above).

## Coverage

Status: verified (unit tests only; the Radicale suite is not instrumented by `jacocoTestReport`), measured by the parent on the final revision 24808c2d6.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: carddav-sync; base d6a1773e14b8e11dd032faac22eb397789050106; measured HEAD 24808c2d671cb589ebde943ab9b25fe34c05f615; ownership from the parent's commit list and the task's `touches` (carddav package).
- Source/test snapshot identifier (including uncommitted inputs): commit 24808c2d6, tree fa1737687353f17cb84722dd8cb67646ca251104, working tree clean.
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/contacts/carddav/{CardDavAccountService,CardDavClient,CardDavContactsSync,CardDavContactsSyncScheduler,CardDavMapping,CardDavState}.kt`. Excluded: `contacts/domain/VCardService.kt` (one-line visibility change, no behaviour), the SQL migration (not executable by JaCoCo), test resources.
- Provider/version, command, working directory, and test selection: JaCoCo (Gradle plugin, version not supplied) via `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.contacts.carddav.*' jacocoTestReport` in `server/`; unit tests of the package only.
- Run start/completion time and exit status: run by the parent on 2026-10-26, exit 0; exact times not supplied.
- Generated report paths and report hashes/run identifier: default `server/build/reports/jacoco/test/` (Git-ignored); hash not supplied.
- Evidence outputs are fresh and inputs stayed unchanged: the parent ran on 24808c2d6 after the last test and production change; no later code commits.
- Final run after latest test additions and production fixes: yes.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `contacts/carddav/CardDavClient.kt` (incl. MultiStatus) | N/A | 105/118 (89.0%) | N/A | 157/169 (92.9%) |
| `contacts/carddav/CardDavContactsSync.kt` | N/A | 153/259 (59.1%) | N/A | 240/304 (78.9%) |
| `contacts/carddav/CardDavMapping.kt` | N/A | 35/48 (72.9%) | N/A | 33/33 (100%) |
| `contacts/carddav/CardDavAccountService.kt` | N/A | 17/18 (94.4%) | N/A | 16/17 (94.1%) |
| `contacts/carddav/CardDavState.kt` | N/A | 0/0 (N/A) | N/A | 33/34 (97.1%) |
| `contacts/carddav/CardDavContactsSyncScheduler.kt` | N/A | 0/0 (N/A) | N/A | 7/10 (70.0%) |
| Aggregate (all 6 files, summed counters; none unmeasured) | N/A | 310/443 (70.0%) | N/A | 486/567 (85.7%) |

Statements and functions: N/A here because only JaCoCo LINE and BRANCH counters were supplied for this revision.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CardDavContactsSync.kt` push paths (create with If-None-Match, update and delete, tombstones), invalid-token full listing, `removeMissing` | If-Match / If-None-Match writes and deletes (criterion) | Covered by CardDavRadicaleSyncTests (`integrationTest`, 9 passed), which JaCoCo of `test` does not include; not duplicated as unit tests |
| `CardDavContactsSync.kt:254-259` 412 on the If-None-Match create, 404 after a write or re-read in `update` | Narrow conflict paths | Left uncovered: secondary error paths; the server-wins logic they call is covered through the update 412 test |
| `CardDavContactsSync.kt` CR-3 behaviour (token stored despite failed cards) | Accepted assumption | No test: behaviour accepted by the parent as an assumption, not a requirement |
| `CardDavMapping.kt` 13 branches | Null alternatives per field group | Left uncovered: every line runs; the remaining branches are Kotlin null-safety alternatives of field groups that are already tested |
| `CardDavClient.kt` 13 branches | Defensive header and URI handling | Left uncovered: the security-relevant branches (another host, redirects, retries) are tested |
| `CardDavContactsSyncScheduler.kt` 3 lines | Scheduled trigger | Failure path tested; the rest is Spring scheduling glue |

## Unable to test

- Gradle could not be run by this test-writer (sub-agent restriction); all executions above were run by the parent and are reported as such.
- Changed-line coverage and Radicale-suite coverage were not measured (not configured for `integrationTest`); no follow-up needed for the task.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. With 57 unit tests and 9 Radicale tests passing on 24808c2d6, the criterion and CR-1 are covered, and CR-2 and CR-4 have regression tests that would fail without the fix.
