---
title: Note editor: revisions list and restore
status: in_progress
owner: dell26/agent-1
deps: [and-notes-list, and-links-panel, and-notes-tags]
prio: 2
parent: and-notes-editor
lease_until: 2026-11-09T06:58:01Z
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
- ASSUMPTION (2026-11-09 07:35+0100 by dell26/agent-1): Android revisions: 'Earlier versions' item in the editor's overflow menu opens a dialog (list newest first + read-only preview of the selected one, Restore/Close), like the web dialog. Restore first saves unsaved text (so it becomes a revision), then replaces title/body; tags/pin/archive stay. Restore failures reuse the existing 'Not changed: ...' editor messages instead of new strings.
