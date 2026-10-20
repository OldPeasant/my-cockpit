---
title: Task detail editor
status: in_progress
owner: dell26/agent-3
deps: [web-gtd-data,web-links-panel,gtd-recurrence]
touches: [web/src/app/features/gtd/task-detail]
prio: 1
lease_until: 2026-10-20T22:42:08Z
extended: dell26/agent-3
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
- ASSUMPTION (2026-10-20 23:04+0200 by dell26/agent-3): Notes are a plain monospace Markdown textarea (no preview/CodeMirror): no Markdown renderer exists in the web app yet; web-notes-editor builds the Markdown editor
- ASSUMPTION (2026-10-20 23:04+0200 by dell26/agent-3): Waiting for is edited as free text (waitingForText) plus 'since' date; picking a contact (waitingForContactId) is left for web-contacts
- ASSUMPTION (2026-10-20 23:04+0200 by dell26/agent-3): Save sends /move first when list or tickler change (move sets list, scheduled date and tickler together), then PATCH of the other fields with the new ETag; checklist changes save immediately per item with their own If-Match, and a 412 there reloads the checklist
- ASSUMPTION (2026-10-20 23:04+0200 by dell26/agent-3): Custom RRULE is validated client-side by a small parser mirroring the server's TaskRecurrence rules (DAILY..YEARLY, no time parts, COUNT>=1); the rrule library was not used because it accepts invalid values
- extended by dell26/agent-3: Implementation and all review fixes (R1 CR-1..4, TW-1..4, R2 CR-1) are committed; R2 CR-2 (link-out drops unsaved edits) was left unfixed on purpose. cd web && npm run check passes (51 files, 924 tests). Left: re-run test-writer and code-reviewer on the final code (6b42d0e1 + 3101390c), tasks.py verify for AC1-4, write reviews/web-task-detail/quality.json, run .taskcheck, then done.
- ASSUMPTION (2026-10-20 23:58+0200 by dell26/agent-3): Only Close asks before discarding unsaved changes; following a link or changing the route id drops unsaved edits without a confirmation (review R2 CR-2, P3). No acceptance criterion asks for it; a canDeactivate guard can be added app-wide later.
