---
title: Recurring tasks
status: in_progress
owner: R1963MACOS/agent-1
deps: [gtd-projects-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/gtd,server/src/test/kotlin/ch/sonensei/cockpit/gtd,server/src/main/resources/db/migration/gtd]
prio: 1
lease_until: 2026-10-20T18:51:28Z
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
- ASSUMPTION (2026-10-20 20:13+0200 by R1963MACOS/agent-1): Duplicates prevented with a new column task.next_occurrence_id (migration V202610201830): only the first completion creates an occurrence; uncomplete keeps it, re-complete creates none
- ASSUMPTION (2026-10-20 20:13+0200 by R1963MACOS/agent-1): Validation: only DAILY/WEEKLY/MONTHLY/YEARLY (tasks are dated by day), no RRULE: prefix, COUNT>=1; recurrenceRule and recurrenceMode must be set together (400 otherwise); COUNT counts down by one per occurrence, COUNT=1 and a passed UNTIL end the series
- ASSUMPTION (2026-10-20 20:13+0200 by R1963MACOS/agent-1): API unchanged (no nextOccurrence in the /complete response, openapi.yaml outside touches): clients see the new task via the CREATED change event / list refresh
- ASSUMPTION (2026-10-20 20:24+0200 by R1963MACOS/agent-1): Purging (hard delete) the next occurrence clears next_occurrence_id, so uncomplete+complete of the original afterwards creates a new one; trashing keeps the link. Pre-existing tasks with an invalid rule get 400 on complete (no such data exists yet)
