---
title: Web task detail: create a new project from the project picker
status: in_progress
owner: dell26/agent-1
deps: []
touches: [web/src/app/features/gtd/task-detail,web/src/locale]
prio: 2
lease_until: 2026-11-14T11:08:42Z
---
## Goal
The task detail project picker (`task-detail.html` `#td-project`) offers only existing projects or "No project".
Inbox processing already has "New project…" (`gtd/inbox/inbox-page.ts`); reuse that behavior.

## Acceptance criteria
- [ ] The project picker in task detail has "New project…"; it asks only for a title, creates the project (Active, no area) and assigns the task to it in one step
- [ ] Cancel or an empty title leaves the task's project unchanged; if creating the project or saving the task fails, an error is shown and a retry does not create a second project (same approach as Inbox processing)
- [ ] The new project appears in the picker and the project list, and the task appears in the project detail
- [ ] New UI strings are in `web/src/locale` (English + German)
- [ ] Unit tests cover the new behavior; `cd web && npm run check` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-14 with the owner: from a mail, make a Next action and archive the mail in one step (mails already clarified should not pass through the cockpit Inbox again); create a project directly from the task detail.
- ASSUMPTION (2026-11-14 11:45+0100 by dell26/agent-1): Task detail 'New project…' asks for the title inline below the picker (as Inbox processing does) with Create/Cancel; Create assigns the project and saves the whole form (other unsaved edits included), since the editor has one Save for all fields.
