---
title: Note editor: LinkedItemsPanel
status: in_progress
owner: dell26/agent-1
deps: [and-notes-list, and-links-panel, and-notes-revisions]
prio: 2
parent: and-notes-editor
lease_until: 2026-11-09T07:24:43Z
---
## Goal
Part of `and-notes-editor` (Android note editor: tags, checklists, links, revisions), which was split.
Goal: Show the shared `LinkedItemsPanel` (from `and-links-panel`) for the open note in the Android note editor. Touches android/.../cockpit/notes, its tests and strings_notes.xml (en + de).

## Acceptance criteria
- [ ] `LinkedItemsPanel` for the note
- [ ] New strings (if any) are in English and German (`res/values/strings_notes.xml` + `res/values-de/strings_notes.xml`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
- Work done on `and-notes-editor` before the split is on `origin/wip/and-notes-editor` (if it exists): merge it first if your part builds on it.
