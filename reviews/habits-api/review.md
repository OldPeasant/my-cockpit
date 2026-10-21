# Code Review

Date: 2026-10-21

Task: habits-api - Habits API

Scope: task commits 0f5875db and bff07d20 (`git diff ff1970a6..bff07d20`), committed changes only

Requirements:
- tasks/habits-api.md (acceptance criteria and the six ASSUMPTION notes)
- SPEC.md §8.5 (habits), §4.2 (API conventions), §4.6 (reminders)
- docs/ARCHITECTURE.md, AGENTS.md project notes (module boundaries, Problem Details, ETag/If-Match, Flyway layout)
- docs/QUALITY.md (report contract)
- Parent instructions: focus on streak/stats logic and transaction/reminder interactions

Git range:
- Branch: main
- HEAD: bff07d20ee111709c9a50185f9b7c442a1617fae
- Base/start: ff1970a6c3d47ae575d708aebabe0070b24dc6ec (merge-base with origin/main)
- Commits reviewed: 2 (0f5875db, bff07d20)
- Uncommitted changes: none at review start

Commands run:
- `git log --oneline ff1970a6..HEAD`, `git diff --stat ff1970a6..HEAD` -> 2 commits, 28 files, additions only
- `git diff ff1970a6..HEAD -- api/openapi.yaml web/src/app/api/generated/` -> only new `/api/v1/habits*` paths, schemas and client functions; nothing removed
- Read in full: Habit.kt, HabitService.kt, HabitStats.kt, HabitController.kt, HabitDtos.kt, HabitItemResolver.kt, HabitRepositories.kt, the migration, HabitCalendarTests.kt and the setup/reminder parts of HabitWebTests.kt; core context: ReminderService.kt, Reminder.kt, ReminderItems.kt, ReminderScheduler.kt, ReminderController.kt (`onFired` listener), ItemResolver.kt, LinkService/SearchIndexer `onItemDeleted`
- `./gradlew test --tests 'ch.sonensei.cockpit.journal.habits.*' --tests '*OpenApiContractTest' ktlintCheck` -> could not run: execution was denied in the reviewer's sandbox. Test and lint results are therefore not independently confirmed by this review; the parent must rely on its own and the test-writer's runs.

Summary: 0 P0, 0 P1, 1 P2, 2 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | The fired-reminder listener runs inside the core firing batch, so a failure in it rolls back (and re-fires) every reminder in the batch**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/journal/habits/domain/HabitService.kt:190`
    - Category: Correctness
    - Problem: `onReminderFired` is a plain `@EventListener` on a class-level `@Transactional` service, so it joins the transaction of `ReminderService.fireDue()` (which publishes `ReminderFiredEvent` inside its loop, `ReminderService.kt:120`). Any `RuntimeException` in `sync()` (DB error, `reminders.create` -> `HabitItemResolver.exists` joined in the same transaction, `checkNotNull(habit.createdAt)`, settings parsing) propagates out of `publishEvent`, aborts `fireDue` and rolls back the whole batch of up to 100 reminders of all modules. Because the scheduler only logs and retries (`ReminderScheduler.run`), a persistent failure blocks all due reminders. Also, `ReminderController.onFired` is a synchronous `@EventListener` that pushes the SSE notification immediately; if it runs before the habit listener throws, the notification has been sent but `firedAt` is rolled back, so the same reminder is sent again on every 15-second run.
    - Why it matters: core explicitly isolates module code from the firing transaction for exactly this reason (`ReminderItems` KDoc: "a single broken item would roll back a whole firing batch"); this listener reintroduces the coupling, and a habit bug would stop or duplicate unrelated GTD/calendar reminders.
    - Suggested fix: Do not let habit rescheduling share the firing transaction's fate. E.g. use `@TransactionalEventListener(phase = AFTER_COMMIT)` with `@Transactional(propagation = REQUIRES_NEW)` for the resync (the fired reminder is already committed then), or at least run it in a `REQUIRES_NEW` transaction and catch/log exceptions. Add a test where the habit sync fails and another module's reminder in the same batch is still marked fired.

## P3 Low

- [ ] **CR-2 | `sync()` deletes every pending reminder of a habit, including ones the user created through `/api/v1/reminders`**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/journal/habits/domain/HabitService.kt:224`
    - Category: Correctness
    - Problem: `sync` takes all pending reminders of the `HABIT` item and, unless they equal exactly `[wanted]`, deletes all of them. The generic reminders API accepts reminders for any item type (`ReminderService.create`), so a reminder a user added to a habit by hand is silently deleted on the next check, habit edit or habit reminder firing, also for habits without `reminderTime`.
    - Why it matters: silent loss of a user-created reminder; the "exactly one pending reminder" assumption is not visible to API clients of `/reminders`.
    - Suggested fix: either only manage the reminder the module owns (e.g. delete/replace only the pending reminder at the previously computed habit time, or skip syncing when `reminderTime` is null), or document on the habits and reminders API that habit reminders are module-managed, and record that as an assumption.

- [ ] **CR-3 | Every sync loads the habit's full fired-reminder history**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/journal/habits/domain/HabitService.kt:224`
    - Category: Quality
    - Problem: `reminders.ofItem(ref)` returns all reminders of the habit, fired or not, and resolves them (in a separate `REQUIRES_NEW` transaction via `ReminderItems`), then filters `firedAt == null` in memory. A habit with a reminder time leaves one fired row per day forever, so each check change and each firing reads a list that grows by ~365 rows per year per habit.
    - Why it matters: unbounded growth on a hot path (one-tap check); avoidable work.
    - Suggested fix: use (or add in core) a query for pending reminders of an item only, without resolving display data.

## Review conclusion

Material issues found; fix before completing the task.

The acceptance criteria are addressed in code: CRUD for the three schedules with target count and reminder time synced to core reminders, If-Match on PATCH/DELETE with Problem Details, checks per date, day and week streaks (week start from settings, tests across week and month boundaries and a DST switch), 30-day completion, the HABIT resolver, search indexing, hard delete via `ItemDeletedEvent`, and an additive regeneration of `api/openapi.yaml` and the web client. The migration path deviation from `touches` is recorded as an assumption and follows the migration convention. Backup coverage belongs to the separate `backup-contrib-journal` task. CR-1 should be fixed before `done`; CR-2 and CR-3 may be fixed or recorded with a disposition. This reviewer could not execute the test suite or lint (sandbox denied), so criterion 3 and 4 evidence must come from the parent's own runs.
