---
title: Note editor: revisions list and restore
status: todo
owner:
deps: [and-notes-list, and-links-panel, and-notes-tags]
prio: 2
parent: and-notes-editor
---
## Goal
Part of `and-notes-editor` (Android note editor: tags, checklists, links, revisions), which was split.
Goal: The Android note editor lists a note's revisions and restores one (server notes revisions API; web: `note-editor-page.html`). Touches android/.../cockpit/notes, its tests and strings_notes.xml (en + de).

## Acceptance criteria
- [ ] Revisions: list and restore
- [ ] New strings are in English and German (`res/values/strings_notes.xml` + `res/values-de/strings_notes.xml`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
- Work done on `and-notes-editor` before the split is on `origin/wip/and-notes-editor` (if it exists): merge it first if your part builds on it.
