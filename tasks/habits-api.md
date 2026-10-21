---
title: Habits API
status: in_progress
owner: dell26/agent-2
deps: [p0-items-links,reminders-core]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/journal/habits,server/src/test/kotlin/ch/sonensei/cockpit/journal/habits,server/src/main/resources/db/migration/journal/habits]
prio: 3
lease_until: 2026-10-21T19:53:16Z
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
