---
title: Android note editor: tags, checklists, links, revisions
status: in_progress
owner: dell26/agent-1
deps: [and-notes-list, and-links-panel, and-notes-checklist-links, and-notes-tags, and-notes-revisions, and-notes-linked-panel]
touches: [android/app/src/main/java/ch/sonensei/cockpit/notes,android/app/src/test/java/ch/sonensei/cockpit/notes,android/app/src/main/res/values/strings_notes.xml,android/app/src/main/res/values-de/strings_notes.xml]
prio: 2
lease_until: 2026-11-09T08:08:37Z
subtasks: and-notes-checklist-links, and-notes-tags, and-notes-revisions, and-notes-linked-panel
releases: 0
extended: 
---
## Goal
The Android note editor lacks tags, checklist toggling in the preview, `[[` link autocomplete, revisions and the
links panel (web: `note-editor-page.html`).

## Acceptance criteria
- [x] Edit tags (chips with suggestions from existing tags)
  - verified (2026-11-09 08:52+0100 by dell26/agent-1): NoteEditorIntegrationTest (picks suggested tag chip + checklist toggle via real NoteEditorRoute/ViewModel) plus NoteTagsEditorTest/NoteEditorTagsViewModelTest: ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*' -> 343 passed
- [x] Tapping a checklist item in preview toggles it (saved like an edit)
  - verified (2026-11-09 08:52+0100 by dell26/agent-1): NoteEditorIntegrationTest: preview checklist tap toggles body and is saved in the same autosave (If-Match) as tag edit; notes tests -> 343 passed
- [x] Typing `[[` offers search-as-you-type and inserts `[[type:id]]`
  - verified (2026-11-09 08:53+0100 by dell26/agent-1): NoteEditorIntegrationTest: typing [[boo searches after 250 ms debounce, picking hit inserts [[task:{id}]], Done saves; MarkdownChecklistLinksTest; notes tests -> 343 passed
- [x] Revisions: list and restore
  - verified (2026-11-09 08:53+0100 by dell26/agent-1): NoteEditorIntegrationTest: Earlier versions lists 2 revisions, Restore saves pending text then restores, title/body/tags shown; NoteRevisionsDialogTest, NoteEditorRevisionsViewModelTest -> pass (343 notes tests)
- [ ] `LinkedItemsPanel` for the note
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- split by felix/agent-1: into and-notes-checklist-links, and-notes-tags, and-notes-revisions, and-notes-linked-panel. Checklist toggle and [[ link query helpers (Markdown.kt, MarkdownView.kt) are committed on wip but have no tests and are not wired into the editor yet. The subtasks are chained because they all change NoteEditorScreen/NoteEditorViewModel. Once they are done, this task is only integration: merge what is left on wip/and-notes-editor (if anything), verify the acceptance criteria end to end, done.
