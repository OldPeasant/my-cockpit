---
title: Android notes
status: in_progress
owner: dell26/agent-3
deps: [and-login,notes-api]
touches: [android/app/src/main/java/ch/sonensei/cockpit/notes,android/app/src/test/java/ch/sonensei/cockpit/notes]
prio: 2
lease_until: 2026-10-21T09:47:48Z
---
## Goal
View/edit notes and add photos (SPEC.md §9.2 item 6).

## Acceptance criteria
- [ ] Notes list (pinned first, tag filter), Markdown view with rendered images, plain editor with autosave, add photo from camera/gallery (uploads image)
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*'` passes, covering list ordering and save conflict handling (Robolectric/Compose UI tests for screens)

## Notes
- ASSUMPTION (2026-10-21 11:30+0200 by dell26/agent-3): Android notes has no offline cache (SPEC §9.3 only names Inbox/Next/Today/agenda): the list and a note load from the server, failures show a retry banner; unsaved text stays in the editor until saved
