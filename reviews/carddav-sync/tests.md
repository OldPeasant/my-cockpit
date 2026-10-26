# Test Report

Date: 2026-10-26

Task: carddav-sync - CardDAV contacts sync

Scope: `git diff d6a1773e14b8e11dd032faac22eb397789050106..464ec5e6d50bf2764884dc712cf3e06627ba6004 -- server` (task commits 01c0ada45 implementation, dd7a7bde4 Radicale tests, 464ec5e6d unit tests; cda655832 and the heartbeat commits touch only `tasks/`), plus one uncommitted test-file change by this session (see "Test files changed").

Requirements:
- tasks/carddav-sync.md acceptance criterion: "sync-collection + writes; `external` Radicale tests", and the assumptions in its Notes (account management through CardDavAccountService only, one address book per account found by discovery, server wins on conflicts, unconditional deletes, photos not synced)
- reviews/carddav-sync/review.md finding CR-1 (missing fast unit tests for client, parser, mapping and several sync branches)
- SPEC.md §8.3, docs/ARCHITECTURE.md "Coverage" (coverage informs, no threshold), AGENTS.md project notes (fictitious data, no secrets in logs or messages)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/carddav/CardDavContactsSyncUnitTests.kt` (uncommitted, this session; sha256 a9aad53cba4f8965b4119c21c4e57b297267d3200e775d929cf663a5d1375292)

Already committed in 464ec5e6d by the earlier test-writer session and reviewed here (unchanged):
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/carddav/CardDavAccountServiceTests.kt` (5 tests)
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/carddav/CardDavClientTests.kt` (17 tests)
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/carddav/CardDavContactsSyncUnitTests.kt` (7 tests before this session)
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/carddav/CardDavMappingTests.kt` (10 tests)
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/carddav/MultiStatusTests.kt` (9 tests)

## Tests added or updated

Count: 6 added (all in CardDavContactsSyncUnitTests; the class KDoc was extended, no existing test changed)

- `a configured URL that is an address book is used as it is` (CR-1: discovery from an address-book URL, `CardDavContactsSync.kt:147-148`)
- `discovery follows the principal to the home set and takes the first address book of this origin by path` (principal, home set, Depth 0/0/1, foreign-origin address book ignored, first by path)
- `a server that announces no address book home fails the run with a message` (discovery error path)
- `a sync-collection answer truncated with 507 is continued with its token until complete` (507 paging, `:417-433`, not reachable with Radicale)
- `an active contact restored from a backup with the card's id and no link is adopted, not duplicated` (CR-1: adoption, `:512-514`)
- `a contact in the Trash with the card's id gives the id up, and the card gets a new contact` (`:516-519`)

These 6 tests were written without running Gradle (sub-agent restriction) and have NOT been executed yet. The parent must run them (command below) before relying on them.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| sync-collection pull (token, incremental, invalid token falls back to full listing) | CardDavRadicaleSyncTests: first sync, later sync through the token, token no longer accepted; CardDavContactsSyncUnitTests: 5xx keeps the token, card that cannot be stored is counted | parent: `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.carddav.*' jacocoTestReport` on 464ec5e6d; Radicale: `./gradlew integrationTest` | Unit: Pass (parent, 48/48). Radicale: run by the parent, result not reported to this session |
| sync-collection 507 truncation paging | `a sync-collection answer truncated with 507 is continued with its token until complete` | to be run by the parent (see "Focused test execution") | Not run: added after the parent's run, sub-agent cannot run Gradle |
| Server without sync-collection (PROPFIND listing), hrefs elsewhere ignored | `a server without sync-collection is listed with PROPFIND, and hrefs elsewhere are ignored` | parent unit run on 464ec5e6d | Pass |
| Writes with If-Match / If-None-Match, 412 lets the server win | CardDavRadicaleSyncTests: local change with If-Match, conflict keeps server version, created and deleted contact, purged contact; unit: `a write the server refuses with 412 ...`; CardDavClientTests: write headers, retry only for guarded writes | parent unit run; Radicale run by the parent | Unit: Pass. Radicale: run by the parent |
| Address book discovery (address-book URL, principal, home set, none) | 3 new discovery tests; Radicale: discovery from the server root | to be run by the parent | Not run (new tests); Radicale: run by the parent |
| Adoption of a restored contact / Trash contact gives up its id | 2 new tests | to be run by the parent | Not run: new tests |
| Same-origin protection, 401/5xx mapping without secrets, retry rules (CR-1) | CardDavClientTests (17) | parent unit run on 464ec5e6d | Pass |
| Multistatus parsing, XXE/DOCTYPE refusal, 404 per response, non-multistatus root (CR-1) | MultiStatusTests (9) | parent unit run on 464ec5e6d | Pass |
| Mapping: only differing groups replaced, unknown properties and N prefixes kept, null when equal (CR-1) | CardDavMappingTests (10) | parent unit run on 464ec5e6d | Pass |
| Account add/remove validation (assumption: CardDavAccountService) | CardDavAccountServiceTests (5) | parent unit run on 464ec5e6d | Pass |
| `external` Radicale tests exist and pass | CardDavRadicaleSyncTests (9) | parent: `cd server && ./gradlew integrationTest --tests '*CardDavRadicaleSyncTests'` | Not run by this session: run by the parent |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.carddav.*' jacocoTestReport` (run by the parent on HEAD 464ec5e6d, before this session's additions)

Result:

Pass: 48 tests, 0 failures (AccountService 5, Client 17, SyncUnit 7, MultiStatus 9, Mapping 10).

