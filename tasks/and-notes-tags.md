---
title: Note editor: tag chips with suggestions
status: in_progress
owner: dell26/agent-1
deps: [and-notes-list, and-links-panel, and-notes-checklist-links]
prio: 2
parent: and-notes-editor
lease_until: 2026-11-09T04:23:30Z
---
## Goal
Part of `and-notes-editor` (Android note editor: tags, checklists, links, revisions), which was split.
Goal: Edit a note's tags in the Android editor as chips, with suggestions from existing tags (web: `note-editor-page.html`). Touches android/.../cockpit/notes, its tests and strings_notes.xml (en + de).

## Acceptance criteria
- [ ] Edit tags (chips with suggestions from existing tags), saved with the note
- [ ] New strings are in English and German (`res/values/strings_notes.xml` + `res/values-de/strings_notes.xml`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
- Work done on `and-notes-editor` before the split is on `origin/wip/and-notes-editor` (if it exists): merge it first if your part builds on it.
- ASSUMPTION (2026-11-09 04:44+0100 by dell26/agent-1): Android tags editor is shown in both the rendered view and the plain editor (below the title), editable whenever the note is; suggestions are the tags in use (most used first) minus the note's, filtered by the typed text, max 8, as tappable chips (web uses a datalist).
- ASSUMPTION (2026-11-09 04:44+0100 by dell26/agent-1): Android sends 'tags' in the note PATCH/POST only when they differ from the saved note's (create: when any), and adopts the server's spelling after a save when unchanged meanwhile; skipped merging origin/wip/and-notes-editor (its checklist/link WIP is superseded by the done and-notes-checklist-links).
