# Fix Report

Date: 2026-10-20

Task: reminders-core - Reminders and notifications backend

Scope: findings CR-1 (P2) and CR-2 (P3) of `reviews/reminders-core/review.md` (review of 9669d36 on base 5fd08d7ec02322de492dc8f9389c7d5730e0e3fd). Changes limited to `server/src/main/kotlin/ch/sonensei/cockpit/core/reminders` and `server/src/test/kotlin/ch/sonensei/cockpit/core/reminders`.

## Fixed findings

### CR-1 - A failing real (transactional) ItemResolver rolls back the whole firing batch and blocks all reminders

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/reminders/domain/ReminderItems.kt` (new)
- `server/src/main/kotlin/ch/sonensei/cockpit/core/reminders/domain/ReminderService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/reminders/ReminderTransactionalResolverTests.kt` (new)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/reminders/ReminderServiceTests.kt` (constructor of the "restart" instance)

Problem:
Confirmed. A regression test with a `@Transactional(readOnly = true)` proxied resolver that throws failed on the unchanged code: `fireDue()` and the read paths ended in `UnexpectedRollbackException: Transaction silently rolled back because it has been marked as rollback-only`. That is, one broken item rolled back the whole batch, and reads returned 500.

Fix:
New `ReminderItems` component. It calls `resolve` / `exists` through a read-only `TransactionTemplate` with `PROPAGATION_REQUIRES_NEW`, so a resolver that throws rolls back only its own inner transaction. The catch-and-log behaviour moved there unchanged: a failure gives `null`, `isGone` gives `false`. `ReminderService` now uses it for `get`, `ofItem`, `upcoming`, the display data returned by `create`, and for both the title and the "item gone" check in `fireDue`. Behaviour is kept: item gone -> marked fired silently; resolver failing -> notification without title; reads -> `available: false`.
The existence check in `create` deliberately still runs in the caller's transaction. That way an item created in the same transaction still counts, and a failing check still rejects the request, as before.
Trade-off: while a resolver runs, it uses a second pooled connection.

Regression tests (`ReminderTransactionalResolverTests`): the test resolver is a proxied `@Transactional` bean, behind an adapter so it does not clash with the real `TASK` resolver.
- A batch where one item's resolver throws: both reminders commit as fired, the broken one is notified without a title, the other with its title.
- `service.get` / `ofItem` / `upcoming` and `GET /api/v1/reminders?itemType&itemId` and `GET /api/v1/reminders/{id}` answer with the item unavailable.
- A reminder whose item is gone is still fired silently.

Validation:
- `cd server && ./gradlew test --tests '*ReminderTransactionalResolverTests'` before the fix -> 2 of 3 failed with `UnexpectedRollbackException` (the third, item-gone case, passed)
- same command after the fix -> 3 passed

### CR-2 - DELETE racing with the scheduler returns an undocumented 409

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/reminders/infra/ReminderRepository.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/core/reminders/domain/ReminderService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/reminders/ReminderServiceEdgeCaseTests.kt`

Problem:
Confirmed. A delete that runs while the scheduler's firing transaction is open failed with `ObjectOptimisticLockingFailureException`, which is mapped to 409 (`delete from reminder where id=? and version=?` matched 0 rows). The new test reproduced this when run against the old `delete` body.

Fix:
New bulk query `ReminderRepository.deleteReminder(id)`: `delete from Reminder r where r.id = :id`, with `@Modifying(flushAutomatically = true)` and no clear, like `deleteByItem`. `ReminderService.delete` throws `NotFoundException` (404) when it matched 0 rows. Otherwise it publishes the `DELETED` change event as before. The API contract (204/404) is unchanged, so `api/openapi.yaml` did not need regeneration.

Regression tests:
- `deleting a reminder while the scheduler fires it succeeds instead of failing on its version`: the firing transaction is held open, the delete is shown to block, then the firing commits. The delete succeeds, the row is gone, the last change event is `DELETED`, and a second delete is 404.
- `a fired reminder can be deleted although the scheduler changed its version`.

Validation:
- `cd server && ./gradlew test --tests '*ReminderServiceEdgeCaseTests'` with the old delete body -> race test failed with `ObjectOptimisticLockingFailureException`
- same command with the fix -> 16 passed

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.reminders.*' --tests '*OpenApiContractTest' --rerun` -> BUILD SUCCESSFUL, run twice. Each run: ReminderServiceTests 5, ReminderServiceEdgeCaseTests 16, ReminderTransactionalResolverTests 3, ReminderWebTests 12, ReminderEventsStreamTests 3, ReminderSchedulerRunTests 3, ReminderSchedulerEnabledTests 1, ReminderSchedulerDisabledTests 2, OpenApiContractTest 1; 0 failures, 0 skipped.

Full test suite:
- `cd server && ./gradlew check` -> BUILD SUCCESSFUL (ktlint, detekt and all server tests; 93 result files, 0 failures)

Lint/type-check/build:
- `cd server && ./gradlew ktlintFormat` -> no remaining issues; covered by `./gradlew check` above

`.taskcheck`:
- `sh scripts/check.sh` -> Pass ("all checks passed", 1m19s)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
