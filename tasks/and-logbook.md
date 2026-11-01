---
title: Android: Logbook (completed tasks, reopen)
status: in_progress
owner: dell26/agent-2
deps: [and-projects-lists,and-gtd-data,and-trash]
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/lists,android/app/src/main/java/ch/sonensei/cockpit/ui,android/app/src/test/java/ch/sonensei/cockpit/gtd,android/app/src/main/res/values/strings_projects_lists.xml,android/app/src/main/res/values-de/strings_projects_lists.xml]
prio: 2
lease_until: 2026-11-01T19:00:26Z
---
## Goal
The Logbook (done tasks, SPEC.md §5) exists on the web only. Use `GET /api/v1/tasks?completed=true` and
`POST /api/v1/tasks/{id}/uncomplete`; server unchanged.

## Acceptance criteria
- [ ] Logbook is reachable from the More screen (next to Projects/Waiting/Someday) and lists completed tasks newest first, grouped by completion day, with project/context where set; loads more on scroll if the API pages
- [ ] Tapping a task opens it as in the other GTD lists; a "Reopen" action uncompletes it and it leaves the Logbook
- [ ] Loading, empty and error states follow the existing GTD list screens
- [ ] All new UI strings exist in English and German (`res/values`, `res/values-de`)
- [ ] Unit tests (ViewModel, plus Compose UI tests where the project has them for similar screens) cover the criteria above; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes

## Notes
Requested by the owner (2026-11-01) after trying the Android app on the phone.
- ASSUMPTION (2026-11-01 19:43+0100 by dell26/agent-2): No Android GTD list opens a task on tap (there is no Android task detail view), so tapping a Logbook row opens a read-only detail dialog (completion time, project, contexts, notes) with Reopen/Close; Reopen is also a row button like Follow up/Activate
