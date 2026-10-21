# Test Report

Date: 2026-10-21

Task: habits-api - Habits API

Scope: `git diff ca4eeffa32bea589fa93118f91ec336943a232cd..bbdb3a69056ebfd8f3cee0a9ee12f44f0194fba5` on `main` (task commits 8d287f7d..bbdb3a69, final code including the row-lock fix bbdb3a69 for review 2 CR-1). The parent supplied the base and the commit range. No uncommitted production or test changes existed at measurement time. The only working-tree change was `reviews/habits-api/review.md`, which the concurrently running code-reviewer wrote. It is not a test input.

Requirements:
- `tasks/habits-api.md` (4 acceptance criteria, 8 ASSUMPTION notes: streak rules, 30-day window, reminder ownership, checks API, hard delete)
- SPEC.md §8.5 (habits), §4.2 (API conventions)
- docs/ARCHITECTURE.md "Coverage", AGENTS.md
- Parent instructions (test command without UPDATE_OPENAPI, JaCoCo for `journal.habits` and changed lines)

## Test files changed

- None. The existing test suite already covers every criterion. See "Requirement coverage" and "Coverage" below.

## Tests added or updated

Count: 0

I reviewed the existing tests against every acceptance criterion and assumption. They are HabitWebTests (18), HabitCalendarTests (18), HabitCalendarEdgeCaseTests (14), HabitEdgeCaseTests (20), HabitReminderOwnershipTests (9) and ReminderPendingOfItemTests (2). Every remaining uncovered location is defensive or documentation-only code (see the gap table). No meaningful behavior was left untested, so I added no tests just to raise the percentage.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Habit CRUD: create/defaults/ETag/Location, validation, PATCH with If-Match, list active/archived by name, GET 404, hard DELETE with checks/reminders/search entry | HabitWebTests: `POST creates a daily habit...`, `POST validates schedule fields...`, `fields that do not fit the schedule are cleared...`, `PATCH changes only the fields sent...`, `GET lists active habits by name...`, `DELETE removes the habit for good...`; HabitEdgeCaseTests: `PATCH with null resets...`, `PATCH rejects clearing required fields...`, `limits are inclusive at their maximum`, `DELETE with a stale If-Match is refused...` | focused command (below) | Pass |
| Schedules daily / N per week / weekdays, target count | HabitCalendarTests (18), HabitCalendarEdgeCaseTests (`seven times per week...`, `a weekday habit on all seven days...`, `a count above the target...`) | focused command | Pass |
| Reminder time -> one managed core reminder (next open due day, moves on change/check/fire, archive/clear removes it, user reminders untouched, version unchanged, DST/time zone, row lock) | HabitWebTests reminder tests (5); HabitEdgeCaseTests `a reminder time in the spring-forward gap...`, `...switch back to winter time`, `a time zone change applies...`, `a weekly reminder moves...`, `a fired reminder moves only...`, `a failing habit reminder move neither rolls back nor stops the firing batch`, `reminders the user added...`, `moving the habit's reminder does not change its version`; HabitReminderOwnershipTests (9, incl. `a habit change waits for the row lock...`, `concurrent changes and checks of the same habit keep exactly one managed reminder`); ReminderPendingOfItemTests (2) | focused command | Pass |
| Checks per date (PUT absolute count, 0 removes, max 1000, future date 400, unknown fields 400, GET range default 30 days, max 3660 days, concurrent checks) | HabitWebTests `PUT check sets the day's count...`, `PUT check rejects future dates...`, `GET checks lists a date range...`; HabitEdgeCaseTests `PUT check rejects unknown fields...`, `removing a check that does not exist...`, `GET checks accepts a single day and at most 3660 days`, `concurrent checks of the same habit and date never insert twice` | focused command | Pass |
| Streaks, including across week boundaries | HabitWebTests `weekly streak runs across week boundaries`, `daily streak runs across week boundaries and an open today does not break it`; HabitCalendarTests `daily streak runs across week and month boundaries`, `weekly streak counts weeks that reached the quota, across week boundaries`, `the week start setting decides...`; HabitCalendarEdgeCaseTests `weekly streak runs across the year boundary...`, `daily streak runs across a leap day and a daylight saving switch`, `the longest streak is kept...` | focused command | Pass |
| 30-day completion stats | HabitCalendarTests `completion of the last 30 days...`, `completion does not count days before the habit existed`, `a check before the creation day moves the start back`, `a new habit with nothing due yet has no completion rate`, `weekly completion expects the quota per 7 days, capped at 1`, `weekday completion counts only scheduled days`; HabitCalendarEdgeCaseTests window/rounding tests (3); HabitEdgeCaseTests `a check before the creation day counts in the stats`, `the list computes each habit's stats from its own checks` | focused command | Pass |
| Resolver HABIT | HabitWebTests `resolver HABIT describes the habit and its schedule`, `habits can be linked to other items`, `GET reminders of a habit shows the habit's name` | focused command | Pass |
| `./gradlew test --tests 'ch.sonensei.cockpit.journal.habits.*'` passes (Postgres via Testcontainers) | all 79 tests in `journal.habits` | focused command | Pass (79/79) |
| `OpenApiContractTest` passes without UPDATE_OPENAPI | `OpenApiContractTest` | focused command, UPDATE_OPENAPI unset | Pass (1/1) |
| Regenerated web client is up to date | `api:check` step of `npm run check` ("src/app/api/generated is up to date.") | `sh scripts/check.sh` | Pass |

