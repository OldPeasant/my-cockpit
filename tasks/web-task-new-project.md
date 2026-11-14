---
title: Web task detail: create a new project from the project picker
status: in_progress
owner: dell26/agent-1
deps: []
touches: [web/src/app/features/gtd/task-detail,web/src/locale]
prio: 2
lease_until: 2026-11-14T11:13:42Z
---
## Goal
The task detail project picker (`task-detail.html` `#td-project`) offers only existing projects or "No project".
Inbox processing already has "New project…" (`gtd/inbox/inbox-page.ts`); reuse that behavior.

## Acceptance criteria
- [x] The project picker in task detail has "New project…"; it asks only for a title, creates the project (Active, no area) and assigns the task to it in one step
  - verified (2026-11-14 11:55+0100 by dell26/agent-1): cd web && npx ng test --watch=false --include src/app/features/gtd/task-detail/task-detail.spec.ts -> 167 passed; 'New project from the project picker' tests: picker offers New project…, only a title field, createProject body {title} (server default ACTIVE, no area), then one updateTask {projectId: new id}
- [x] Cancel or an empty title leaves the task's project unchanged; if creating the project or saving the task fails, an error is shown and a retry does not create a second project (same approach as Inbox processing)
  - verified (2026-11-14 11:55+0100 by dell26/agent-1): same spec run -> 167 passed; tests: Cancel/Escape/empty title leave projectId unchanged and make no calls; create failure shows role=alert and Create retries; failed save (500/412) shows error, retry with same title reuses project (createProject called once), other title creates new one
- [ ] The new project appears in the picker and the project list, and the task appears in the project detail
- [x] New UI strings are in `web/src/locale` (English + German)
  - verified (2026-11-14 11:55+0100 by dell26/agent-1): grep taskDetail.newProject|newProjectTitle|createProject|cancelNewProject in web/src/locale/messages.xlf and messages.de.xlf -> 4 units each, German targets translated (Neues Projekt…, Titel des neuen Projekts, Erstellen, Abbrechen); ng extract-i18n sources match
- [ ] Unit tests cover the new behavior; `cd web && npm run check` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-14 with the owner: from a mail, make a Next action and archive the mail in one step (mails already clarified should not pass through the cockpit Inbox again); create a project directly from the task detail.
- ASSUMPTION (2026-11-14 11:45+0100 by dell26/agent-1): Task detail 'New project…' asks for the title inline below the picker (as Inbox processing does) with Create/Cancel; Create assigns the project and saves the whole form (other unsaved edits included), since the editor has one Save for all fields.
