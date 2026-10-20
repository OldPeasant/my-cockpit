---
title: Task detail editor
status: todo
owner:
deps: [web-gtd-data,web-links-panel,gtd-recurrence]
touches: [web/src/app/features/gtd/task-detail]
prio: 1
---
## Goal
Edit all task fields (SPEC.md §5.2).

## Acceptance criteria
- [ ] Side panel/dialog with title, notes (Markdown), list, project, contexts, energy, creativity, estimate, due date/time, scheduled, tickler, waiting for, checklist (add/toggle/reorder), recurrence editor (presets + custom RRULE)
- [ ] Saves with If-Match; a 412 shows 'changed elsewhere' and reloads
- [ ] Linked items panel included
- [ ] Vitest component/service tests cover form validation, 412 handling, recurrence presets; `cd web && npm run check` passes

## Notes
