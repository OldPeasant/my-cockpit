---
title: Today view: keep 'scheduled for today' visible after the scheduled release
status: todo
owner:
deps: [web-next-today]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/gtd,web/src/app/features/gtd/today]
prio: 2
---
## Goal
TaskService.releaseDue moves SCHEDULED tasks to NEXT and clears scheduledDate, so the Today view (SPEC.md §5.3 'scheduled for today') can't tell which Next actions were scheduled for today.

## Acceptance criteria
- [ ] Server keeps a marker (e.g. `scheduledDate` kept or a new `releasedOn` field) on released tasks, new Flyway migration if needed, `api/openapi.yaml` regenerated; server tests cover it
- [ ] Today view 'Scheduled for today' shows tasks released today; Vitest covers it; `cd web && npm run check` passes

## Notes
