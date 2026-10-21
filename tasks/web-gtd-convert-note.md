---
title: Inbox/task detail: to reference
status: in_progress
owner: dell26/agent-3
deps: [web-inbox,web-task-detail,gtd-convert-note]
touches: [web/src/app/features/gtd/inbox,web/src/app/features/gtd/task-detail]
prio: 2
lease_until: 2026-10-21T06:21:28Z
extended: dell26/agent-3
---
## Goal
Enable 'To reference (note)' in processing and task detail.

## Acceptance criteria
- [ ] Action converts and navigates/offers to open the note
- [ ] Vitest component/service tests cover the action calls convert and updates the inbox; `cd web && npm run check` passes

## Notes
- ASSUMPTION (2026-10-21 07:01+0200 by dell26/agent-3): Inbox processing does not navigate away after 'To reference': it shows a status line with an 'Open note' link (/notes/:id) so processing can continue; the task detail navigates straight to /notes/:id after a confirm. The /notes/:id route comes with web-notes-editor.
- ASSUMPTION (2026-10-21 07:01+0200 by dell26/agent-3): Added TaskStore.convertToNote in gtd/data (outside touches) so the inbox item leaves the list optimistically like trash; inline clarification changes are not saved before converting (a note has no GTD attributes).
- ASSUMPTION (2026-10-21 07:43+0200 by dell26/agent-3): Completed before web-notes-editor (in progress) adds the /notes/:id route: until then 'Open note' and the task-detail navigation land on Not Found. Convert + navigation target are verified via unit tests (router/link), not end to end.
- extended by dell26/agent-3: Implementation + 2 review rounds done, CR-5/CR-6 fixed in 331a24a6, web npm run check passes. Left: re-run test-writer and code-reviewer on final code (fixes changed code after review-2), tasks.py verify both criteria, write reviews/web-gtd-convert-note/quality.json, run done.
- ASSUMPTION (2026-10-21 08:04+0200 by dell26/agent-3): Review CR-7 (P3, one spec line in task-store.spec.ts not Prettier-wrapped) left as is: npm run check does not enforce Prettier and reformatting after the final review would make the review evidence stale; a later format pass can fix it.
