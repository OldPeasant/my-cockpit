# Code Review

Date: 2026-10-20

Task: p0-trash-core - Trash: listing, restore, purge

Scope: `git diff 8b7cac4..ccceabd` (production code in `server/src/main/kotlin/ch/sonensei/cockpit/core/trash/**`, tests in `server/src/test/kotlin/ch/sonensei/cockpit/core/trash/**`, the regenerated `api/openapi.yaml` and `web/src/app/api/generated/**`)

Requirements:
- `tasks/p0-trash-core.md` acceptance criteria and recorded assumptions
- SPEC.md §4.2 (soft delete, Trash, 30-day purge, cursor pagination, Problem Details) and §4.3 (ItemType, links)
- docs/ARCHITECTURE.md (code conventions, persistence conventions, Clock)
- AGENTS.md "Project notes"
- Existing conventions: `ItemResolver`/`ItemResolverRegistry`, `LinkService`, `CursorRequest`, `ProblemDetailsAdvice`, `LinkController`

Git range:
- Branch: main
- HEAD: ccceabd78146a1c783cff94176ef0cd65982e26b
- Base/start: 8b7cac4314c702a906112ae8da84dfdc30fc6624
- Commits reviewed: 1 (ccceabd)

Summary: 0 P0, 0 P1, 1 P2, 1 P3

The four acceptance criteria are implemented:
- The `TrashContributor` interface, its registry and the three endpoints (`GET /api/v1/trash`, `POST /api/v1/trash/{type}/{id}/restore`, `DELETE /api/v1/trash/{type}/{id}`) exist.
- The daily purge job uses the injected `Clock` and purges items deleted strictly more than 30 days ago. A test drives it with `MutableClock` and checks the boundary.
- Tests use fake TASK and NOTE contributors on the Postgres test base.
- The OpenAPI contract and the web client are regenerated.

Other points checked:
- Cursor encoding and decoding round-trip correctly, and the keyset order is consistent between sorting and filtering.
- Errors are returned as Problem Details: 400 for a bad cursor or limit, 400 for a bad type or id, 404 when the item is not in the Trash.
- Purging publishes `ItemDeletedEvent` in the same transaction, so links are removed only when the purge commits.
- Scheduling is enabled with a module-specific configuration bean name.
- CSRF and authentication are enforced and tested.
- No secrets or content bodies are logged.

I did not run the test suite myself. Running commands was mostly denied in this session, so I could only read the code and `git diff`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | A failing contributor breaks the whole Trash listing and the purge job when contributors use the database, despite the stated isolation**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/trash/domain/TrashService.kt:46-69` (also `TrashPurgeJob.kt:31`)
    - Category: Correctness
    - Problem: `list()` and `entries()` run every contributor's `listDeleted()` inside one shared `@Transactional(readOnly = true)` transaction. The code then catches `RuntimeException` per contributor so that "one broken module does not hide the rest of the Trash" (KDoc lines 43-44 and 59). That only works for contributors that touch no transactional resource, like the in-memory fakes in the tests. Real contributors will read through `SoftDeletableRepository`, and Spring Data repository methods are transactional proxies. If one of them throws, two things happen:
        1. The participating interceptor marks the shared transaction rollback-only. Committing `list()`/`entries()` then throws `UnexpectedRollbackException`, so the caller gets a 500 instead of a partial list.
        2. If the cause was a SQL error, PostgreSQL aborts the transaction. Every later contributor's query in the same loop also fails ("current transaction is aborted").

      The scheduled `purgeExpired()` calls `trash.entries()` outside any try/catch. So one broken module stops the automatic 30-day purge for every module, not just for itself.
    - Why it matters: The KDoc promises this resilience to future module contributors, but the code cannot deliver it once real JPA-backed contributors are registered. The test `a failing contributor is left out of the list` passes only because the fake is not transactional, so it hides the problem. A purge that silently stops for all types breaks the SPEC.md §4.2 promise that deleted items are "purged after 30 days".
    - Suggested fix: Isolate each contributor call in its own transaction. For example, remove `@Transactional` from `list`/`entries` and wrap each `contributor.listDeleted()` in a `TransactionTemplate` with `PROPAGATION_REQUIRES_NEW` and read-only, or let each contributor own its read transaction. Also let `purgeExpired()` survive a failing `entries()` per contributor. Add a test with a fake contributor that runs its failure inside a transaction, e.g. a `@Transactional` fake bean that throws, or one that runs an invalid SQL statement. The test should show that the other contributors' items are still listed and purged.

## P3 Low

- [ ] **CR-2 | The 400 responses of restore and purge are tested but missing from the OpenAPI contract**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/trash/api/TrashController.kt:57-87`
    - Category: Integration
    - Problem: An unknown `{type}` or a malformed `{id}` returns 400 Problem Details through `ProblemDetailsAdvice.handleTypeMismatch`. `TrashWebTests` asserts this (`an unknown type or a malformed id gives 400`). But `restoreTrashItem` and `purgeTrashItem` only declare 204 and 404 (`api/openapi.yaml`, `/api/v1/trash/{type}/{id}` and `.../restore`). `listLinks` does document its 400 for an invalid type or id (`LinkController.kt:53-57`).
    - Why it matters: The generated clients and the contract do not describe a response that the server sends and the tests check.
    - Suggested fix: Add an `@ApiResponse(responseCode = "400", description = "Invalid type or id", ...)` with the ProblemDetail schema to both operations, then regenerate `api/openapi.yaml` and the web client.

## Review conclusion

Material issues found; fix before completing the task. CR-1 is a resilience defect in the contract offered to future contributors and in the purge job, and the current tests do not catch it. CR-2 is a small gap in the contract documentation.