## Focused test execution

Command:

`cd server && ./gradlew cleanTest test --tests 'ch.sonensei.cockpit.journal.habits.*' --tests '*Reminder*' --tests '*OpenApiContractTest' jacocoTestReport --console=plain -q` (UPDATE_OPENAPI not set)

Result:

Pass: 134 tests, 0 failures, 0 errors, 0 skipped (journal.habits 79, core.reminders 54 incl. ReminderPendingOfItemTests 2, OpenApiContractTest 1). Exit 0, 2026-10-21 22:32:30-22:33:07 +0200.

Note: two earlier attempts of the same command (22:30, 22:31) failed with `java.io.EOFException` in `Test.getPreviousFailedTestClasses`. The parent worker was running Gradle in the same `server/build` at the same time, and the overlap left a truncated binary test-result store (`build/test-results/test/binary`). Gradle's own `cleanTest` task reset that store. No test failed. It was a corrupted build artifact, not a test result. Advice for the parent: don't run Gradle in this clone while a helper does.

## Full test execution

Command:

`sh scripts/check.sh` (= `.taskcheck`, includes `cd server && ./gradlew --quiet check`)

Result:

Pass. Server: 3739 tests, 0 failures, 0 errors, 0 skipped, ktlint clean. This includes the four gtd convert test files this task changed: TaskToEventEdgeCaseTests 22, TaskToEventRollbackTests 1, TaskToNoteRollbackTests 1, TaskToNoteWebTests 8, all passing. Web: 126 files / 2563 tests passed, lint clean, build OK. Android: ktlint, unit tests and lint passed. TFP gate tests: 20 OK. Ended with "all checks passed", 2026-10-21 22:37-22:40 +0200.

## .taskcheck

Result:

Pass (see above)

## Bugs found

None.

## Coverage

