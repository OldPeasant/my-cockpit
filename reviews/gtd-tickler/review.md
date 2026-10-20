# Code Review

Date: 2026-10-20

Task: gtd-tickler - Tickler and scheduled tasks job

Scope: `git diff d40fb120f981f9aefe34c7a20fbe14662f5ac748..3c1a6837d3fbe10c5e9f5fdc1c442a24c0e45d2e`, excluding `tasks/` and `reviews/` (board state). Committed changes only.

Requirements:
- `tasks/gtd-tickler.md` acceptance criteria 1-3 and the four recorded assumptions (three confirmed by a human)
- `SPEC.md` §5.1 (Scheduled and Tickler rows, line 229/231), Phase 1 acceptance scenario 5 (line 718)
- `AGENTS.md` "Project notes", `docs/ARCHITECTURE.md` conventions, `docs/QUALITY.md`
- `reviews/gtd-tickler/tests.md` (test-writer report)

Git range:
- Branch: main
- HEAD: 3c1a6837d3fbe10c5e9f5fdc1c442a24c0e45d2e
- Base/start: d40fb120f981f9aefe34c7a20fbe14662f5ac748
- Commits reviewed: 2 (`756ea8f7` tickler and scheduled tasks job with on-read check, `3c1a6837` edge-case tests and test report)
- Uncommitted changes: none at review start

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Commands run

- `git log --oneline d40fb120..3c1a6837` -> the 2 commits above
- `git diff d40fb120..3c1a6837 -- server/src/main api server/src/test/resources` -> read in full; changed source files read in context (`TaskService.kt`, `TaskController.kt`, `GtdRepositories.kt`, `ProjectService.kt`, `ChangeEventPublisher.kt`, `ReminderScheduler.kt`, `TrashPurgeJob.kt`)
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.Tickler*' --tests 'ch.sonensei.cockpit.gtd.UserZoneCronTriggerTests'` (Postgres via Testcontainers, test clock) -> TicklerWebTests 8/8, TicklerEdgeCaseTests 14/14, TicklerJobTests 5/5, UserZoneCronTriggerTests 5/5, 0 failures, 0 errors, 0 skipped (from `build/test-results/test/*.xml`)
- The full `ch.sonensei.cockpit.gtd.*` package and `.taskcheck` were not re-run by the reviewer; `tests.md` records 224/224 and a passing `.taskcheck`.

## Verification notes (no finding)

- AC1: the only task list query is `TaskService.list` (GET /api/v1/tasks); its specification now adds `ticklerDate is null or ticklerDate <= today` (user zone) for open and done lists. Other repository reads (`ProjectService.orderTasks`, trash, context cleanup, item resolver) are not list views. Covered by filter, paging, Logbook and inclusive-date tests.
- AC2: `GtdSchedulingConfiguration` registers one trigger task with `UserZoneCronTrigger` (default `0 5 0 * * *`, zone read for each planning), conditional on `cockpit.gtd.tickler.scheduler.enabled` (off in test config, like the reminder scheduler). `@EnableScheduling` on a module-specific config class matches existing `TrashSchedulingConfiguration` / `ReminderSchedulingConfiguration`. `releaseDue` runs in the class-level `@Transactional` of `TaskService`, moves due ticklers (INBOX) before due SCHEDULED tasks (NEXT, tickler-free only), and publishes `UPDATED` via `changed()`; SSE listeners are `@TransactionalEventListener`, so a rolled-back run delivers nothing. On-read check runs before `list` (separate read-only transaction) and before `get`.
- Concurrency: entities carry `@Version`; a concurrent run or user edit makes the losing transaction fail with an optimistic-lock error, which rolls back the whole batch and is retried on the next read or run (`TicklerJob.run` logs WARN and returns 0, the read still succeeds). A user edit based on the pre-release ETag is rejected (tested). `spring.jpa.open-in-view=false`, so the failed transaction leaves no shared persistence context.
- Invariants: cleared fields keep the existing SCHEDULED/WAITING invariants valid without `validate`; positions follow the existing list/project rules. Behavior matches the confirmed assumptions.
- AC3 / scenario 5: `TicklerWebTests` first test creates the task, sets the tickler to today + 2, checks it is hidden, advances the `MutableClock` 1 day (nothing moved) then 2 days and runs the job -> INBOX with one `UPDATED` event.
- API docs: `listTasks` description updated in the controller and `api/openapi.yaml` consistently. Logging: only counts and exceptions, no task content.
- Module boundaries: changes stay in `gtd.{api,domain,infra}`; the only cross-module dependency is `core.settings.SettingsService`, already used by `TaskService`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | KDoc links to a non-existent `gtd.infra.TicklerJob`**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/TaskService.kt:219`
    - Category: Quality
    - Problem: The `releaseDue` KDoc says it "Runs from [ch.sonensei.cockpit.gtd.infra.TicklerJob]", but `TicklerJob` is in `ch.sonensei.cockpit.gtd.domain` (`TicklerJob.kt:1`). The scheduling lives in `gtd.infra.GtdSchedulingConfiguration`.
    - Why it matters: The link does not resolve and points readers to the wrong package.
    - Suggested fix: Reference `[TicklerJob]` (same package), optionally also `[ch.sonensei.cockpit.gtd.infra.GtdSchedulingConfiguration]`.
    - Disposition: open, for the parent to decide.

- [ ] **CR-2 | `getTask` API description does not mention the on-read release**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/gtd/api/TaskController.kt:166,181`
    - Category: Integration
    - Problem: GET /api/v1/tasks/{id} now runs `tickler.run()` first, so reading a due task can change its list, clear its tickler/scheduled date and bump its version (ETag). The `listTasks` description documents this; `getTask` still only says "One task".
    - Why it matters: Clients (web, Android) relying on the OpenAPI contract are not told that a GET can return a changed version of a task they hold; small contract documentation gap only.
    - Suggested fix: Add a short `description` to the `getTask` `@Operation` (e.g. "A due tickler or scheduled task is moved to its new list first") and regenerate `api/openapi.yaml`.
    - Disposition: open, for the parent to decide.

## Review conclusion

No material issues found. The two P3 findings are optional cleanups; all three acceptance criteria are implemented and backed by tests that the reviewer re-ran (32/32 in the tickler suites on Postgres via Testcontainers).
