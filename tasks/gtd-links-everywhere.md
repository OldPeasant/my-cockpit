---
title: Linked items on projects and files; follow-up by mail (web + Android)
status: in_progress
owner: felix/agent-1
deps: [and-links-panel,web-projects-plus,and-project-tasks,and-files-preview,and-scheduled-list,web-task-actions]
touches: [web/src/app/features/gtd/projects,web/src/app/features/files,web/src/app/features/gtd/lists,android/app/src/main/java/ch/sonensei/cockpit/gtd/projects,android/app/src/test/java/ch/sonensei/cockpit/gtd/projects,android/app/src/main/java/ch/sonensei/cockpit/files,android/app/src/test/java/ch/sonensei/cockpit/files,android/app/src/main/java/ch/sonensei/cockpit/gtd/lists,android/app/src/test/java/ch/sonensei/cockpit/gtd/lists,web/src/locale,android/app/src/main/res/values/strings_projects_lists.xml,android/app/src/main/res/values-de/strings_projects_lists.xml,android/app/src/main/res/values/strings_files.xml,android/app/src/main/res/values-de/strings_files.xml]
prio: 3
lease_until: 2026-11-09T01:29:54Z
---
## Goal
SPEC.md §4.3 wants the linked-items panel on every detail view; projects and files have none on either client.
SPEC.md §5.3 also wants waiting "follow up" to be able to draft an email to the contact.

## Acceptance criteria
- [ ] Web and Android project detail show the linked-items panel
- [ ] Web file details/preview and Android file preview show the linked-items panel
- [ ] Waiting "Follow up" offers "Draft email" (prefilled To from the linked contact or waiting-for text, subject from the task) on web and Android
- [ ] New strings in English and German on both clients (`web/src/locale`, `res/values{,-de}/`)
- [ ] Unit tests cover the new behavior on each changed part; `sh scripts/check.sh` passes

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-09 02:12+0100 by felix/agent-1): Web 'Draft email' opens /mail/compose?to=&subject=; added minimal ?to/?subject prefill to the compose page (outside touches, 5 lines) since no prefill existed. To = first email of waitingForContactId's contact, else an address found in waitingForText, else empty (a plain name is not a usable address).
