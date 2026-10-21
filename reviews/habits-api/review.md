# Code Review

Date: 2026-10-21

Task: habits-api - Habits API

Scope: full task diff `git diff ca4eeffa..HEAD` (committed changes only, `tasks/` and `reviews/` excluded), with a focus on the round 2 fix commit bbdb3a69 (row lock on every reminder sync path, previous review's P3 CR-1)

Requirements:
- tasks/habits-api.md (four acceptance criteria and the ASSUMPTION notes)
- SPEC.md §8.5 (habits), §4.2 (API conventions), §4.6 (reminders)
- docs/ARCHITECTURE.md and AGENTS.md project notes (module boundaries, Problem Details, ETag/If-Match, Flyway layout, logging)
- reviews/habits-api/review.md (round 2) and reviews/habits-api/fixes.md "Round 2"
- Parent instructions: verify that If-Match is still checked after the lock, and that `onReminderFired` takes the lock inside the REQUIRES_NEW template

Git range:
- Branch: main
- HEAD: bbdb3a69056ebfd8f3cee0a9ee12f44f0194fba5
- Base/start: ca4eeffa32bea589fa93118f91ec336943a232cd
- Commits reviewed: 7 (8d287f7d, e3d3b7f5, fc398ef4, 2330f272, 4979ec13, 19251878, bbdb3a69)
- Uncommitted changes: none at review start

Commands run:
- `git log --oneline ca4eeffa..HEAD`, `git diff --stat ca4eeffa..HEAD -- . ':!tasks'` -> 41 files: habits module (domain, api, infra), two Flyway migrations in `db/migration/journal/`, additive `ReminderService.pendingOfItem` and its repository query in core, tests, regenerated `api/openapi.yaml` and web client, four gtd convert tests switched to a real habit
- `git show bbdb3a69` -> `update`, `delete`, `setCheck` use a new `lock(id)` (`habits.lockById`, PESSIMISTIC_WRITE); `onReminderFired` calls `habits.lockById` inside `isolated.executeWithoutResult`; two new concurrency tests in HabitReminderOwnershipTests
- Read in full: HabitService.kt, HabitStats.kt, Habit.kt, HabitController.kt, HabitRepositories.kt, HabitItemResolver.kt, both migrations, the core diff, the new tests
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.journal.habits.*' --tests '*ReminderPendingOfItemTests' --tests '*OpenApiContractTest' --tests 'ch.sonensei.cockpit.gtd.convert.*'` (run twice) -> BUILD FAILED both times with `NoSuchFileException: server/build/test-results/test/binary/in-progress-results-generic.bin`. Another Gradle test run was active on the same build directory at the same time (other Gradle test JVMs were running), so this is a collision, not a test failure. The existing result files in `server/build/test-results/test/` (written 22:29, before the commit at 22:30 and not by this reviewer) show HabitCalendarEdgeCaseTests 14, HabitCalendarTests 18, HabitEdgeCaseTests 20, HabitReminderOwnershipTests 9 (both new concurrency tests included), HabitWebTests 18, ReminderPendingOfItemTests 2, all with 0 failures and 0 errors. This reviewer did not run the suite successfully. Evidence for criteria 3 and 4 must come from the parent's own run on the final code.

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Verification of the round 2 fix (previous CR-1)

- Fixed. Every path that ends in `sync` now holds the habit row lock: `update` (HabitService.kt:129), `delete` (:142), `setCheck` (:167) and `onReminderFired` (:215). `create` needs no lock, because other transactions cannot see the new row yet.
- If-Match is still checked. `update` and `delete` call `checkVersion(habit.version)` right after `lock(id)`. Each call runs in a new transaction, and `lockById` is a JPQL query with `@Lock(PESSIMISTIC_WRITE)` (`select ... for update`), so it returns the version committed when the lock is granted. A stale ETag still gives 412. The test `a habit change waits for the row lock ... and sees its version` checks this: it bumps the version under a lock it holds and asserts that `checkVersion` sees `version + 1`.
- The lock is taken inside the REQUIRES_NEW template. In `onReminderFired`, `habits.lockById(...)` runs in the `isolated.executeWithoutResult` lambda. The method itself is `NOT_SUPPORTED`, and the try/catch stays outside the template, so a failure (including a rollback-only state) is only logged with the habit id. No lock is taken outside a transaction, which would fail with TransactionRequiredException.
- No new deadlock order. The firing batch commits before the AFTER_COMMIT listener runs. Habit paths lock the habit row first and then touch reminder rows. Core reminder code never locks habit rows.
- `setReminderId` remains a bulk update without a version bump, so a lock holder's later `saveAndFlush` keeps the version check intact. The stale in-memory `reminderId` after the bulk update is not read again in the same transaction (sync runs once per call) and is not exposed in the DTO.

## Rest of the final code

The earlier findings of rounds 1 and 2 stay fixed: AFTER_COMMIT/REQUIRES_NEW resync, a single managed reminder through `habit.reminder_id`, and the pending-only `pendingOfItem` query. The habits module uses core only through `ReminderService`, `SearchIndexer`, `ChangeEventPublisher`, `SettingsService` and `ItemDeletedEvent`. Migrations are new files in the flat `journal/` folder, and that deviation is recorded as an assumption. Errors are Problem Details. PATCH and DELETE need If-Match (409, 412 and 428 are documented). The streak and completion logic matches the recorded assumptions. The HABIT resolver exists. The OpenAPI and web client changes only add to the existing files.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Checks have no lower date bound, so one very old check makes every stats computation iterate from that date**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/journal/habits/domain/HabitService.kt:163` (validation), `server/src/main/kotlin/ch/sonensei/cockpit/journal/habits/domain/HabitStats.kt:55` and `:109`/`:126` (iteration)
    - Category: Correctness
    - Problem: `setCheck` rejects only dates after today. `HabitCalendar.first` is the earliest of the creation date and all check dates, and `dayStreaks` / `weekStreaks` build a list of every day (or week) from `first` to today. A check such as `PUT /habits/{id}/checks/0001-01-01` (valid ISO date) makes `first` about 740,000 days back. Each `get`, `list` (once per habit), `update` and `setCheck` of that habit then allocates and filters lists of that size several times. The check also stays outside the 3660-day range of `GET /checks`, so the client cannot see or remove it there.
    - Why it matters: one mistyped year (a client bug or a typo) slows every habit list permanently and leaves a check the UI cannot list. It is single-user and not a crash, so the priority is low.
    - Suggested fix: reject check dates long before today in `setCheck`, for example more than `MAX_RANGE_DAYS` back or before a fixed floor, with a 400 field error, and add a test.

## Review conclusion

No material issues found.

The round 2 fix is correct. The row lock covers every sync path, If-Match is still checked on the locked, current version, and the listener takes the lock inside its own REQUIRES_NEW transaction. The only remaining finding is the P3 above. It can be fixed or recorded with a disposition. This reviewer's own test run failed because another Gradle run was using the same build directory. The parent must record criteria 3 and 4 from its own successful run of the habits suite and of `OpenApiContractTest` on HEAD.
