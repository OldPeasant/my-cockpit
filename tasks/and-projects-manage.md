---
title: Android: create, edit, complete and drop projects
status: in_progress
owner: felix/agent-1
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/projects,android/app/src/test/java/ch/sonensei/cockpit/gtd/projects,android/app/src/main/res/values/strings_projects_lists.xml,android/app/src/main/res/values-de/strings_projects_lists.xml]
prio: 1
lease_until: 2026-11-08T10:06:03Z
---
## Goal
Android cannot create a project (only inside inbox "To project → New") nor edit one (SPEC.md §5.3; web:
`projects-page.html:5`, `project-form.html`). Parity decided by the owner 2026-11-08 (see docs/PARITY.md).

## Acceptance criteria
- [ ] The Projects screen has a "New project" FAB/action: title (required), area (pick an existing area), due date, review date, description
- [ ] Project detail has Edit: title, status (active/someday/on hold/completed/dropped), area, due date, review date; completing or dropping from a menu asks for confirmation if open tasks remain (same rule as web)
- [ ] The description is rendered as Markdown and can be edited (edit/preview), using the existing Android Markdown renderer from notes
- [ ] Deleting a project: menu action with confirmation, tasks handled like on the server's delete-reassign rules (`gtd-delete-reassign`)
- [ ] Optimistic locking: 412 reloads and tells the user
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 10:43+0100 by felix/agent-1): Web has no 'confirm when open tasks remain' rule (project-form.ts saves status directly), so Android's rule is: completing/dropping (menu or form status) asks for confirmation when the project detail shows >=1 open task; the server leaves those tasks open and in the project, which the dialog says.
- ASSUMPTION (2026-11-08 10:43+0100 by felix/agent-1): Project delete: the server's rule (ProjectService.delete) is 'project to Trash, tasks keep their project'; gtd-delete-reassign concerns contexts/areas only. Android always confirms delete and tells how many open tasks keep the project; no reassignment.
- ASSUMPTION (2026-11-08 10:43+0100 by felix/agent-1): New project is an Add (+) icon action in the Projects list header, not a FAB: the app shell already places a global capture FAB (LocalFabClearance). New projects are created ACTIVE (status is editable afterwards), matching the criterion's field list.
- ASSUMPTION (2026-11-08 10:43+0100 by felix/agent-1): 412 on save keeps the form open: projects are reloaded, the form is rebased on the current version keeping only the fields the user changed, and a banner + snackbar say so; the user saves again. 412 on status change/delete reloads and shows a snackbar.
