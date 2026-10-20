---
title: Task detail editor
status: in_progress
owner: dell26/agent-3
deps: [web-gtd-data,web-links-panel,gtd-recurrence]
touches: [web/src/app/features/gtd/task-detail]
prio: 1
lease_until: 2026-10-20T21:21:23Z
---
## Goal
Edit all task fields (SPEC.md §5.2).

## Acceptance criteria
- [ ] Side panel/dialog with title, notes (Markdown), list, project, contexts, energy, creativity, estimate, due date/time, scheduled, tickler, waiting for, checklist (add/toggle/reorder), recurrence editor (presets + custom RRULE)
- [ ] Saves with If-Match; a 412 shows 'changed elsewhere' and reloads
- [ ] Linked items panel included
- [ ] Vitest component/service tests cover form validation, 412 handling, recurrence presets; `cd web && npm run check` passes

## Notes
- ASSUMPTION (2026-10-20 23:04+0200 by dell26/agent-3): The editor is a routed page /tasks/:id (inbox and search already link there) laid out as a panel; TaskDetail is a standalone component that a dialog can embed later. Added the 'tasks' route to gtd/gtd.routes.ts (outside touches, one line, as features/README.md says for new GTD views)
