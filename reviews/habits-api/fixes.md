# Fix Report

Date: 2026-10-21

Task: habits-api - Habits API

Scope: findings CR-1, CR-2, CR-3 of `reviews/habits-api/review.md`. The task's base commit is 6365c9a9, its commits are 0239c0ce, 2d7c239e and d344c9dc (HEAD). The review names the hashes from before the rebase, but the code is the same. These fixes are uncommitted working-tree changes on top of d344c9dc.

## Fixed findings

### CR-1 - The fired-reminder listener runs inside the core firing batch

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/journal/habits/domain/HabitService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/journal/habits/HabitEdgeCaseTests.kt`

Problem:
`onReminderFired` was a plain `@EventListener` on a `@Transactional` service, so it ran inside the transaction of `ReminderService.fireDue()`. If one habit's resync threw, the whole firing batch was rolled back, for every module. The batch was then fired again on each scheduler run, and SSE notifications went out more than once.

Fix:
`onReminderFired` is now a `@TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)` with `@Transactional(propagation = NOT_SUPPORTED)`. Spring allows only REQUIRES_NEW or NOT_SUPPORTED on such a listener. Its body loads the habit and runs the resync in a `TransactionTemplate` set to `PROPAGATION_REQUIRES_NEW`. The try/catch sits outside that template, so it also catches the case where an inner joined call marks the transaction rollback-only. A failure is logged at WARN with the habit id only, no name or other content. The next habit change or check moves the reminder again.

New test: `a failing habit reminder move neither rolls back nor stops the firing batch`. Two habits have due reminders in the same batch. One habit row is corrupted so that every load of it throws. `fireDue()` returns 2, both reminders are marked fired, and the healthy habit gets its next reminder. With the old listener put back temporarily, this test fails. With the fix, it passes.

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.journal.habits.HabitEdgeCaseTests'` with the old `@EventListener` restored temporarily: Fail (1 of 20, the new test, as expected)
- `./gradlew ktlintFormat ktlintCheck test --tests 'ch.sonensei.cockpit.journal.habits.*' --tests '*Reminder*'` with the fix: Pass

### CR-2 - `sync()` deletes all pending reminders of the habit, including ones the user created

Files changed:
- `server/src/main/resources/db/migration/journal/V202610212300__journal_habits_reminder_id.sql` (new: `alter table habit add column reminder_id uuid`)
- `server/src/main/kotlin/ch/sonensei/cockpit/journal/habits/domain/Habit.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/journal/habits/infra/HabitRepositories.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/journal/habits/domain/HabitService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/journal/habits/HabitEdgeCaseTests.kt`

Problem:
`sync()` deleted every pending reminder of the HABIT item unless the pending list matched the computed one exactly. That included reminders the user had added through `/api/v1/reminders`.

Fix:
The module now records the one reminder it owns in `habit.reminder_id`, and `sync()` only moves or deletes that reminder. Once it has fired or is gone, `sync()` creates a new one. Other reminders of the habit are never touched. I chose an id column over matching reminders by time of day because time matching breaks when the time zone changes: the old reminder no longer matches and stays behind as a duplicate, which the existing test `a time zone change applies to today and to the next reminder move` would catch.

The entity maps the column read-only (`insertable/updatable = false`). It is written with a JPQL bulk update, `HabitRepository.setReminderId`. That way, moving a reminder does not bump the habit's version (ETag), so clients don't get a 412 after a check or a firing. It also cannot overwrite a concurrent PATCH of the habit. The migration is a new file and does not edit the applied one. It sits in `db/migration/journal/`, like the task's existing migration (see the task's migration assumption).

New tests:
- `reminders the user added to a habit are left alone`: a user reminder survives a check, a firing and clearing the reminder time.
- `moving the habit's reminder does not change its version`: a PATCH with the ETag from before a check still succeeds.

Validation:
- `./gradlew ktlintFormat ktlintCheck test --tests 'ch.sonensei.cockpit.journal.habits.*' --tests '*Reminder*'`: Pass

### CR-3 - Every sync loads the habit's full reminder history

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/reminders/infra/ReminderRepository.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/core/reminders/domain/ReminderService.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/journal/habits/domain/HabitService.kt`

Problem:
`sync()` used `ReminderService.ofItem`. That loads all reminders of the habit, fired ones included, and resolves display data for each in a separate transaction. Then it filtered for pending ones in memory. The list grows by one row per day per habit.

Fix:
A small change in core that only adds code: the derived query `findByItemTypeAndItemIdAndFiredAtIsNullOrderByRemindAtAscIdAsc` and `ReminderService.pendingOfItem(item): List<Reminder>`, which returns only pending reminders and resolves no display data. The habits module still talks to core only through `ReminderService`. `sync()` calls `pendingOfItem` only when the habit has a recorded managed reminder.

Validation:
- `./gradlew ktlintFormat ktlintCheck test --tests 'ch.sonensei.cockpit.journal.habits.*' --tests '*Reminder*'`: Pass (the core reminder suites are included and unchanged)

## Not fixed

None. All three findings were valid and in scope. The core change for CR-3 and its scope were approved by the parent.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat ktlintCheck test --tests 'ch.sonensei.cockpit.journal.habits.*' --tests '*Reminder*'`: BUILD SUCCESSFUL. 13 suites, 122 tests, 0 failures. HabitEdgeCaseTests now has 20 tests, 3 of them new.
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.journal.habits.*' --tests '*Reminder*' --tests '*MigrationConvention*' --tests '*OpenApiContractTest'`: BUILD SUCCESSFUL. This checks that the new migration follows the naming convention and that the API contract did not change; the DTOs are unchanged.

