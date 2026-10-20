---
title: Habits API
status: todo
owner:
deps: [p0-items-links,reminders-core]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/journal/habits,server/src/test/kotlin/ch/sonensei/cockpit/journal/habits,server/src/main/resources/db/migration/journal/habits]
prio: 3
---
## Goal
Habits with streaks (SPEC.md §8.5).

## Acceptance criteria
- [ ] Habit CRUD (daily / N per week / weekdays, target count, reminder time → reminders), checks per date, streak and 30-day completion stats
- [ ] Resolver HABIT
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.journal.habits.*'` passes (Postgres via Testcontainers where persistence is involved) incl. streaks across week boundaries
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
