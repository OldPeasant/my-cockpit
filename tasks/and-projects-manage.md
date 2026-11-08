---
title: Android: create, edit, complete and drop projects
status: in_progress
owner: felix/agent-1
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/projects,android/app/src/test/java/ch/sonensei/cockpit/gtd/projects,android/app/src/main/res/values/strings_projects_lists.xml,android/app/src/main/res/values-de/strings_projects_lists.xml]
prio: 1
lease_until: 2026-11-08T10:01:01Z
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