Still required (parent): `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.carddav.CardDavContactsSyncUnitTests'` (expected 13 tests) and `cd server && ./gradlew ktlintCheck` for the changed file. Not run by this session.

## Full test execution

Command:

`sh scripts/check.sh` / `cd server && ./gradlew check`

Result:

Not run: sub-agents cannot run Gradle here; the parent runs it.

## .taskcheck

Result:

Not run (sub-agent cannot run Gradle); the parent runs `.taskcheck` before `done`.

## Bugs found

None found by testing. The new tests encode the documented behaviour of `CardDavContactsSync` (KDoc and code comments) and the task assumptions; if one fails when the parent runs it, first check the test (it was not executed), then the code.

## Coverage

Status: partial - measured by the parent on 464ec5e6d (unit tests only, before the 6 tests of this session); stale for the final state; a fresh run is required.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: carddav-sync; base d6a1773e14b8e11dd032faac22eb397789050106; measured HEAD 464ec5e6d50bf2764884dc712cf3e06627ba6004; ownership from the parent's commit list (01c0ada45, dd7a7bde4, 464ec5e6d) and the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): the measurement covers 464ec5e6d with a clean tree; the current snapshot adds the uncommitted CardDavContactsSyncUnitTests.kt (sha256 above), which is NOT included in the numbers.
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/contacts/carddav/{CardDavAccountService,CardDavClient,CardDavContactsSync,CardDavContactsSyncScheduler,CardDavMapping,CardDavState}.kt`. Excluded: `contacts/domain/VCardService.kt` (one-line visibility change, no behaviour), the SQL migration (not executable by JaCoCo), test resources.
- Provider/version, command, working directory, and test selection: JaCoCo via `./gradlew test --tests 'ch.sonensei.cockpit.contacts.carddav.*' jacocoTestReport` in `server/`, unit tests only (the `@ExternalTest` Radicale suite is excluded from `test`). Provider version and report path/hash not supplied to this session.
- Run start/completion time and exit status: run by the parent, exit 0 (48 passed); times not supplied.
- Generated report paths and report hashes/run identifier: not supplied (default `server/build/reports/jacoco/test/`).
- Evidence outputs are fresh and inputs stayed unchanged: as reported by the parent for 464ec5e6d.
- Final run after latest test additions and production fixes: no - the 6 tests added in this session are not measured.

Per-file values as reported by the parent (percentages only; per-file covered/total counters were not supplied):

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `contacts/carddav/CardDavClient.kt` (incl. MultiStatus) | N/A | 88.1% | N/A | 92.9% |
| `contacts/carddav/CardDavContactsSync.kt` | N/A | 36.9% | N/A | 64.2% |
| `contacts/carddav/CardDavMapping.kt` | N/A | 72.9% | N/A | 100% |
| `contacts/carddav/CardDavAccountService.kt` | N/A | not supplied | N/A | 94.1% |
| `contacts/carddav/CardDavState.kt` | N/A | not supplied | N/A | 97.1% |
| `contacts/carddav/CardDavContactsSyncScheduler.kt` | N/A | not supplied | N/A | 70% |
| Aggregate (package contacts.carddav, summed counters) | N/A | 250/439 (56.9%) | N/A | 440/565 (77.9%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CardDavContactsSync.kt:142-169` discovery | Finding the address book (criterion depends on it) | 3 discovery tests added |
| `CardDavContactsSync.kt:417-433` 507 truncation | Complete incremental pull on large changes | 507 paging test added |
| `CardDavContactsSync.kt:505-521` adoption / Trash id release | Restore from backup without duplicates (CR-1) | 2 tests added |
| `CardDavContactsSync.kt` create/update/delete push paths, invalid-token fallback, removeMissing | If-Match / If-None-Match writes, deletes, tombstones | Covered by CardDavRadicaleSyncTests (`integrationTest`, run by the parent); JaCoCo of `test` does not include it. Not duplicated as unit tests |
| `CardDavContactsSync.kt:254-259` 412 on If-None-Match create, `:292`/`:297` 404 after a write | Narrow conflict paths | Left uncovered: secondary error paths, server-wins logic already covered via the update 412 test |
| `CardDavContactsSync.kt:526-529` store on a link whose contact is in the Trash | CR-4 (P3) behaviour | Left uncovered: CR-4 is an open review finding about this very path; a test would freeze disputed behaviour |
| `CardDavMapping.kt` remaining branches (72.9%) | Null-handling per field group | Left uncovered: line coverage 100%, remaining branches are Kotlin null-safety alternatives of already tested groups |
| `CardDavContactsSyncScheduler.kt` (70%) | Scheduled trigger | Failure path tested; the rest is Spring scheduling glue |

## Unable to test

- Gradle execution of the 6 new tests, ktlint on the changed file, the full `check` and `.taskcheck`: sub-agents are denied Gradle here; the parent must run them.
- The Radicale suite (`integrationTest`): needs a container runtime; run by the parent, result not reported to this session.
- Fresh coverage including the new tests: requires a rerun of the parent's JaCoCo command.

## Summary

Tests added; they still need to be run by the parent. The existing unit tests from 464ec5e6d already cover most of CR-1: same-origin protection, retry rules, 401/5xx messages without secrets, XXE/DOCTYPE refusal, multistatus parsing, the selective mapping merge, the PROPFIND fallback and the 412 server-wins path. This session added 6 tests for the remaining CR-1 branches: discovery from an address-book URL and through principal and home set, 507 truncation paging, adoption of a restored contact, and a contact in the Trash giving up its id. No production defects were found. Coverage numbers are from before these additions and are stale.