Full test suite:
- Run as part of `.taskcheck`, see below.

Lint/type-check/build:
- `./gradlew ktlintCheck`: Pass. Compiling main and test code: Pass.

`.taskcheck`:
- `sh scripts/check.sh`: Fail. 3682 server tests, 4 failed, all in `ch.sonensei.cockpit.gtd.convert`: `TaskToNoteRollbackTests`, `TaskToNoteWebTests`, `TaskToEventEdgeCaseTests` and `TaskToEventRollbackTests`. These tests use `ItemType.HABIT` as an item type that has no resolver, i.e. a link target that is kept but cannot be resolved. The HABIT resolver added by this task (commit 0239c0ce, `HabitItemResolver`) breaks that premise: a random habit id now resolves as missing, so its link is dropped or the expected failure does not happen. These fixes do not cause the failures, since they change neither resolvers nor links. The failures are not among the supplied findings and are left to the parent worker. A likely fix is to use an item type that still has no resolver in those gtd tests. That would be a change outside this task's `touches`.

Remaining unresolved findings:
0 of the supplied findings. One new blocker for `.taskcheck` is described above.

## Final status

Fixes completed, but validation still fails: all validated findings are fixed and the focused validation passes, but `.taskcheck` fails on 4 gtd convert tests. The HABIT resolver from the original implementation causes those failures; these fixes do not.

# Round 2

Date: 2026-10-21

Task: habits-api - Habits API

Scope: finding CR-1 (P3) of the second code review in `reviews/habits-api/review.md`. Base commit ca4eeffa, task commits 8d287f7d..19251878 (HEAD). Changes are limited to `server/.../journal/habits` (main and test) and are uncommitted working-tree changes on top of 19251878.

## Fixed findings

### CR-1 — Concurrent reminder syncs of the same habit are not serialized

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/journal/habits/domain/HabitService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/journal/habits/HabitReminderOwnershipTests.kt`

Problem:
Only `setCheck` locked the habit row. `update`, `delete` and `onReminderFired` read the habit without a lock and then ran `sync`. Two overlapping syncs of the same habit could both see the same managed reminder: the second `ReminderService.delete` threw a 404, or both created a reminder and one became an unmanaged duplicate.

Fix:
A new private helper `lock(id)` calls `habits.lockById(id)` (PESSIMISTIC_WRITE, the lock `setCheck` already used) and throws `NotFoundException` when the habit is missing. `update`, `delete` and `setCheck` use it. `onReminderFired` calls `habits.lockById` inside its REQUIRES_NEW `TransactionTemplate`, so the lock is held by that isolated transaction. Every path that ends in `sync` now takes the same row lock. `create` is unchanged because a new habit cannot be seen by other transactions. The If-Match check still runs on the locked entity's version, which is the latest committed version once the lock is acquired. The existing 412 tests in HabitWebTests and HabitEdgeCaseTests pass.

The optional part was not applied: `sync` does not catch a `NotFoundException` from `ReminderService.delete`. That method is `@Transactional` and joins the caller's transaction. When a RuntimeException leaves it, Spring marks the shared transaction rollback-only, so catching it in `sync` would turn the 404 into an `UnexpectedRollbackException` at commit. A safe version needs a non-throwing delete in core, which is outside this scope. With the row lock, two habit syncs can no longer cause the double delete. The only remaining case is a user deleting the managed reminder through the reminders API in the short window between `pendingOfItem` and `delete` of the same sync. That gives a 404, and a retry succeeds.

Tests added in HabitReminderOwnershipTests:
- `a habit change waits for the row lock another change of the habit holds and sees its version`: another transaction holds `select ... for update` on the habit and bumps its version. `HabitService.update` must not finish while that lock is held. After the lock is released, its `checkVersion` sees the bumped version and the reminder moves to the new time.
- `concurrent changes and checks of the same habit keep exactly one managed reminder`: 5 rounds of 4 threads mix `update` (each call moves the reminder) and `setCheck`. No call fails, and exactly one pending reminder exists, which is the managed one.
- Both tests failed with `ObjectOptimisticLockingFailureException` when `update` was temporarily switched back to the unlocked `load(id)`. They pass with the fix. The temporary revert was undone, and the final diff contains only the fix.
- The existing test `after its reminder fired the habit names the next pending one` covers `lockById` in `onReminderFired`. A pessimistic lock outside a transaction would fail with TransactionRequiredException, the listener would only log it, and the assertions on the next reminder would fail.

Validation:
- `cd server && ./gradlew test --tests '*HabitReminderOwnershipTests'` with `update` temporarily unlocked: 9 tests, 2 failed (expected; this shows the new tests catch the bug)
- `cd server && ./gradlew ktlintFormat ktlintCheck test --tests 'ch.sonensei.cockpit.journal.habits.*' --tests '*Reminder*'`: Pass

## Not fixed

None. The optional "tolerate an already deleted managed reminder" part is explained above. It is not a separate finding.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat ktlintCheck test --tests 'ch.sonensei.cockpit.journal.habits.*' --tests '*Reminder*'`: BUILD SUCCESSFUL. 15 suites, 133 tests, 0 failures, 0 errors. HabitReminderOwnershipTests now has 9 tests, 2 of them new.

Full test suite:
- Not run by the fixer. Left to the parent worker's `.taskcheck` run.

Lint/type-check/build:
- `./gradlew ktlintFormat ktlintCheck`: Pass. Main and test code compile.

`.taskcheck`:
- Not run by the fixer.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed, for the focused suites. `.taskcheck` is left to the parent worker.
