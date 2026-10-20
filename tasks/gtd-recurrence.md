---
title: Recurring tasks
status: in_progress
owner: R1963MACOS/agent-1
deps: [gtd-projects-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/gtd,server/src/test/kotlin/ch/sonensei/cockpit/gtd,server/src/main/resources/db/migration/gtd]
prio: 1
lease_until: 2026-10-20T18:27:05Z
---
## Goal
RRULE recurrence on completion (SPEC.md §5.2).

## Acceptance criteria
- [ ] Tasks accept `recurrenceRule` (validated RRULE via ical4j) and `recurrenceMode` FIXED|AFTER_COMPLETION
- [ ] Completing a recurring task creates exactly one next occurrence (FIXED: next RRULE date after the current due date; AFTER_COMPLETION: relative to completion date in the user's time zone); no pre-generation; uncomplete does not create duplicates
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.*'` passes (Postgres via Testcontainers where persistence is involved) incl. weekly Monday case, late AFTER_COMPLETION case, DST boundary

## Notes
