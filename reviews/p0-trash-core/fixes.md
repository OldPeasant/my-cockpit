# Fix Report

Date: 2026-10-20

Task: p0-trash-core - Trash: listing, restore, purge

Scope: current task, findings CR-1 and CR-2 of `reviews/p0-trash-core/review.md` (base 8b7cac4, reviewed HEAD 403ac8b)

## Fixed findings

### CR-1 — A failing contributor breaks the whole Trash listing and the purge job when contributors use the database

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/trash/domain/TrashService.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/core/trash/domain/TrashContributor.kt` (KDoc only)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/trash/TrashContributorIsolationTests.kt` (new)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/trash/TrashUnitTests.kt` (constructor change only)

Problem:
`list()` and `entries()` ran every contributor's `listDeleted()` in one shared read-only transaction. If a contributor failed with an SQL error, Postgres aborted the shared transaction. If it failed through a transactional proxy, Spring marked the transaction rollback-only. The other contributors' queries then failed too, and the commit threw `UnexpectedRollbackException`. The result was a 500 on `GET /api/v1/trash` and a `TrashPurgeJob.purgeExpired()` that stopped for every type. The new regression test reproduced the bug before the fix: both cases failed with `UnexpectedRollbackException: Transaction silently rolled back because it has been marked as rollback-only`.

Fix:
`TrashService` now gets the `PlatformTransactionManager` and runs each contributor's `listDeleted()` in its own `TransactionTemplate` with `PROPAGATION_REQUIRES_NEW` and read-only. The existing catch-and-log per contributor is unchanged. `list()` and `entries()` are now `@Transactional(propagation = NOT_SUPPORTED)`, so they no longer hold an outer transaction or connection. `TrashPurgeJob` needed no change: it gets its entries from the now-isolated `entries()`, and each purge already ran in its own transaction with a per-item catch.

The regression test `TrashContributorIsolationTests` runs on Postgres with two database-backed contributors registered in order TASK then NOTE. While listing, TASK runs `select missing_column from no_such_table` inside a `TransactionTemplate` that joins the caller's transaction. NOTE runs `select 1`. The test shows that:
- the list still returns the NOTE item;
- `purgeExpired()` still purges the expired NOTE item (returns 1), and the TASK item stays in the Trash.

The unit test passes a no-op transaction manager to the new constructor parameter.

Validation:
- `./gradlew test --tests '*TrashContributorIsolationTests'` before the fix — Fail (2/2, UnexpectedRollbackException, as expected)
- `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.trash.*' --tests '*OpenApiContractTest'` after the fix — Pass

### CR-2 — The 400 responses of restore and purge are missing from the OpenAPI contract

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/trash/api/TrashController.kt`
- `api/openapi.yaml` (regenerated)

Problem:
For an unknown type or a malformed id, `restoreTrashItem` and `purgeTrashItem` return 400 Problem Details, and the tests check this. The contract only declared 204 and 404.

Fix:
Added `@ApiResponse(responseCode = "400", description = "Invalid type or id", ...)` with the `ProblemDetail` schema to both operations, the same way `LinkController` does it. Then regenerated `api/openapi.yaml`. The generated web client did not change, because the generator does not model error responses.

Validation:
- `cd server && UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'` — Pass (adds the 400 to both paths)
- `cd web && npm run api:gen && npm run api:check` — Pass ("src/app/api/generated is up to date.")

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat` — Pass
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.trash.*' --tests '*OpenApiContractTest'` — Pass: TrashContributorIsolationTests 2, TrashServiceTests 12, TrashUnitTests 25, TrashWebTests 9, TrashTransactionTests 2, OpenApiContractTest 1; 0 failures

Full test suite:
- Covered by `.taskcheck` below (server `./gradlew check` and web `npm run check`)

Lint/type-check/build:
- ktlint (server) — Pass; web lint, tests and build run by `.taskcheck` — Pass

`.taskcheck`:
- `sh scripts/check.sh` — Pass ("all checks passed"; Android skipped by the script with COCKPIT_NO_ANDROID=1)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
