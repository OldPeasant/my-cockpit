---
title: Note editor: LinkedItemsPanel
status: in_progress
owner: dell26/agent-1
deps: [and-notes-list, and-links-panel, and-notes-revisions]
prio: 2
parent: and-notes-editor
lease_until: 2026-11-09T07:48:21Z
---
## Goal
Part of `and-notes-editor` (Android note editor: tags, checklists, links, revisions), which was split.
Goal: Show the shared `LinkedItemsPanel` (from `and-links-panel`) for the open note in the Android note editor. Touches android/.../cockpit/notes, its tests and strings_notes.xml (en + de).

## Acceptance criteria
- [x] `LinkedItemsPanel` for the note
  - verified (2026-11-09 08:31+0100 by dell26/agent-1): NoteEditorLinksPanelScreenTest (5) + NoteEditorLinksRouteInAppTest (8, MainActivity/Hilt, fake LinksRepository) via ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*' -> 340 tests pass; saved note shows LinkedItemsPanel for ItemRef(NOTE,id) in view mode, linked items open in place, panel reloads on new version
- [x] New strings (if any) are in English and German (`res/values/strings_notes.xml` + `res/values-de/strings_notes.xml`)
  - verified (2026-11-09 08:31+0100 by dell26/agent-1): git diff 9fd07318f..HEAD -- android/app/src/main/res -> no changes; the panel reuses the existing links_* strings (en+de), no new strings needed
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
- Work done on `and-notes-editor` before the split is on `origin/wip/and-notes-editor` (if it exists): merge it first if your part builds on it.
- ASSUMPTION (2026-11-09 08:06+0100 by dell26/agent-1): The LinkedItemsPanel shows only in the note's view mode (below the text), only for saved notes; not while editing (the body field fills the screen there). Linked in-app items (task, note, mail, contact) open in place via SearchItemScreen after saving the note, like the contact detail; Screen targets are ignored (no navigation in the editor), web targets open in the browser.
