---
title: Note editor: checklist toggle in preview and [[ link autocomplete
status: todo
owner:
deps: [and-notes-list, and-links-panel]
prio: 2
parent: and-notes-editor
---
## Goal
Part of `and-notes-editor` (Android note editor: tags, checklists, links, revisions), which was split.
Goal: Tapping a checklist item in the preview toggles it and saves like an edit; typing `[[` in the editor offers search-as-you-type and inserts `[[type:id]]`. Helpers `Markdown.toggleChecklist`, `Markdown.linkQuery`, `Markdown.insertLink` and `MarkdownView(onToggleChecklist)` are already on `origin/wip/and-notes-editor` (no tests yet); wire them into NoteEditorScreen/NoteEditorViewModel. Touches android/.../cockpit/notes, its tests and strings_notes.xml (en + de).

## Acceptance criteria
- [ ] Tapping a checklist item in preview toggles it (saved like an edit)
- [ ] Typing `[[` offers search-as-you-type and inserts `[[type:id]]`
- [ ] New strings are in English and German (`res/values/strings_notes.xml` + `res/values-de/strings_notes.xml`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
- Work done on `and-notes-editor` before the split is on `origin/wip/and-notes-editor` (if it exists): merge it first if your part builds on it.
