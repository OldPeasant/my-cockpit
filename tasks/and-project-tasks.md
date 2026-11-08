---
title: Android: add tasks directly to a project and reorder them
status: todo
owner:
deps: [and-projects-manage]
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/projects,android/app/src/test/java/ch/sonensei/cockpit/gtd/projects,android/app/src/main/res/values/strings_projects_lists.xml,android/app/src/main/res/values-de/strings_projects_lists.xml]
prio: 1
---
## Goal
On Android a task can only be added to a project as "next step" inside the choose-next-step prompt; web has an
"Add task" field on project detail (`project-detail-page.html:186-205`) and reordering (`:124-138`).

## Acceptance criteria
- [ ] Project detail has an "Add task" field/button that creates a Next task in the project (quick entry, stays open for several tasks); optional "make it the next step"
- [ ] Tasks can be reordered with long-press drag (with accessibility actions "move up/down"); the order is saved like on web
- [ ] A task row opens task detail; tasks of other lists (Waiting/Someday/Scheduled) keep their list label
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
