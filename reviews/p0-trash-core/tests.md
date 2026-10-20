# Test Report

Date: 2026-10-20

Task: p0-trash-core - Trash: listing, restore, purge

Scope: `git diff 8b7cac4314c702a906112ae8da84dfdc30fc6624..ef5dec1d3e5fea8ebc6912a5225750542e9178c4` on branch `main` (task commits `ccceabd` implementation, `403ac8b` tests and first-pass reports, `ef5dec1` review fixes; base supplied by the parent worker). This is the second test pass, on the final reviewed revision `ef5dec1`. The fixer delta checked here is `git diff 403ac8b..ef5dec1`: `TrashService` lists every contributor in its own `REQUIRES_NEW` read-only transaction, and `list`/`entries` became `Propagation.NOT_SUPPORTED`; `TrashController` documents 400 responses for restore and delete; `api/openapi.yaml` was regenerated. The working tree was clean before and after this pass. No uncommitted source or test changes.

Requirements:
- `tasks/p0-trash-core.md` acceptance criteria and recorded assumptions (purge strictly older than 30 days, per-item transaction, 404 for not-in-trash / no contributor, ordering deletedAt desc / ItemType / id desc, `purgeAt` = deletedAt + 30 days)
- `SPEC.md` §4.2 (Trash, 30-day retention, cursor pagination, Problem Details)
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`
- `reviews/p0-trash-core/review.md` and `reviews/p0-trash-core/fixes.md` (the behavior changed by the fixer)
- Existing conventions in `server/src/test/kotlin/ch/sonensei/cockpit/core/trash/*` (Kotest matchers, JUnit 5, `PostgresTestBase`, `FakeTrashContributorsConfiguration`, `MutableClockTestConfiguration`)

## Test files changed

The second pass changed no test files. These test files are in the task scope (all committed at `ef5dec1`):
- `server/src/test/kotlin/ch/sonensei/cockpit/core/trash/TrashUnitTests.kt`: added in the first pass. The fixer changed it to pass a no-op `PlatformTransactionManager` to the new constructor parameter.
- `server/src/test/kotlin/ch/sonensei/cockpit/core/trash/TrashTransactionTests.kt`: added in the first pass.
- `server/src/test/kotlin/ch/sonensei/cockpit/core/trash/TrashContributorIsolationTests.kt`: added by the fixer. It uses Spring Boot, Postgres via Testcontainers and DB-backed contributors.
- `TrashServiceTests.kt`, `TrashWebTests.kt`, `FakeTrashContributors.kt`: from the implementation commit.

## Tests added or updated

Count: 0 in this pass. No meaningful gap was found in the fixer's changes (see below).

How the fixer's changes are covered:
- **Per-contributor listing transaction (`TrashService.entries`, `list`):** `TrashContributorIsolationTests` covers this with two tests:
  - `a contributor failing in the database is left out of the list, the others are listed`
  - `a contributor failing in the database does not stop the purge job for the others`

  A contributor runs invalid SQL in a transaction. This aborts the Postgres transaction and marks a shared Spring transaction rollback-only. Before the fix, `list` used a shared `@Transactional(readOnly = true)`, so the following contributor's `select 1` would fail and its items would vanish too. These tests therefore act as regression tests for the review finding. `TrashUnitTests` (job and listing tests with a throwing contributor) cover the in-memory path through the new `TransactionTemplate`.
- **`NOT_SUPPORTED` on `list`/`entries`:** this is exercised by every list and job test. There is no separate test for calling `list` inside a caller's transaction, because no caller in this task does that.
- **Documented 400s on restore/delete:**
  - `OpenApiContractTest` checks that `api/openapi.yaml` matches the controller. The file has 2 occurrences of "Invalid type or id".
  - `TrashWebTests.an unknown type or a malformed id gives 400` checks the runtime behavior: restore with an unknown type and delete with a malformed id both return a 400 Problem.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: `TrashContributor` per module (list, restore, purge); registry with one contributor per type | `TrashUnitTests` registry tests, `a restore or purge that finds nothing publishes no event`, `a contributor failing on restore or purge propagates its error and publishes nothing`; `TrashServiceTests` | `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.trash.*' --tests '*OpenApiContractTest' jacocoTestReport --console=plain` | Pass |
| AC1: `GET /api/v1/trash`: ordering, paging, cursor robustness, 400 for a bad cursor/limit, a failing contributor is left out | `TrashUnitTests` listing/cursor tests; `TrashContributorIsolationTests.a contributor failing in the database is left out of the list, the others are listed`; `TrashWebTests.GET ...`; `TrashServiceTests` paging tests | same | Pass |
| AC1: `POST /api/v1/trash/{type}/{id}/restore` (204, 404, 400 documented and returned, UPDATED event, links kept) | `restore announces an update at the clock's time and no deletion`; `TrashWebTests.POST restore ...`, `... gives 404`, `an unknown type or a malformed id gives 400`; `OpenApiContractTest` | same | Pass |
| AC1: `DELETE /api/v1/trash/{type}/{id}` (204, 404, 400 documented and returned, links removed, DELETED event, one transaction) | `purge removes links first, then announces the deletion`, `a purge whose announcement fails rolls back the removal of the links`; `TrashWebTests` DELETE / 404 / 400 tests; `OpenApiContractTest` | same | Pass |
| AC2: scheduled job purges items deleted more than 30 days ago, using the test clock | `the job keeps an item deleted exactly 30 days ago and purges it one nanosecond later`, `the job leaves recent items alone`, `the job does nothing on an empty Trash`, `the job still purges other modules when one contributor fails to list`, `the job skips an item that left the Trash between listing and purging`, `in the job a failing purge rolls back only that item, the others commit`, `TrashContributorIsolationTests.a contributor failing in the database does not stop the purge job for the others`; existing cron test `0 30 3 * * *` | same | Pass |
| AC3: tests with a fake contributor; the trash test filter passes with Postgres via Testcontainers | 50 tests in `ch.sonensei.cockpit.core.trash.*` (25 unit executions, 25 Spring/Postgres) | same | Pass (50 tests, 0 failures, 0 errors, 0 skipped) |
| AC4: `api/openapi.yaml` regenerated; `OpenApiContractTest` passes without the update flag | `OpenApiContractTest`; the `src/app/api/generated is up to date` check in the web step | same, plus `sh scripts/check.sh` | Pass (not re-run with `UPDATE_OPENAPI=1`; regenerating the file is the implementer's step) |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.trash.*' --tests '*OpenApiContractTest' jacocoTestReport --console=plain`

Result:

Pass: BUILD SUCCESSFUL in 17s. `:test` ran (it was not UP-TO-DATE). `ktlintCheck` (main, test and scripts) was UP-TO-DATE, meaning it had already passed for these exact inputs.

| Test class | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| `TrashUnitTests` | 25 | 0 | 0 | 0 |
| `TrashServiceTests` | 12 | 0 | 0 | 0 |
| `TrashWebTests` | 9 | 0 | 0 | 0 |
| `TrashTransactionTests` | 2 | 0 | 0 | 0 |
| `TrashContributorIsolationTests` | 2 | 0 | 0 | 0 |
| `OpenApiContractTest` | 1 | 0 | 0 | 0 |
| **Total** | **51** | **0** | **0** | **0** |

## Full test execution

Command:

`sh scripts/check.sh` (repository root)

Result:

Pass: "all checks passed".
- Server: ktlint plus the full server suite, 920 tests, 0 failures, 0 errors, 0 skipped (summed from `server/build/test-results/test/*.xml`, 78 suites).
- Web: check, generated API up to date, production build OK.
- Android: SKIPPED by the script (`COCKPIT_NO_ANDROID=1`); this task does not touch Android.

## .taskcheck

Result:

Pass (`sh scripts/check.sh`, the same run as above, at HEAD `ef5dec1` with a clean tree).

## Bugs found

None. The tests found no production defects in the final revision.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: equal to whole-file coverage. All four production files are new in this task (added in `ccceabd`, edited in `ef5dec1`), so every line is a task line. No separate tool-based changed-line intersection was run.

Evidence:
- **Task id, immutable base, measured HEAD, and task-owned commits/file-scope source:** `p0-trash-core`; base `8b7cac4314c702a906112ae8da84dfdc30fc6624` (supplied by the parent); measured HEAD `ef5dec1d3e5fea8ebc6912a5225750542e9178c4`; task commits `ccceabd`, `403ac8b`, `ef5dec1` (`git log 8b7cac4..HEAD`). The file scope comes from the task diff, checked against the task `touches` and the parent's file list.
- **Source/test snapshot identifier (including uncommitted inputs):** commit `ef5dec1d3e5fea8ebc6912a5225750542e9178c4`. The working tree was clean (`git status --short` was empty), so no uncommitted inputs.
- **Exact included files; renamed/deleted/excluded files and reasons:**
  - Included: `server/src/main/kotlin/ch/sonensei/cockpit/core/trash/domain/TrashContributor.kt`, `.../domain/TrashService.kt`, `.../domain/TrashPurgeJob.kt`, `.../api/TrashController.kt`.
  - Excluded: `api/openapi.yaml` (generated contract with no executable code; covered by `OpenApiContractTest`) and `web/src/app/api/generated/**` (generated client).
  - No renames or deletions.
- **Provider/version, command, working directory, and test selection:** JaCoCo through the existing Gradle `jacoco` plugin configuration (unchanged). Command: `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.trash.*' --tests '*OpenApiContractTest' jacocoTestReport --console=plain`, run in `server/`. Selection: the trash test package plus `OpenApiContractTest`.
- **Run start/completion time and exit status:** 2026-10-20 about 15:26:25 to 15:26:44 +02:00 (suites ran 13:26:32Z to 13:26:43Z; `test.exec` and the XML report were written at 15:26). Exit status 0 (BUILD SUCCESSFUL).
- **Generated report paths and report hashes/run identifier:** `server/build/reports/jacoco/test/jacocoTestReport.xml`, sha256 `bbabb0fa43d9f4e43d51377bc373fec31796b6272a088d8658f89abd1332c782`; `server/build/jacoco/test.exec`. Counters were extracted with `.tmp/jacoco_trash.py`, which reads the provider's `<sourcefile>` counters and `<line>` entries.
- **Evidence outputs are fresh and inputs stayed unchanged:** yes.
  - The first-pass report hash (`75e155ce...`) differs from this one, and `:test` was executed rather than UP-TO-DATE.
  - The report hash was unchanged after the later `scripts/check.sh` run.
  - No source or test file was edited during or after the run.
- **Final run after latest test additions and production fixes:** yes. This pass measures `ef5dec1`, which includes the fixer's production changes and tests.

Values below use covered/total (percentage); zero-denominator metrics are N/A.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/core/trash/domain/TrashContributor.kt` | 107/107 (100%) | 4/4 (100%) | 10/10 (100%) | 14/14 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/trash/domain/TrashService.kt` | 360/362 (99.4%) | 21/22 (95.5%) | 18/18 (100%) | 57/57 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/trash/domain/TrashPurgeJob.kt` | 73/73 (100%) | 2/2 (100%) | 3/3 (100%) | 17/17 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/trash/api/TrashController.kt` | 135/160 (84.4%) | N/A (0 branches) | 16/18 (88.9%) | 35/37 (94.6%) |
| **Aggregate (summed counters over 4 files; excluded: generated `api/openapi.yaml`, `web/src/app/api/generated/**`)** | 675/702 (96.2%) | 27/28 (96.4%) | 47/49 (95.9%) | 123/125 (98.4%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `TrashService.kt:83`: 1 of 2 branches and 2 instructions missed (the `null` side of `listingTransaction.execute { ... }.orEmpty()`) | `TransactionTemplate.execute` returns a platform type (`T?`). It is `null` only if the callback returns `null`, which a Kotlin `TrashContributor` cannot do, because `listDeleted()` is declared as non-null `List<TrashedItem>`. | Justified gap: this is a defensive null guard, and no contributor in the task can reach it. The behavior that matters on this line (one contributor's listing per transaction, with failures isolated) is covered by `TrashContributorIsolationTests` and the `TrashUnitTests` failing-contributor tests. |
| `TrashController.kt:100,114` (`TrashItemResponse`) and `:116,121` (`TrashPageResponse`), partially `:107`, `:120` | Kotlin's synthetic default-argument constructors for `subtitle = null` and `nextCursor = null`. The controller always passes every argument, so they are never called. These are the same gaps as in the first pass, moved 10 lines down by the added `@ApiResponse` annotations. | Justified gap: compiler glue with no behavior. The null `nextCursor` path itself is covered (`GET lists ...` asserts that `nextCursor` is absent). |

## Unable to test

- **Rollback of contributor-side database changes:**
  - Restore and purge: the contributors are in-memory fakes. Only the rollback of the real link rows (via `ItemDeletedEvent` -> `LinkService`) is verified, along with the DB-level isolation of listings (`TrashContributorIsolationTests`).
  - Real module contributors (JPA soft-delete entities) do not exist yet. Their rollback should be tested in the tasks that add them.
- **Actual cron firing at 03:30 UTC:** the tests only verify that the `CronTask` is registered with `0 30 3 * * *`. Waiting for the trigger is not practical. No follow-up needed.
- **Concurrent restore/purge of the same item from two threads:** not tested, because the fake has no shared persistence to race on. The job's "item left the Trash between listing and purging" case is covered deterministically. Worth revisiting once a real contributor exists.
- **A contributor that swallows its own SQL error and returns normally while its listing transaction is rollback-only:** not tested. Committing the `REQUIRES_NEW` listing transaction would then throw `UnexpectedRollbackException`, a `RuntimeException` that `entries()` catches and logs, so that contributor would be left out. This is a contributor-side bug pattern, not task behavior. No follow-up needed.

## Summary

Tests and task-scoped coverage are sufficient, and no production defects were found. On the final revision `ef5dec1`, the fixer's changes are covered:
- the per-contributor `REQUIRES_NEW` listing transactions are covered by the DB-backed regression tests in `TrashContributorIsolationTests`;
- the documented 400s are covered by `OpenApiContractTest` and `TrashWebTests`.

No new tests were needed in this pass. The focused run passed (51 tests plus ktlint), and `.taskcheck` (`sh scripts/check.sh`) passed with 920 server tests. Task-scoped coverage is 123/125 lines and 27/28 branches. The only gaps are a defensive null branch and Kotlin compiler-generated constructors.
