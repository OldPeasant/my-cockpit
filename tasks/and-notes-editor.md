---
title: Android note editor: tags, checklists, links, revisions
status: in_progress
owner: felix/agent-1
deps: [and-notes-list,and-links-panel]
touches: [android/app/src/main/java/ch/sonensei/cockpit/notes,android/app/src/test/java/ch/sonensei/cockpit/notes,android/app/src/main/res/values/strings_notes.xml,android/app/src/main/res/values-de/strings_notes.xml]
prio: 2
lease_until: 2026-11-08T21:42:51Z
---
## Goal
The Android note editor lacks tags, checklist toggling in the preview, `[[` link autocomplete, revisions and the
links panel (web: `note-editor-page.html`).

## Acceptance criteria
- [ ] Edit tags (chips with suggestions from existing tags)
- [ ] Tapping a checklist item in preview toggles it (saved like an edit)
- [ ] Typing `[[` offers search-as-you-type and inserts `[[type:id]]`
- [ ] Revisions: list and restore
- [ ] `LinkedItemsPanel` for the note
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
