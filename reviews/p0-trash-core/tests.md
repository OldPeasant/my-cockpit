# Test Report

Date: 2026-10-20

Task: p0-trash-core - Trash: listing, restore, purge

Scope: `git diff 8b7cac4314c702a906112ae8da84dfdc30fc6624..ccceabd78146a1c783cff94176ef0cd65982e26b` (one task commit, `ccceabd`, on branch `main`; base supplied by the parent worker), plus the two new, uncommitted test files listed below. No uncommitted production changes.

Requirements:
- `tasks/p0-trash-core.md` acceptance criteria and recorded assumptions (purge strictly older than 30 days, per-item transaction, 404 for not-in-trash / no contributor, ordering deletedAt desc / ItemType / id desc, `purgeAt` = deletedAt + 30 days)
- `SPEC.md` §4.2 (Trash, 30-day retention, cursor pagination, Problem Details)
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`
- Existing conventions in `server/src/test/kotlin/ch/sonensei/cockpit/core/trash/*` (Kotest matchers, JUnit 5, `PostgresTestBase`, `FakeTrashContributorsConfiguration`, `MutableClockTestConfiguration`)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/trash/TrashUnitTests.kt` (new, no Spring/DB)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/trash/TrashTransactionTests.kt` (new, Spring Boot + Testcontainers Postgres)

Existing `TrashServiceTests.kt`, `TrashWebTests.kt`, `FakeTrashContributors.kt` were not modified.

## Tests added or updated

Count: 21 new test methods (19 in `TrashUnitTests`, one of them parameterized with 7 cases, so 25 executions; 2 in `TrashTransactionTests`).

New tests:
- Registry: `the registry refuses two contributors for the same type`, `the registry finds the contributor of a type and nothing for types without one`
- Listing/cursor: `an empty Trash is one empty page`, `a page that holds exactly the remaining items has no next cursor`, `ties on deletedAt and type are ordered by id descending, across pages of one`, `the cursor of the last item gives an empty page`, `paging continues correctly when the cursor's item is restored between pages`, `an item deleted while paging does not shift the following pages`, `a cursor with a well-encoded but invalid position is rejected as a bad request` (bad instant, bad UUID, lower-case type, 2 parts, 4 parts, `||`, `|`), `purgeAt is exactly 30 days after deletedAt`
- Events: `restore announces an update at the clock's time and no deletion`, `purge removes links first, then announces the deletion`, `a restore or purge that finds nothing publishes no event`, `a contributor failing on restore or purge propagates its error and publishes nothing`
- Purge job: `the job keeps an item deleted exactly 30 days ago and purges it one nanosecond later`, `the job does nothing on an empty Trash`, `the job still purges other modules when one contributor fails to list`, `the job skips an item that left the Trash between listing and purging`, `the job leaves recent items alone`
- Transactions (real Postgres link rows): `a purge whose announcement fails rolls back the removal of the links`, `in the job a failing purge rolls back only that item, the others commit`

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: `TrashContributor` per module (list, restore, purge); registry with one contributor per type | `TrashUnitTests`: registry tests, `a restore or purge that finds nothing publishes no event`, `a contributor failing on restore or purge ...`; existing `TrashServiceTests` | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.trash.*' jacocoTestReport ktlintCheck` | Pass |
| AC1: `GET /api/v1/trash` ordering, paging, cursor robustness, 400 for bad cursor/limit | `TrashUnitTests` listing/cursor tests; existing `TrashWebTests` GET tests, `TrashServiceTests` paging tests | same | Pass |
| AC1: `POST /api/v1/trash/{type}/{id}/restore` (204, 404, UPDATED event, links kept) | `restore announces an update ...`, `paging continues correctly when the cursor's item is restored ...`; existing `TrashWebTests`, `TrashServiceTests` | same | Pass |
| AC1: `DELETE /api/v1/trash/{type}/{id}` (204, 404, links removed, DELETED event, one transaction) | `purge removes links first, then announces the deletion`, `a purge whose announcement fails rolls back the removal of the links`; existing tests | same | Pass |
| AC2: scheduled job purges items deleted > 30 days ago, by the test clock | `the job keeps an item deleted exactly 30 days ago and purges it one nanosecond later`, `the job leaves recent items alone`, `the job does nothing on an empty Trash`, `the job still purges other modules when one contributor fails to list`, `the job skips an item that left the Trash between listing and purging`, `in the job a failing purge rolls back only that item, the others commit`; existing job tests incl. cron `0 30 3 * * *` | same | Pass |
| AC3: tests with a fake contributor; trash test filter passes with Postgres via Testcontainers | all 48 tests in `ch.sonensei.cockpit.core.trash.*` (25 unit executions, 23 Spring/Postgres) | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.trash.*'` | Pass (48 tests, 0 failures, 0 skipped) |
| AC4: `api/openapi.yaml` regenerated, `OpenApiContractTest` passes without the flag | existing `OpenApiContractTest` (part of the full server suite in `.taskcheck`); `src/app/api/generated is up to date` check in web step | `sh scripts/check.sh` | Pass (not re-run with `UPDATE_OPENAPI=1`; regeneration itself is the implementer's step) |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.trash.*' jacocoTestReport ktlintCheck --console=plain`

Result:

Pass - BUILD SUCCESSFUL. `TrashUnitTests` 25, `TrashServiceTests` 12, `TrashWebTests` 9, `TrashTransactionTests` 2; 48 tests, 0 failures, 0 errors, 0 skipped. `ktlintCheck` (main, test, scripts) passed.

A first run failed 7 cases of the new parameterized cursor test because the test asserted on the exception message (`InvalidRequestException`'s message is the generic "Request validation failed"; the field is in `fieldErrors`). The test was wrong and was corrected to assert `fieldErrors[].field == "cursor"`; not a production bug.

## Full test execution

Command:

`sh scripts/check.sh` (repository root)

Result:

Pass - "all checks passed". TFP gate tests 18 OK; server ktlint + full test suite 918 tests, 0 failures, 0 errors, 0 skipped (incl. `OpenApiContractTest`); web eslint, 22 files / 321 Vitest tests, generated API up to date, production build OK; android SKIPPED by the script (`COCKPIT_NO_ANDROID=1`, not touched by this task).

## .taskcheck

Result:

Pass (`sh scripts/check.sh`, same run as above).

## Bugs found

None. No production defect was found by the new tests.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: equal to whole-file coverage here, because all four production files are new in this task (added in `ccceabd`, every line is an added line); no separate tool-based changed-line intersection was run.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `p0-trash-core`; base `8b7cac4314c702a906112ae8da84dfdc30fc6624` (supplied by parent); HEAD `ccceabd78146a1c783cff94176ef0cd65982e26b`; single task commit `ccceabd`; file scope from `git diff --stat 8b7cac4..HEAD` matched against the task `touches` and the parent's file list.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `ccceabd` + untracked `TrashUnitTests.kt` (sha256 `df1521f056cd95b6c975071e433ec1d0bb743a357c3eaf9c214fa48f79c7d6a2`) and `TrashTransactionTests.kt` (sha256 `35ad130a62fbbba1dbf2f67e87df37328828770e17656a01a8581f9a3e6e6dea`). No uncommitted production changes.
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/core/trash/domain/TrashContributor.kt`, `.../domain/TrashService.kt`, `.../domain/TrashPurgeJob.kt`, `.../api/TrashController.kt`. Excluded: `api/openapi.yaml` (generated contract, no executable code; covered by `OpenApiContractTest`), `web/src/app/api/generated/**` (generated client, not hand-written, out of the trash test scope). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (existing configuration, unchanged); `./gradlew test --tests 'ch.sonensei.cockpit.core.trash.*' jacocoTestReport ktlintCheck --console=plain` in `server/`; selection = only the trash test package.
- Run start/completion time and exit status: 2026-10-20 ~15:16:22 to 15:16:35 +02:00 (`test.exec` written 15:16:34, XML report 15:16:35); exit status 0 (BUILD SUCCESSFUL). Test classes were recompiled and `:test` executed (not UP-TO-DATE).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 `75e155ce739c81933338319e9948167663fac34f98a8d87dd2a572db32b8ff84`; `server/build/jacoco/test.exec`. Counters extracted with `.tmp/jacoco_trash.py` (reads `<sourcefile>` counters).
- Evidence outputs are fresh and inputs stayed unchanged: yes; the report hash was unchanged after the later `scripts/check.sh` run, and the test file hashes above were taken after the run with no further edits.
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage); zero-denominator metrics are N/A.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/core/trash/domain/TrashContributor.kt` | 107/107 (100%) | 4/4 (100%) | 10/10 (100%) | 14/14 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/trash/domain/TrashService.kt` | 328/328 (100%) | 20/20 (100%) | 17/17 (100%) | 50/50 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/trash/domain/TrashPurgeJob.kt` | 73/73 (100%) | 2/2 (100%) | 3/3 (100%) | 17/17 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/trash/api/TrashController.kt` | 135/160 (84.4%) | N/A (0 branches) | 16/18 (88.9%) | 35/37 (94.6%) |
| **Aggregate (summed counters, 4 files; excluded: generated `api/openapi.yaml`, `web/src/app/api/generated/**`)** | 643/668 (96.3%) | 26/26 (100%) | 46/48 (95.8%) | 116/118 (98.3%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `TrashController.kt:90,104` (`TrashItemResponse`) and `:106,111` (`TrashPageResponse`), partially `:97`, `:110` | Kotlin-generated synthetic default-argument constructors for `subtitle = null` / `nextCursor = null`; the controller always passes every argument, so these are never invoked | Justified gap: compiler glue, no behavior. The null `nextCursor` path itself is covered (`GET lists ...` asserts `nextCursor` absent). |

Before the new tests (existing tests only) `TrashService.kt`, `TrashContributor.kt` and `TrashPurgeJob.kt` were not measured separately; the new tests target the registry duplicate check, the cursor parse failure branches and the job's skip paths.

## Unable to test

- Rollback of contributor-side database changes: the fake contributor is in memory, so only the rollback of the real link rows (via `ItemDeletedEvent` -> `LinkService`) is verified. Real module contributors (JPA soft-delete entities) do not exist yet; their rollback should be tested in the tasks that add them.
- Actual cron firing at 03:30 UTC: verified only that the `CronTask` is registered with `0 30 3 * * *` (existing test); waiting for the trigger is not practical. No follow-up needed.
- Concurrent restore/purge of the same item from two threads: not tested; with the fake there is no shared persistence to race on. The job's "item left the Trash between listing and purging" case is covered deterministically. Worth revisiting with a real contributor.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. 21 new test methods (27 executions) were added, the trash package passes (48 tests), ktlint passes, and `.taskcheck` (`sh scripts/check.sh`) passes.
