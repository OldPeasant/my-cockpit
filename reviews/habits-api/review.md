# Code Review

Date: 2026-10-21

Task: habits-api - Habits API

Scope: full task diff `git diff a075af7e..HEAD` (committed changes only, `reviews/` and `tasks/` excluded), with a focus on the fixes for the previous review's CR-1..CR-3 (b784ec85 in the parent's numbering, local commit 4f140629) and the gtd convert test change (f10f4a34, local 45984d18)

Requirements:
- tasks/habits-api.md (four acceptance criteria and the eight ASSUMPTION notes)
- SPEC.md §8.5 (habits), §4.2 (API conventions), §4.6 (reminders)
- docs/ARCHITECTURE.md and AGENTS.md project notes (module boundaries, Problem Details, ETag/If-Match, Flyway layout, logging)
- reviews/habits-api/review.md (previous review) and reviews/habits-api/fixes.md
- Parent instructions: verify the AFTER_COMMIT + REQUIRES_NEW resync, the habit.reminder_id ownership with a bulk update that does not touch the version, and ReminderService.pendingOfItem

Git range:
- Branch: main
- HEAD: 45984d1823eca296f6856ad6bc360bb4f92772b6
- Base/start: a075af7e985e4a3aeb5589308d6854cf56dd0902 (ancestor of HEAD; the parent named it as the task base)
- Commits reviewed: 10 in `a075af7e..HEAD`, 5 of them code commits (e786e5f1, 2cfb793d, 71e108ea, 4f140629, 45984d18); the rest only touch `tasks/` (assumptions). The parent's hashes (f14ea014 ... f10f4a34) belong to the same commits before a rebase; the subjects match.
- Uncommitted changes: none at review start

Commands run:
- `git log --oneline a075af7e..HEAD`, `git diff --stat a075af7e..HEAD -- . ':!reviews' ':!tasks'` -> 37 files, server code, two migrations, tests, regenerated `api/openapi.yaml` and web client
- `git diff a075af7e..HEAD -- server/src/main/kotlin/ch/sonensei/cockpit/core` -> only additions: `ReminderRepository.findByItemTypeAndItemIdAndFiredAtIsNullOrderByRemindAtAscIdAsc` and `ReminderService.pendingOfItem`
- `git diff a075af7e..HEAD -- server/src/test/kotlin/ch/sonensei/cockpit/gtd` -> the four gtd convert tests create a real habit through `HabitService` instead of a random HABIT id, and clean `habit`/`habit_check`; the assertions are unchanged except the test name
- Read in full: Habit.kt, HabitService.kt, HabitStats.kt, HabitController.kt, HabitDtos.kt, HabitItemResolver.kt, HabitRepositories.kt, both migrations, HabitEdgeCaseTests.kt (reminder and fix tests); core context: ReminderService.kt, ReminderItems.kt, ItemResolver.kt
- `./gradlew test --tests 'ch.sonensei.cockpit.journal.habits.*'` -> could not run: execution was denied in the reviewer's sandbox, and no earlier test result files exist in `server/build/test-results`. This review does not confirm test or lint results itself; criteria 3 and 4 need the parent's and the test-writer's own runs.

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Verification of the previous findings

- CR-1 (firing batch coupling): fixed. `HabitService.onReminderFired` (HabitService.kt:207-219) is a `@TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)` with `@Transactional(propagation = NOT_SUPPORTED)`, which Spring accepts on such a listener. The resync runs in a `TransactionTemplate` with `PROPAGATION_REQUIRES_NEW`. The try/catch sits outside the template, so a rollback-only inner transaction is caught too. The WARN log names only the habit id. The listener only runs after `fireDue` committed, so a habit failure can no longer roll back or re-fire other modules' reminders. The test `a failing habit reminder move neither rolls back nor stops the firing batch` (HabitEdgeCaseTests.kt:230) covers this with a habit row that cannot be loaded.
- CR-2 (user reminders deleted): fixed. `habit.reminder_id` (new migration V202610212300, a new file, flat `journal/` folder like the first migration, recorded as an assumption) records the managed reminder. `sync` (HabitService.kt:249-255) deletes or replaces only that reminder. The entity maps the column `insertable = false, updatable = false` with a protected setter. `HabitRepository.setReminderId` is a JPQL bulk update without `versioned`, so the habit's version (ETag) does not change and a concurrent PATCH is not overwritten. Tests: `reminders the user added to a habit are left alone` and `moving the habit's reminder does not change its version`.
- CR-3 (full reminder history loaded): fixed. `ReminderService.pendingOfItem` returns only pending reminders and resolves no display data. It is called only when the habit has a recorded reminder. The habits module still reaches core only through `ReminderService`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Concurrent reminder syncs of the same habit are not serialized: spurious 404 or a duplicate pending reminder**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/journal/habits/domain/HabitService.kt:249`
    - Category: Correctness
    - Problem: Only `setCheck` locks the habit row (`lockById`). `update` (`load`, line 128) and `onReminderFired` (`habits.findById`, line 214) read the habit without a lock and then run `sync`, which reads `habit.reminderId` and the pending reminders and then deletes and creates reminders. Two overlapping syncs of the same habit (a PATCH from one device while a check comes from another, or a check while the fired-reminder resync runs) can both see the same managed reminder. The second `reminders.delete(id)` then finds 0 rows and throws `NotFoundException("Reminder not found")` (ReminderService.kt:84), and the PATCH or check fails with a 404 that has nothing to do with the habit. If neither sees a pending managed reminder (it just fired), both create one, and `setReminderId` keeps only the last id. The other reminder is no longer managed, so it fires as a duplicate notification, also after the habit was archived or its reminder time was cleared.
    - Why it matters: the timing is rare and the state heals after the orphan fires, but the "exactly one managed reminder" invariant can break, and an unrelated 404 is confusing for clients. The intent is clearly to serialize changes per habit, because `setCheck` already locks for that.
    - Suggested fix: load the habit with `habits.lockById(id)` in `update` and in `onReminderFired` (and `delete` for consistency), so every path that ends in `sync` holds the same row lock. Optionally make `sync` tolerate an already deleted managed reminder instead of propagating the 404.

## Review conclusion

No material issues found.

All four acceptance criteria are addressed in the code. Habit CRUD covers DAILY, TIMES_PER_WEEK and WEEKDAYS with target count, and the reminder time is synced to one core reminder. PATCH and DELETE check If-Match, and errors are Problem Details. Checks are stored per date. Streaks are day or week streaks (week start from settings), and 30-day completion is computed. The HABIT resolver exists, habit names are indexed for search, and a hard delete publishes `ItemDeletedEvent`. Tests cover week, year and DST boundaries. `api/openapi.yaml` and the web client were regenerated, and the change only adds to them. CR-1..CR-3 of the previous review are fixed as described above. The remaining CR-1 is low priority and can be fixed or recorded with a disposition. This reviewer could not run the test suite (sandbox denied), so the evidence for criteria 3 and 4 must come from the parent's own runs.
