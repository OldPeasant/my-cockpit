# Code Review

Date: 2026-10-20

Task: reminders-core - Reminders and notifications backend

Scope: task commit 9669d36212bc1d7a765b05a381a6eba184705951 on top of base 5fd08d7ec02322de492dc8f9389c7d5730e0e3fd (`git diff 5fd08d7..9669d36`). Production code under `server/src/main/kotlin/ch/sonensei/cockpit/core/reminders/**`, `core/events/domain/ClientNotification.kt`, `core/events/infra/SseEventHub.kt`, migration `db/migration/core/V202610201538__core_reminders.sql`, test config `server/src/test/resources/config/application.properties`, the new tests, and the generated `api/openapi.yaml` / `web/src/app/api/generated/**` (consistency only). Committed changes only; working tree was clean.

Requirements:
- `tasks/reminders-core.md` acceptance criteria and its five ASSUMPTION notes
- SPEC.md §4.2 (ids, timestamps, conventions), §4.3 (ItemResolver), §4.6 (reminders)
- docs/ARCHITECTURE.md and AGENTS.md project notes (module boundaries, Problem Details, UUIDv7, logging, Flyway naming)
- existing code conventions (`LinkService`, `ItemResolver`, `GtdItemResolvers`, `TrashPurgeJob`, `ProblemDetailsAdvice`, `BaseEntity`)

Git range:
- Branch: main
- HEAD: 9669d36212bc1d7a765b05a381a6eba184705951
- Base/start: 5fd08d7ec02322de492dc8f9389c7d5730e0e3fd
- Commits reviewed: 1

Commands run:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.reminders.*' --tests '*OpenApiContractTest' --tests '*MigrationConvention*'` -> BUILD SUCCESSFUL; ReminderEventsStreamTests 3, ReminderSchedulerEnabledTests 1, ReminderSchedulerDisabledTests 2, ReminderSchedulerRunTests 3, ReminderServiceEdgeCaseTests 14, ReminderServiceTests 5, ReminderWebTests 12, OpenApiContractTest 1, MigrationConventionTests 2; 0 failures, 0 skipped
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.events.*'` -> BUILD SUCCESSFUL (incl. ClientNotificationHubTests, ClientNotificationStreamTests, SseEventHubTests, EventsStreamTests)
- Manual comparison of the generated web models (`reminder-response.ts`, `reminder-item-response.ts`, `create-reminder-request.ts`, `fn/reminders/*`) with the controller DTOs and `api/openapi.yaml`: consistent

Acceptance criteria check:
1. Table + `GET/POST/DELETE /api/v1/reminders` + `GET /upcoming?until=`: implemented (`ReminderController`, migration). Met.
2. Scheduler fires once (sets `firedAt`, `SELECT ... FOR UPDATE SKIP LOCKED`) and publishes the `reminder` SSE event with the resolved title after commit: implemented and tested against Postgres, incl. rollback and concurrent-lock cases. Met, with the resilience gap in CR-1.
3. Reminder tests pass with Testcontainers and the test clock, incl. "not twice after restart": ran, passing.
4. OpenAPI regenerated and `OpenApiContractTest` passes without the flag: ran, passing; web client regenerated consistently.

Summary: 0 P0, 0 P1, 1 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | A failing real (transactional) ItemResolver rolls back the whole firing batch and blocks all reminders**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/reminders/domain/ReminderService.kt:101-116` and `:136-145`
    - Category: Correctness
    - Problem: `fireDue()` runs in one `@Transactional` and calls `resolveItem()`, which catches a `RuntimeException` from the resolver and continues ("One broken module must not stop the reminders of every other one"). The real resolvers (`gtd/infra/GtdItemResolvers.kt`, `TaskItemResolver.resolve` / `ProjectItemResolver.resolve`) are `@Transactional(readOnly = true)` Spring proxies that join the caller's transaction. When such a resolver throws, Spring's `TransactionInterceptor` marks the shared transaction rollback-only before the exception reaches the `catch`; `fireDue()` then fails at commit with `UnexpectedRollbackException`, rolling back the `firedAt` updates of the whole batch. `ReminderScheduler.run()` logs and retries every poll interval, and since `lockDue` returns the earliest reminders first, a deterministic resolver failure for one early reminder keeps the whole batch (up to 100 reminders, i.e. effectively all) from ever firing. The same applies to `ofItem`/`upcoming`/`get`, which would return 500 instead of `available: false`. The tests (`a failing resolver still sends the notification, without a title`, `GET of a list with a failing resolver still answers with the item unavailable`) pass only because `FakeItemResolver` is not a transactional proxy, so they do not exercise the production behaviour.
    - Why it matters: the documented guarantee (a broken module degrades to a title-less notification) does not hold with the resolvers that actually exist; a single bad row in another module silently stops all reminders, with only a repeating WARN log.
    - Suggested fix: isolate resolver calls from the firing transaction, e.g. resolve each item in a `TransactionTemplate` with `PROPAGATION_REQUIRES_NEW` (read-only) or `NOT_SUPPORTED`, or mark fired in the locked transaction and resolve the title afterwards (e.g. in an after-commit listener before publishing the `ClientNotification`). Add a test with a resolver that is a `@Transactional` bean and throws, asserting the batch still commits and the notification goes out without a title.

## P3 Low

- [ ] **CR-2 | DELETE racing with the scheduler returns an undocumented 409**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/reminders/domain/ReminderService.kt:76-79`, `server/src/main/kotlin/ch/sonensei/cockpit/core/reminders/api/ReminderController.kt:133-148`
    - Category: Integration
    - Problem: `delete` loads the reminder without a lock and deletes it with the `@Version` check. If the scheduler fires the same reminder in between (its `markFired` bumps `version`), Hibernate's delete matches 0 rows, `OptimisticLockingFailureException` is mapped to 409 by `ProblemDetailsAdvice`. The operation is documented as "no `If-Match` needed" with only 204/404 responses, so clients will not expect a 409 for a delete without a precondition.
    - Why it matters: rare, but a user deleting a reminder right at its due time gets an unexpected error and the reminder survives.
    - Suggested fix: delete by id with a bulk query (`delete from Reminder r where r.id = :id`, 404 when 0 rows), or retry once on optimistic lock failure; alternatively document the 409 in the OpenAPI annotations.

## Review conclusion

Material issues found; fix before completing the task. CR-1 (P2) undermines the stated resolver-failure resilience of the scheduler with the resolvers that exist in production code; CR-2 is minor. No security, migration, module-boundary, UUIDv7, Problem Details or logging-rule violations were found, all four acceptance criteria are otherwise met, and the generated API artifacts are consistent.