Status: verified (fresh run on HEAD bbdb3a69, after the last production change)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured. For each file I took the added/modified line numbers from `git diff -U0 ca4eeffa..bbdb3a69` and intersected them with the lines that the JaCoCo XML lists for that sourcefile (lines with instructions). A line counts as covered when `ci > 0`. Removed lines and lines without bytecode are not in the denominator. I don't infer branch coverage from this.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: habits-api; base `ca4eeffa32bea589fa93118f91ec336943a232cd`; HEAD `bbdb3a69056ebfd8f3cee0a9ee12f44f0194fba5`; commits 8d287f7d..bbdb3a69 as supplied by the parent. The file scope is `git diff --name-only ca4eeffa..HEAD -- server/src/main`, cross-checked against the task's touches and notes.
- Source/test snapshot identifier (including uncommitted inputs): HEAD bbdb3a69 with a clean working tree for `server/` (`git status --porcelain` before and after showed only `reviews/habits-api/review.md`, a non-input). I added no tests.
- Exact included files: `core/reminders/domain/ReminderService.kt`, `core/reminders/infra/ReminderRepository.kt`, `journal/habits/api/HabitController.kt`, `journal/habits/api/HabitDtos.kt`, `journal/habits/domain/Habit.kt`, `journal/habits/domain/HabitService.kt`, `journal/habits/domain/HabitStats.kt`, `journal/habits/infra/HabitItemResolver.kt`, `journal/habits/infra/HabitRepositories.kt` (all under `server/src/main/kotlin/ch/sonensei/cockpit/`). Excluded: the two Flyway SQL migrations (`db/migration/journal/V202610211930__journal_habits.sql`, `V202610212300__journal_habits_reminder_id.sql`), because JaCoCo cannot instrument SQL. Every Testcontainers test applies them, so they run. Also excluded: generated `api/openapi.yaml` and `web/src/app/api/generated/**` (generated code, checked by OpenApiContractTest and `api:check`), the tests, and `reviews/`. No renamed or deleted production files.
- Provider/version, command, working directory, and test selection: JaCoCo 0.8.15 (Gradle `jacoco` plugin, Gradle 9.8.1); `./gradlew cleanTest test --tests 'ch.sonensei.cockpit.journal.habits.*' --tests '*Reminder*' --tests '*OpenApiContractTest' jacocoTestReport`; working directory `server/`. Test selection as in the command (134 tests).
- Run start/completion time and exit status: 2026-10-21 22:32:30 to 22:33:07 +0200, exit 0.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`, sha256 `c35f4bc875c707e131e61cbae0ca6d7414a5b428d5b16f269c7ce3a8efa7437d` (a copy is at `.tmp/habits-api-jacoco.xml`); exec data `server/build/jacoco/test.exec`, written 22:33:04, report written 22:33:06.
- Evidence outputs are fresh and inputs stayed unchanged: `cleanTest` forced a fresh test run, and the test-result timestamps are 20:32:36Z-20:33:04Z. HEAD and the server sources were the same before and after.
- Final run after latest test additions and production fixes: yes. bbdb3a69 is the last production change, and no tests were added afterwards.

Values below use covered/total (percentage). Zero-denominator metrics are N/A. "Statements" are JaCoCo instructions.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines | Changed lines |
|---|---:|---:|---:|---:|---:|
| `core/reminders/domain/ReminderService.kt` | 335/335 (100.0%) | 12/12 (100.0%) | 19/19 (100.0%) | 49/49 (100.0%) | 1/1 (100.0%) |
| `core/reminders/infra/ReminderRepository.kt` | N/A (0/0) | N/A | N/A | N/A | N/A (0/0) |
| `journal/habits/api/HabitController.kt` | 336/338 (99.4%) | 17/18 (94.4%) | 12/12 (100.0%) | 32/32 (100.0%) | 32/32 (100.0%) |
| `journal/habits/api/HabitDtos.kt` | 410/467 (87.8%) | 6/8 (75.0%) | 40/50 (80.0%) | 93/97 (95.9%) | 93/97 (95.9%) |
| `journal/habits/domain/Habit.kt` | 235/243 (96.7%) | 2/4 (50.0%) | 31/32 (96.9%) | 39/40 (97.5%) | 39/40 (97.5%) |
| `journal/habits/domain/HabitService.kt` | 1146/1158 (99.0%) | 112/114 (98.2%) | 39/39 (100.0%) | 137/137 (100.0%) | 137/137 (100.0%) |
| `journal/habits/domain/HabitStats.kt` | 635/644 (98.6%) | 49/52 (94.2%) | 31/32 (96.9%) | 78/78 (100.0%) | 78/78 (100.0%) |
| `journal/habits/infra/HabitItemResolver.kt` | 83/83 (100.0%) | 7/7 (100.0%) | 5/5 (100.0%) | 17/17 (100.0%) | 17/17 (100.0%) |
| `journal/habits/infra/HabitRepositories.kt` | N/A (0/0) | N/A | N/A | N/A | N/A (0/0) |
| **Aggregate (summed counters, 9 Kotlin files)** | 3180/3268 (97.3%) | 205/215 (95.3%) | 177/189 (93.7%) | 445/450 (98.9%) | 397/402 (98.8%) |

`ReminderRepository.kt` and `HabitRepositories.kt` contain only Spring Data repository interfaces, with query methods and the JPQL `@Modifying` update `setReminderId`. JaCoCo has no bytecode to count there (0/0), so they are N/A, not 100%. Spring Data runs them at runtime, and the tests exercise them: `ReminderPendingOfItemTests` covers `findByItemTypeAndItemIdAndFiredAtIsNull...`, and `moving the habit's reminder does not change its version` covers `setReminderId`. Missing or unmeasured files: none. The SQL migrations are excluded as noted above.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `HabitDtos.kt:127-130` (`HabitCheckRequest`) | None: OpenAPI schema class only, referenced in `@DocumentedBody` of the PUT check endpoint. The controller parses the body itself. | Justified gap. OpenApiContractTest checks the documented schema. |
| `HabitDtos.kt:53,54` (`checkNotNull(createdAt/updatedAt)` failure branch) and the remaining instructions/methods of the data classes (generated `copy`/`componentN`) | Defensive: timestamps are always set on a persisted habit | Justified gap |
| `Habit.kt:73` (`reminderId` protected setter) | None: Hibernate writes the field directly. The column is `insertable/updatable = false` and only `HabitRepository.setReminderId` changes it, which tests cover. | Justified gap |
| `Habit.kt:98,106` (`WeekdaysConverter` `attribute`/`dbData` null branch) | Defensive: the column is `not null` and the entity list is never null | Justified gap |
| `HabitController.kt:193` (`?: "body"` fallback when a Jackson error has no property path) | Defensive: converting a JSON object to `HabitRequest` always reports the field. Wrong-typed fields are tested (`schedule":"HOURLY`, `reminderTime":"25:00"`) | Justified gap |
| `HabitService.kt:248` (`firstOrNull { it > now }` returns null) | Defensive: every valid rule has an open due day within the 15-day horizon (`open due days stay within the reminder horizon` covers the horizon) | Justified gap |
| `HabitService.kt:269` (`checkNotNull(habit.createdAt)` failure) | Defensive | Justified gap |
| `HabitStats.kt:70,84,122` (`timesPerWeek ?: 1` null branch) | Defensive: validation requires `timesPerWeek` in 1..7 for TIMES_PER_WEEK (tested in `POST validates schedule fields...`) | Justified gap |

## Unable to test

- Nothing in scope. Concurrency on the habit row lock (bbdb3a69) is covered by deterministic tests in HabitReminderOwnershipTests: `a habit change waits for the row lock another change of the habit holds and sees its version` and `concurrent changes and checks of the same habit keep exactly one managed reminder`. Both pass against real Postgres.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. I added no tests. The focused suite (134 tests), the full server suite (3739 tests) and `.taskcheck` all pass on HEAD bbdb3a69.
