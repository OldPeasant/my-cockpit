# Code Review

Date: 2026-10-20

Task: gtd-tickler - Tickler and scheduled tasks job (re-review of the final revision after fixes)

Scope: `git diff 4d86a3771f810407062f496857260f99d8a13dc5..ce9907782cc99a0f0ebc8a0ff43405b13c50e3fc`, all task commits; `tasks/` and `reviews/` read as context only. Committed changes only (the working tree had only an uncommitted `reviews/gtd-tickler/tests.md` update by the test-writer, not reviewed as code).

Requirements:
- `tasks/gtd-tickler.md` acceptance criteria 1-3 and the four recorded assumptions (three confirmed by a human)
- `SPEC.md` §5.1 (Scheduled and Tickler rows)
- `AGENTS.md` "Project notes", `docs/ARCHITECTURE.md` conventions, `docs/QUALITY.md`
- Previous review findings CR-1, CR-2 and `reviews/gtd-tickler/fixes.md`

Git range:
- Branch: main
- HEAD: ce9907782cc99a0f0ebc8a0ff43405b13c50e3fc
- Base/start: 4d86a3771f810407062f496857260f99d8a13dc5
- Commits reviewed: 4 (`4d181e2c` implementation, `c5da2943` edge-case tests and test report, `24714dcb` review report, `ce990778` review fixes)
- Uncommitted changes: `reviews/gtd-tickler/tests.md` only (no code)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Commands run

- `git log --oneline 4d86a377..HEAD`, `git diff --stat 4d86a377..HEAD` -> 4 commits, 14 files
- `git diff 4d86a377..HEAD -- server/src/main api server/src/test/resources` -> read in full; `TaskService.kt`, `ProjectService.kt` (views/next-task lookup), `GtdRepositories.kt` and the task-reading call sites (`grep` for `TaskRepository` use) read in context
- Own run of `./gradlew test --tests 'ch.sonensei.cockpit.gtd.*' --tests '*OpenApiContractTest'` was aborted by a concurrent Gradle run in the same clone (`NoSuchFileException` in `build/test-results`, a build-dir clash, not a test failure). The JUnit XML results that the concurrent run left in `server/build/test-results/test/` (timestamps 2026-10-20T19:19-19:20Z, HEAD `ce990778`, clean code tree) were read instead: 160 classes, 1857 tests, 0 failures, 0 errors, 0 skipped; TicklerWebTests 8/8, TicklerEdgeCaseTests 14/14, TicklerJobTests 5/5, UserZoneCronTriggerTests 5/5, OpenApiContractTest 1/1.

## Fix confirmation

- CR-1 (KDoc link): `TaskService.kt` `releaseDue` KDoc now links `[TicklerJob]`, which is in the same package (`gtd.domain`). Resolved.
- CR-2 (getTask description): `TaskController.kt` `getTask` `@Operation` now has a description of the on-read release (list, dates and ETag may change); `api/openapi.yaml` has the same text on `get /api/v1/tasks/{id}`, and `OpenApiContractTest` passes against the committed spec. Resolved.

## Verification notes (no finding)

- AC1: `TaskService.specification` adds `ticklerDate is null or ticklerDate <= today` (user zone) to every `list` query, open and Logbook. The other `TaskRepository` reads (project ordering, project stalled check via `nextTaskId`, trash, context cleanup, item resolver) are not list views.
- AC2: `GtdSchedulingConfiguration` registers `TicklerJob.run` with `UserZoneCronTrigger` (default `0 5 0 * * *`, zone reread on each planning), disabled in tests via `cockpit.gtd.tickler.scheduler.enabled=false`. `releaseDue` runs in `TaskService`'s class-level transaction, moves due ticklers to INBOX and then tickler-free due SCHEDULED tasks to NEXT, and publishes `UPDATED` through `changed()`. The on-read check runs before `list` and `get`; `TicklerJob.run` logs and swallows failures so reads do not fail, as the confirmed assumption says. Field clearing keeps the existing SCHEDULED/WAITING rules valid.
- AC3: `TicklerWebTests` "a task in a tickler is hidden from every list, and two days later the job puts it back in the Inbox" uses the `MutableClock` (advances 1 day then 1 more day) on Postgres via Testcontainers; it passes in the results above.
- Module boundaries and logging: changes stay in `gtd.{api,domain,infra}`. Logs hold only counts and exceptions, never task content. No migrations, dependencies or security-relevant configuration changed.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. Both earlier P3 findings (CR-1, CR-2) are fixed in `ce990778`. No new findings in the final revision.
