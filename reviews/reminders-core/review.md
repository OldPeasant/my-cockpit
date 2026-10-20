# Code Review

Date: 2026-10-20

Task: reminders-core - Reminders and notifications backend

Scope: `git diff 3f0c01c6983106a3c91f1493a7c8842b0f566951..9d2f201ea7ad3624de79650e268896d239da0b1f` (whole task change on the final revision; rerun after the CR-1/CR-2 fixes in 8238e1c)

Requirements:
- tasks/reminders-core.md (acceptance criteria 1-4 and recorded assumptions 1-5)
- SPEC.md §4.2 (ids, timestamps, conventions), §4.3 (ItemResolver), §4.6 (reminders and notifications)
- docs/ARCHITECTURE.md (code, persistence and migration conventions)
- AGENTS.md project notes (module boundaries, generated files, logging, lint)
- reviews/reminders-core/fixes.md and tests.md (to confirm the earlier findings)

Git range:
- Branch: main
- HEAD: 9d2f201ea7ad3624de79650e268896d239da0b1f
- Base/start: 3f0c01c6983106a3c91f1493a7c8842b0f566951
- Commits reviewed: 6 (e1b919f, b7828f2, 8238e1c, c4c7dcc, 2662524, 9d2f201); no uncommitted changes at review start

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Verification performed

- Read the full diff and, in full, `ReminderController.kt`, `Reminder.kt`, `ReminderItems.kt`, `ReminderService.kt`, `ReminderRepository.kt`, `ReminderScheduler.kt`, `ClientNotification.kt`, the `SseEventHub.kt` change, the migration `core/V202610201538__core_reminders.sql`, the test configuration change, and the context code (`ItemResolver`/`ItemResolverRegistry`, `BaseEntity`, `ItemDeletedEvent` publishers, `LinkController` conventions).
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.reminders.*' --tests '*OpenApiContractTest'` -> BUILD SUCCESSFUL; ReminderWebTests 12, ReminderServiceTests 5, ReminderServiceEdgeCaseTests 16, ReminderTransactionalResolverTests 3, ReminderGtdResolverTests 7, ReminderEventsStreamTests 3, ReminderSchedulerRunTests 3, ReminderSchedulerEnabledTests 1, ReminderSchedulerDisabledTests 2, OpenApiContractTest 1; 0 failures, 0 skipped (Postgres via Testcontainers, `MutableClock`).
- `api/openapi.yaml` diff is purely additive (reminder paths and schemas); generated web client files match the new operations.

## Acceptance criteria

1. Table + `GET/POST/DELETE /api/v1/reminders` + `GET /upcoming?until=`: implemented (`ReminderController.kt`, migration with BaseEntity columns, UUIDv7 ids, ISO-8601 instants, Problem Details for 400/404). `GET` by item requires itemType+itemId as recorded in the assumptions. Covered by ReminderWebTests.
2. Scheduler fires once and publishes the `reminder` SSE event with the item title: `lockDue` (`FOR UPDATE SKIP LOCKED`) + `markFired` + `ReminderFiredEvent` -> `ReminderNotifier` -> `ClientNotification` -> `SseEventHub.onNotification` after commit. Covered by ReminderEventsStreamTests (real SSE stream, rollback case) and ReminderSchedulerEnabledTests (background scheduler).
3. Focused test command passes with Testcontainers and the test clock, including "fires once, not twice after restart" (`ReminderServiceTests` restart case) - reproduced above.
4. OpenAPI regenerated; `OpenApiContractTest` passes without the flag - reproduced above.

Note for the parent worker: `python tasks.py verify reminders-core` shows no criterion recorded yet; this is expected at this step of the workflow but must be done before `done`.

## Earlier findings

- CR-1 (failing resolver rolls back the firing batch / turns reads into 500): resolved. `ReminderItems` runs every display/gone lookup in a read-only `REQUIRES_NEW` transaction and catches failures; `ReminderService` uses it for `get`, `ofItem`, `upcoming`, `create`'s response and both lookups in `fireDue`. `ReminderTransactionalResolverTests` uses a proxied `@Transactional` resolver that throws and confirms the batch commits and reads answer `available: false`.
- CR-2 (DELETE racing the scheduler -> 409): resolved. `ReminderRepository.deleteReminder` is a version-less bulk delete; under a concurrent firing it blocks on the row lock and then deletes the fired row (Postgres re-evaluates `id = :id` on the updated row). The race regression test in `ReminderServiceEdgeCaseTests` holds the firing transaction open and confirms 204 semantics and a later 404.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Isolated resolver transactions need a second pooled connection per request**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/reminders/domain/ReminderItems.kt:25`
    - Category: Quality
    - Problem: Every `resolve`/`isGone` call opens a `REQUIRES_NEW` transaction while the caller's transaction (read-only for `get`/`ofItem`/`upcoming`, read-write with row locks in `fireDue`) still holds its own connection. Each reminder request therefore needs two connections at once, and `upcoming` can open up to 1000 nested transactions (one per distinct item). If as many concurrent reminder requests as the Hikari pool size (default 10, tests 5) reach the resolver step together, they wait on each other until the pool's connection timeout; the timeout is caught and the items come back `available: false` / notified without title. The fix report names the second connection as a trade-off, but neither the code comment nor the assumptions record it.
    - Why it matters: For a single-user cockpit this is unlikely, but it is a latent pool-starvation pattern and a latency cost on the Android sync path that a later reader would not expect from a read endpoint.
    - Suggested fix: Either document the trade-off in the `ReminderItems` KDoc (and record it with `tasks.py assume`), or, for the read paths, resolve outside the outer transaction (e.g. load the reminders in the transactional service method and resolve the items in a non-transactional step) so only one connection is held at a time.

- [ ] **CR-2 | `upcoming` cannot tell Android which reminders the server would suppress**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/reminders/api/ReminderController.kt:186`
    - Category: Integration
    - Problem: `fireDue` suppresses the notification when the item is gone (trashed/deleted) but notifies when the type has no resolver or the resolver fails. `GET /upcoming` returns both cases identically as `available: false` (the schema description says so), so the Android client that schedules local alarms from this list cannot reproduce the server's rule: it will either ring for trashed items or stay silent for items whose module has no resolver yet.
    - Why it matters: SPEC.md §4.6 has the server fire to web and Android schedule from synced reminders; with this contract the two channels can disagree for trashed items. Not blocking for this backend task, but the contract is set now and consumed by the Android task.
    - Suggested fix: Either leave reminders of gone items out of `upcoming` (using `ReminderItems.isGone`), or expose the distinction (e.g. a `gone` flag or an availability enum) in `ReminderItemResponse`, then regenerate `api/openapi.yaml` and the web client.

## Review conclusion

No material issues found. CR-1 and CR-2 of the previous review are resolved on the final revision; the two remaining P3 items are optional improvements.
