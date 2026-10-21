---
title: Habits API
status: in_progress
owner: dell26/agent-2
deps: [p0-items-links,reminders-core]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/journal/habits,server/src/test/kotlin/ch/sonensei/cockpit/journal/habits,server/src/main/resources/db/migration/journal/habits]
prio: 3
lease_until: 2026-10-21T20:48:42Z
extended: dell26/agent-2
---
## Goal
Habits with streaks (SPEC.md §8.5).

## Acceptance criteria
- [ ] Habit CRUD (daily / N per week / weekdays, target count, reminder time → reminders), checks per date, streak and 30-day completion stats
- [ ] Resolver HABIT
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.journal.habits.*'` passes (Postgres via Testcontainers where persistence is involved) incl. streaks across week boundaries
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
- ASSUMPTION (2026-10-21 21:38+0200 by dell26/agent-2): Habit migration lives in db/migration/journal/V202610211930__journal_habits.sql (not journal/habits/ as in touches): MigrationConventionTests requires a flat <module>/ folder whose name matches the file prefix
- ASSUMPTION (2026-10-21 21:38+0200 by dell26/agent-2): Habits are hard-deleted (DELETE with If-Match; checks cascade; ItemDeletedEvent removes links, reminders, search entry), no Trash: the spec gives habits an 'archived' flag for keeping them and the task names no trash; habit names are in global search
- ASSUMPTION (2026-10-21 21:38+0200 by dell26/agent-2): Streaks: DAILY/WEEKDAYS count scheduled days done in a row (count >= targetCount); days the schedule does not name neither extend nor break; TIMES_PER_WEEK counts weeks (owner's weekStart setting) with >= timesPerWeek done days (streakUnit WEEKS). Today/this week does not break a streak while still open. longestStreak is also returned
- ASSUMPTION (2026-10-21 21:38+0200 by dell26/agent-2): 30-day completion: window = last 30 days up to today (today only once done), not before the habit existed (creation date, or earliest check); rate = done/due capped at 1; TIMES_PER_WEEK expects round(timesPerWeek*days/7) done days; null when nothing was due
- ASSUMPTION (2026-10-21 21:38+0200 by dell26/agent-2): Reminders: a non-archived habit with reminderTime (local time in the owner's time zone) keeps exactly one pending core reminder, at that time on the next due day not done yet; it is moved on every habit/check change and after it fires (ReminderFiredEvent listener). A later time zone change applies at the next move
- ASSUMPTION (2026-10-21 21:38+0200 by dell26/agent-2): Checks API: PUT /habits/{id}/checks/{date} {count} sets an absolute count (0 removes, max 1000, no If-Match, future dates 400) and returns the habit with new stats; GET /habits/{id}/checks?from&to (default last 30 days, max 3660 days). Habit list is not paginated (active by default, ?archived=true for archived), ordered by name
- ASSUMPTION (2026-10-21 22:07+0200 by dell26/agent-2): Habit stores the id of the one reminder the module manages (habit.reminder_id, migration V202610212300); sync only moves/deletes that reminder so user-created reminders on a habit survive; resync runs AFTER_COMMIT in its own transaction so a habit failure cannot roll back a core firing batch
- ASSUMPTION (2026-10-21 22:07+0200 by dell26/agent-2): gtd convert tests used HABIT as a type without resolver; they now link to a real habit (4 gtd test files outside touches) because HABIT has a resolver now
- extended by dell26/agent-2: Implementation, fixes for review 1 (CR-1..3) and review 2 are committed; .taskcheck passes. Left: test-writer report reviews/habits-api/tests.md (the tests are committed in 4ce6aafd, the report with coverage is missing); decide on review 2 P3 CR-1 (lock the habit row via lockById in update/onReminderFired/delete) and, if fixed, re-run test-writer + code-reviewer; tasks.py verify for 4 criteria (habits test suite, OpenApiContractTest); quality.json; done.
