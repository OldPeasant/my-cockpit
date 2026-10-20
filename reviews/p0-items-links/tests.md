# Test Report

Date: 2026-10-20

Task: p0-items-links - Universal item references and links

Scope: `git diff 75a353c2fa68f759d93e8ab9de723f9a4e23974e..88a23f3164e6233bff4701becb17aac1b289fa03`, the final code revision. The parent supplied the base 75a353c (the claim commit) and the task commits `git log 75a353c..HEAD`: the implementation and earlier fix rounds from `origin/wip/p0-items-links` (aecd6fe, a5e0b77, 96d566e, 13ced92, 40eb79e, 1176100, 52c919b, 14f521d), merged in 6f15066, and 88a23f3, which regenerates `api/openapi.yaml` and the web client after merging main. The parent restricted the scope to `server/src/main/kotlin/ch/sonensei/cockpit/core/items`, `server/src/test/kotlin/ch/sonensei/cockpit/core/items` and `server/src/main/resources/db/migration/core/V202610201400__core_items_link.sql`. The production files in scope are unchanged since the previous report. This round re-checks the final code, including the LinkWebTests additions of 14f521d that the previous report did not cover.

Requirements:
- `tasks/p0-items-links.md` acceptance criteria 1-5 and its ASSUMPTION notes. The 14:10 notes replace the "pair linked at most once" note: MANUAL and SYNC links are independent; DELETE of a SYNC link returns 409; the response carries `origin`.
- SPEC.md §4.3
- docs/ARCHITECTURE.md "Coverage"

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/items/LinkWebTests.kt` (1 test added)

## Tests added or updated

Count: 1 added. The `core.items` package now has 65 tests: `ItemResolverRegistryTests` 5, `LinkServiceEdgeCaseTests` 17, `LinkServiceRaceTests` 1, `LinkServiceTests` 19, `LinkWebTests` 23.

Added:
- `LinkWebTests` / `GET marks the ends of a failing resolver unavailable and still answers 200`. AC2 says an end that cannot be resolved is "marked unavailable, not an error". At the HTTP level, this was tested only for a removed item and for a type without a resolver. A resolver that throws was tested only at the service level (`a failing resolver marks its ends unavailable`). The new test checks that GET returns 200, with the failing end returned as `available: false` with its type and id and no title, and the other end still resolved.

I re-read all existing tests against the criteria and the final code, including the 14f521d additions (`POST accepts an end whose type has no resolver yet...`, `POST rejects a source its resolver does not know`, `POST stores a blank label as none...`, `POST of malformed JSON...`, `GET without a type...`, `DELETE of a malformed or unknown id...`, `DELETE needs the CSRF token`, `creating and deleting links need a login`, `DELETE of an inline link is a conflict and keeps it`, `DELETE of a manual link next to an inline link...`). They are correct and not redundant, so I kept them unchanged. I found no other missing tests.

## Requirement coverage

Command F (focused, with coverage): `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.items.*' --rerun jacocoTestReport`. Postgres comes from Testcontainers (`PostgresTestBase`).
Command S (full server): `cd server && ./gradlew check` (ktlint and all server tests, including `OpenApiContractTest`).
Command T: `sh scripts/check.sh` (`.taskcheck`).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 `ItemType` has every type of SPEC §4.3 | `ItemType has every type of SPEC section 4_3` | F, S | Pass |
| AC1 `ItemResolver` (title, subtitle, icon, deepLink, exists) and a registry by type | `finds the resolver of a type`, `a type without a resolver gives null`, `two resolvers for the same type fail at startup`, `exists follows resolve` | F, S | Pass |
| AC2 `link` table and its constraints (one manual link per pair in either direction, one sync link per source and target, sync links allowed next to a manual one, no self link, known origin) | `the database rejects a reversed manual duplicate`, `the database rejects a duplicate sync link in the same direction only`, `the database allows sync links in both directions next to a manual link`, `the database rejects a self link`, `the database rejects an unknown origin` | F, S | Pass |
| AC2 `POST /api/v1/links`: 201 with Location; 400 for a self link, unknown source or target, missing fields, malformed JSON or label > 200; blank label stored as none; type without a resolver accepted; 409 for an existing manual pair in either direction; inline links do not block it; CSRF and login required | `POST creates a link and returns it resolved with its location`, `POST rejects self links, unknown items and missing ends`, `POST rejects a source its resolver does not know`, `POST accepts an end whose type has no resolver yet and returns it unavailable`, `POST stores a blank label as none and accepts a label of the maximum length`, `POST of malformed JSON or without a body is a bad request`, `POST of an already linked pair is a conflict`, `POST links a pair that only has inline links in both directions`, `state-changing requests need the CSRF token`, `creating and deleting links need a login`, `a pair linked concurrently between check and insert is a conflict`, the `LinkServiceTests` create tests | F, S | Pass |
| AC2 `DELETE /api/v1/links/{id}`: 204; 404; 400 for a malformed id; 409 for a SYNC link, which is kept; CSRF and login required | `DELETE removes the link from both ends`, `DELETE of a malformed or unknown id is a problem`, `DELETE of an inline link is a conflict and keeps it`, `DELETE of a manual link next to an inline link of the same pair removes only the manual one`, `DELETE needs the CSRF token`, `creating and deleting links need a login`, `delete rejects a sync link and keeps it` | F, S | Pass |
| AC2 `GET /api/v1/links?type=&id=` lists both directions with each end resolved; a missing resolver, missing item or failing resolver gives `unavailable`, not an error; each linked item appears once (the manual link if there is one, else the oldest) | `GET lists links in both directions from the queried item's perspective`, `GET marks a deleted item unavailable instead of failing`, **`GET marks the ends of a failing resolver unavailable and still answers 200` (new)**, `GET of an item without links is an empty list`, `GET needs a valid type and id`, `GET without a type is a bad request`, `links need a login`, `GET lists a pair with a manual and inline links once, as the manual link from each side`, `an end without a resolver or whose item is gone is unavailable, not an error`, `a failing resolver marks its ends unavailable`, `linksOf returns links in both directions, oldest first`, `linksOf orders by the shown link, oldest first`, `a pair with sync links in both directions and no manual link is listed once, as the oldest link`, `linksOf distinguishes items of different types with the same id`, `linksOf resolves each distinct end once` | F, S | Pass |
| AC3 `LinkService.sync(source, targets)` replaces the links a source owns and leaves manual links and other sources' links alone | `sync replaces the links a source owns and keeps manual ones`, `sync does not touch links other sources own`, `sync adds its own link next to a manual link in the other direction, listed once`, `sync links targets that are missing or have no resolver and lists them as unavailable`, `sync with unchanged targets keeps the existing links`, `sync of a source without a resolver works`, `a pair both ends sync stays linked while one end still wants it` (TW-1 regression) | F, S | Pass |
| AC3 deleting an item removes its links via an event (permanent delete, in the publisher's transaction) | `ItemDeletedEvent removes the item's links in both directions in the publisher's transaction`, `ItemDeletedEvent removes manual and sync links of the item in both directions`, `a rolled back delete keeps the links`, `ItemDeletedEvent of an item without links leaves other links alone`, `ItemDeletedEvent matches type and id, not only the id`, `ItemDeletedEvent keeps the entities the publisher loaded managed`, `links of an item in the Trash stay and become available again on restore` | F, S | Pass |
| AC4 tests use a fake resolver; `./gradlew test --tests 'ch.sonensei.cockpit.core.items.*'` passes (Postgres via Testcontainers) | all 65 tests of the package (`FakeItemResolvers`) | F | Pass: 65/65 |
| AC5 `OpenApiContractTest` passes without the flag | `OpenApiContractTest` | S | Pass: 1/1. The test-writer did not run the regeneration with `UPDATE_OPENAPI=1` (it rewrites a committed file). The parent regenerated it in 88a23f3. |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.items.*' --rerun jacocoTestReport`

Result:

Pass. I ran it twice: before adding the test (14:53:06 to 14:53:31 +02:00, 64/64) and after adding it (14:54:17 to 14:54:37 +02:00, 65/65: `ItemResolverRegistryTests` 5, `LinkServiceEdgeCaseTests` 17, `LinkServiceRaceTests` 1, `LinkServiceTests` 19, `LinkWebTests` 23). There were 0 failures, 0 errors and 0 skipped tests in both runs. `./gradlew ktlintCheck` also passed after the edit.

## Full test execution

Command:

`cd server && ./gradlew check`

Result:

Pass. The run went from 14:54:57 to 14:56:43 +02:00. ktlint and 870 server tests passed, with 0 failures, 0 errors and 0 skipped (summed from `server/build/test-results/test/*.xml`). This includes `OpenApiContractTest` (1/1).

## .taskcheck

Result:

Pass. `sh scripts/check.sh` exited 0 at 14:57:19 +02:00. It ran these steps: tfp completion gate tests, server ktlint and tests, web eslint, tests and build, android ktlint, unit tests and lint, and "== all checks passed".

## Bugs found

None. The earlier findings (TW-1, TW-2) remain resolved: `a pair both ends sync stays linked while one end still wants it` passes, and the full suite ran without running out of connections.

## Coverage

Status: verified. The run was fresh, complete and exited 0.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: equal to whole-file line coverage. `git diff --name-status 75a353c..HEAD -- server/src/main` shows every scoped file as `A` (added by the task), so every executable line is a changed line.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: p0-items-links. The base is 75a353c2fa68f759d93e8ab9de723f9a4e23974e and the measured HEAD is 88a23f3164e6233bff4701becb17aac1b289fa03. The parent supplied the task commits (`git log 75a353c..HEAD`) and the path scope, and I checked them against the task's `touches` and the migration ASSUMPTION note.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 88a23f3. Production tree `core/items` d7e81d65b634aa4dbe6afdb84ee87da749f9856b is unchanged in the working tree. Test files (sha256): `LinkWebTests.kt` 5d9bd4b305332aa2c5e2bffde1b9bc87166dde347266167d2c1bc6e9bd1a914f is the only uncommitted change. The rest are as committed: `LinkServiceTests.kt` bb4e9fd0e2a1…, `LinkServiceEdgeCaseTests.kt` 5369a62cc3ba…, `LinkServiceRaceTests.kt` 233a1e5b069c…, `FakeItemResolvers.kt` 69847c63b288…, `ItemResolverRegistryTests.kt` 4a5ccf647baf…. I hashed `LinkWebTests.kt` before the run and it had the same hash afterwards.
- Exact included files; renamed/deleted/excluded files and reasons: Included are `server/src/main/kotlin/ch/sonensei/cockpit/core/items/api/LinkController.kt`, `core/items/domain/{ItemResolver,ItemType,Link,LinkService}.kt` and `core/items/infra/LinkRepository.kt`. Excluded:
  - `V202610201400__core_items_link.sql`: SQL that JaCoCo cannot instrument. The database constraint tests in the requirement table exercise it.
  - `api/openapi.yaml` and `web/src/app/api/generated/**`: generated files outside the supplied scope, checked by `OpenApiContractTest`.
  Nothing was renamed or deleted, and no scoped file is missing from the report.
- Provider/version, command, working directory, and test selection: JaCoCo 0.8.15.202606040825, via the Gradle `jacoco` plugin (version read from the HTML report). Command: `./gradlew test --tests 'ch.sonensei.cockpit.core.items.*' --rerun jacocoTestReport`, run in `server/`. Test selection: the `core.items` package (65 tests).
- Run start/completion time and exit status: 2026-10-20T14:54:17+02:00 to 14:54:37+02:00, BUILD SUCCESSFUL, exit 0.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`, written 14:54:37.9, sha256 30f3a08e675d68589c5c52b17719b5c246fd66ad4cf7aad444a598a1282053c4. The later `./gradlew check` overwrote `build/jacoco/test.exec`, so the XML hash is the run identifier.
- Evidence outputs are fresh and inputs stayed unchanged: `--rerun` forced the test task to run again. The test-result XML timestamps (12:54:20Z to 12:54:34Z) and the report time fall inside the run. The input hashes were the same before and after the run.
- Final run after latest test additions and production fixes: yes. The run covers the new test, and production code has not changed since.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. "Statements" are JaCoCo INSTRUCTION counters and "Functions" are METHOD counters.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/.../core/items/api/LinkController.kt` | 334/391 (85.4%) | 11/12 (91.7%) | 33/36 (91.7%) | 79/82 (96.3%) |
| `server/.../core/items/domain/ItemResolver.kt` | 104/104 (100%) | 6/6 (100%) | 9/9 (100%) | 13/13 (100%) |
| `server/.../core/items/domain/ItemType.kt` | 100/100 (100%) | N/A (0/0) | 5/5 (100%) | 15/15 (100%) |
| `server/.../core/items/domain/Link.kt` | 145/145 (100%) | 7/8 (87.5%) | 16/16 (100%) | 25/25 (100%) |
| `server/.../core/items/domain/LinkService.kt` | 512/512 (100%) | 44/44 (100%) | 19/19 (100%) | 71/71 (100%) |
| `server/.../core/items/infra/LinkRepository.kt` | N/A (interface, no counters) | N/A | N/A | N/A |
| **Aggregate** (summed counters over the 6 files in scope, none missing; the migration is excluded as above) | 1195/1252 (95.4%) | 68/70 (97.1%) | 82/85 (96.5%) | 203/206 (98.5%) |

`LinkRepository` is a Spring Data interface with nothing to instrument. Its three queries (`findTouching`, `findBySourceTypeAndSourceIdAndOrigin`, `deleteTouching`) are exercised against Postgres by the `LinkServiceTests` and `LinkServiceEdgeCaseTests` tests.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `LinkController.kt:134,165,175-183,192,202` | Members that Kotlin generates for the DTOs: the synthetic default-argument constructors of `LinkEndResponse` (165) and `LinkResponse` (183), the unused `ItemRefDto.getType` getter (134), and default-argument parts of nullable properties | Non-material: generated code or code never called. The controller always passes every argument. |
| `LinkController.kt:217` (1 of 2 branches) | The failure path of `checkNotNull(link.createdAt)` | Non-material defensive check: a persisted link always has `createdAt`. |
| `Link.kt:55` (1 of 8 branches) | One short-circuit combination inside `connects` | Non-material: both directions and the non-matching case are covered through `hasManualLink` (`a pair is linked at most once, in either direction`, `a manual link next to an existing sync link is allowed...`). |

## Unable to test

- I did not run the AC5 regeneration (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) because it rewrites the committed file. The contract test passes without the flag, which shows that the committed `api/openapi.yaml` matches the generated contract.
- I did not run a real multi-threaded POST of the same pair. `LinkServiceRaceTests` covers the race deterministically with a mocked repository, and `the database rejects a reversed manual duplicate` covers the database side. Concurrent `sync` calls for the same source are not handled specially: a duplicate SYNC insert would surface as a `link_sync_pair` violation. No requirement covers this and no caller exists yet, so it is left for the inline-note-links task. No follow-up is needed now.
- Observations on unspecified behavior, not asserted and not filed as bugs, for the parent to decide:
  - When a resolver's `exists` throws during `POST`, the exception propagates and the response is 500. The resolver-failure assumption covers only display resolution.
  - The label limit is checked on the raw value by Bean Validation (`@Size(max = 200)`), while the service trims before its own check. A label of 200 characters plus surrounding spaces gets a 400, even though the stored value would fit.

## Summary

Tests added and all validation passed. On the final code (HEAD 88a23f3 plus one new web test), all 65 `core.items` tests pass against Testcontainers Postgres, `./gradlew check` passes (870 tests, including `OpenApiContractTest`), and `.taskcheck` passes. I found no production defects. Task-scoped coverage, from a fresh verified run: lines 203/206 (98.5%), branches 68/70 (97.1%), and `LinkService.kt` at 100%. The only uncovered code is generated or defensive.
