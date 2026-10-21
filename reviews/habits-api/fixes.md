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
