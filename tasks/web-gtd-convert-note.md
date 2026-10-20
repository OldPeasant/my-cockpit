---
title: Inbox/task detail: to reference
status: todo
owner:
deps: [web-inbox,web-task-detail,gtd-convert-note]
touches: [web/src/app/features/gtd/inbox,web/src/app/features/gtd/task-detail]
prio: 2
---
## Goal
Enable 'To reference (note)' in processing and task detail.

## Acceptance criteria
- [ ] Action converts and navigates/offers to open the note
- [ ] Vitest component/service tests cover the action calls convert and updates the inbox; `cd web && npm run check` passes

## Notes
