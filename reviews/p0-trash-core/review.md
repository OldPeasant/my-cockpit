# Code Review

Date: 2026-10-20

Task: p0-trash-core - Trash: listing, restore, purge

Scope: second pass on the final code. Full task diff `git diff 8b7cac4..ef5dec1`: production code in `server/src/main/kotlin/ch/sonensei/cockpit/core/trash/**`, tests in `server/src/test/kotlin/ch/sonensei/cockpit/core/trash/**`, the regenerated `api/openapi.yaml` and `web/src/app/api/generated/**`. The review-fix delta `git diff ccceabd..ef5dec1 -- server/src/main api` was checked in detail.

Requirements:
- `tasks/p0-trash-core.md` acceptance criteria and recorded assumptions
- SPEC.md §4.2 (soft delete, Trash, 30-day purge, cursor pagination, Problem Details) and §4.3 (ItemType, links)
- docs/ARCHITECTURE.md (code conventions, persistence conventions, Clock)
- AGENTS.md "Project notes"
- The first review of ccceabd (CR-1, CR-2) and `reviews/p0-trash-core/fixes.md`
- Existing conventions: `ItemResolverRegistry`, `LinkController`, `CursorRequest`, `ProblemDetailsAdvice`, `server/src/main/resources/application.properties`

Git range:
- Branch: main
- HEAD: ef5dec1d3e5fea8ebc6912a5225750542e9178c4
- Base/start: 8b7cac4314c702a906112ae8da84dfdc30fc6624
- Commits reviewed: 3 (ccceabd, 403ac8b, ef5dec1); no uncommitted changes when the review started

Summary: 0 P0, 0 P1, 0 P2, 0 P3 (open). Both earlier findings are resolved.

## Status of earlier findings

- [x] **CR-1 | A failing contributor breaks the whole Trash listing and the purge job (P2): Resolved**
    - `TrashService.kt:51-55` adds a `TransactionTemplate` with `PROPAGATION_REQUIRES_NEW` and read-only. `entries()` (`TrashService.kt:79-90`) runs each `contributor.listDeleted()` inside it. The existing per-contributor `catch (RuntimeException)` stays.
    - `list()` and `entries()` are now `@Transactional(propagation = NOT_SUPPORTED)` (`TrashService.kt:61,78`), so no shared outer transaction can be marked rollback-only or aborted by Postgres. `list()` calls `entries()` on itself, which bypasses the proxy, but `list()` carries the same annotation, so the behavior is the same.
    - All failure modes are caught per contributor:
        - an exception rolls back only that contributor's transaction, and `TransactionTemplate` rethrows it;
        - if a contributor swallows its own exception after marking the transaction rollback-only, the commit throws `UnexpectedRollbackException`, which is also a `RuntimeException`;
        - a failed rollback throws `TransactionSystemException`, also a `RuntimeException`.
    - `spring.jpa.open-in-view=false` (`application.properties:10`), so the per-contributor transactions do not share a pre-bound EntityManager in web requests.
    - `TrashPurgeJob.purgeExpired()` now gets the isolated `entries()`. Each `trash.purge()` already ran in its own `REQUIRED` transaction, because the job itself is not transactional, and failures are caught per item.
    - The regression test `TrashContributorIsolationTests` runs on Postgres. Its failing contributor executes invalid SQL inside a joining `TransactionTemplate`, which reproduces both the rollback-only and the aborted-transaction mode. The test checks that the list still returns the other contributor's item and that the job still purges it while the failing type stays in the Trash. According to `fixes.md`, the test failed before the fix with `UnexpectedRollbackException`. That matches the mechanism described in CR-1.
    - The KDoc in `TrashContributor.kt:8-12` now describes the per-contributor transaction. `TrashUnitTests` passes a no-op `AbstractPlatformTransactionManager` to the new constructor parameter, which is appropriate for a unit test.

- [x] **CR-2 | The 400 responses of restore and purge are missing from the OpenAPI contract (P3): Resolved**
    - `TrashController.kt:61-65` and `84-88` declare `400 "Invalid type or id"` with the `ProblemDetail` schema, the same way `LinkController` and this controller's `list` do.
    - `api/openapi.yaml` has the matching entries for `DELETE /api/v1/trash/{type}/{id}` and `POST .../restore`. The generated web client does not change, because the generator does not model error responses. `fixes.md` records `npm run api:check` as up to date.

## Acceptance criteria re-check (ef5dec1)

1. `TrashContributor` (with `listDeleted`, `restore` and `purge`), `TrashContributorRegistry` (fails on a duplicate type), and the three endpoints `GET /api/v1/trash`, `POST /api/v1/trash/{type}/{id}/restore` and `DELETE /api/v1/trash/{type}/{id}` are implemented. Met in code.
2. The `TrashPurgeJob` cron is `cockpit.trash.purge-cron`, defaulting to 03:30 UTC. It uses the injected `Clock` and purges items whose `deletedAt` is strictly before now minus 30 days. Tests drive it with `MutableClock`. Met in code.
3. Fake contributors on `PostgresTestBase` are used in `TrashServiceTests`, `TrashWebTests`, `TrashTransactionTests` and `TrashContributorIsolationTests`, plus `TrashUnitTests`. Met in code. The test run is reported in `fixes.md`.
4. The `api/openapi.yaml` and web client regeneration is reported in `fixes.md`. Met in code.

`python tasks.py verify p0-trash-core` shows no evidence recorded yet for any criterion. The parent worker records it after the quality workflow (AGENTS.md step 7), so this is not a finding. It must be done before `done`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. CR-1 and CR-2 are resolved in ef5dec1, and the full task diff meets the four acceptance criteria. I did not run the test suite in this pass and read only the code and Git history. The test results above come from `fixes.md` and are not my own observation.
