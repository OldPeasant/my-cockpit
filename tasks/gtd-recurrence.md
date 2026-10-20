---
title: Recurring tasks
status: in_progress
owner: R1963MACOS/agent-1
deps: [gtd-projects-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/gtd,server/src/test/kotlin/ch/sonensei/cockpit/gtd,server/src/main/resources/db/migration/gtd]
prio: 1
lease_until: 2026-10-20T18:31:24Z
---
## Goal
RRULE recurrence on completion (SPEC.md §5.2).

## Acceptance criteria
- [ ] Tasks accept `recurrenceRule` (validated RRULE via ical4j) and `recurrenceMode` FIXED|AFTER_COMPLETION
- [ ] Completing a recurring task creates exactly one next occurrence (FIXED: next RRULE date after the current due date; AFTER_COMPLETION: relative to completion date in the user's time zone); no pre-generation; uncomplete does not create duplicates
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.*'` passes (Postgres via Testcontainers where persistence is involved) incl. weekly Monday case, late AFTER_COMPLETION case, DST boundary

## Notes
- ASSUMPTION (2026-10-20 20:13+0200 by R1963MACOS/agent-1): Next occurrence is a copy on the same list (notes, project if still active, contexts, energy/creativity/estimate, due time, waiting-for; checklist copied unchecked; no tickler; waitingSince=today); due and scheduled dates move by the same number of days; a task with neither date gets the next date as due date
- ASSUMPTION (2026-10-20 20:13+0200 by R1963MACOS/agent-1): FIXED anchors on dueDate, else scheduledDate, else the completion day, and keeps the schedule even when the next date has passed; AFTER_COMPLETION = first RRULE day after the completion day (user zone) with the series starting that day
