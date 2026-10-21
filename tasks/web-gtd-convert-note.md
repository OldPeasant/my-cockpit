---
title: Inbox/task detail: to reference
status: in_progress
owner: dell26/agent-3
deps: [web-inbox,web-task-detail,gtd-convert-note]
touches: [web/src/app/features/gtd/inbox,web/src/app/features/gtd/task-detail]
prio: 2
lease_until: 2026-10-21T05:56:15Z
---
## Goal
Enable 'To reference (note)' in processing and task detail.

## Acceptance criteria
- [ ] Action converts and navigates/offers to open the note
- [ ] Vitest component/service tests cover the action calls convert and updates the inbox; `cd web && npm run check` passes

## Notes
- ASSUMPTION (2026-10-21 07:01+0200 by dell26/agent-3): Inbox processing does not navigate away after 'To reference': it shows a status line with an 'Open note' link (/notes/:id) so processing can continue; the task detail navigates straight to /notes/:id after a confirm. The /notes/:id route comes with web-notes-editor.
- ASSUMPTION (2026-10-21 07:01+0200 by dell26/agent-3): Added TaskStore.convertToNote in gtd/data (outside touches) so the inbox item leaves the list optimistically like trash; inline clarification changes are not saved before converting (a note has no GTD attributes).
