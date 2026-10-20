---
title: Android notes
status: todo
owner:
deps: [and-login,notes-api]
touches: [android/app/src/main/java/ch/sonensei/cockpit/notes,android/app/src/test/java/ch/sonensei/cockpit/notes]
prio: 2
---
## Goal
View/edit notes and add photos (SPEC.md §9.2 item 6).

## Acceptance criteria
- [ ] Notes list (pinned first, tag filter), Markdown view with rendered images, plain editor with autosave, add photo from camera/gallery (uploads image)
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*'` passes, covering list ordering and save conflict handling (Robolectric/Compose UI tests for screens)

## Notes
