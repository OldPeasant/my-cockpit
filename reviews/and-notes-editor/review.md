# Code Review

Date: 2026-11-09

Task: and-notes-editor - Android note editor: tags, checklists, links, revisions

Scope: integration review of the note editor as it stands on HEAD. The task adds no code of its own (HEAD a84e8ba57 merges an obsolete wip branch; `git diff HEAD~1 HEAD` is empty). Its four subtasks (and-notes-checklist-links, and-notes-tags, and-notes-revisions, and-notes-linked-panel) are done and on main. Files reviewed in full: `android/app/src/main/java/ch/sonensei/cockpit/notes/` NoteEditorScreen.kt, NoteEditorViewModel.kt, NoteEditors.kt, NoteTagsEditor.kt, NoteRevisionsDialog.kt, Markdown.kt, MarkdownView.kt, the save/flags/revisions/tags calls in NotesRepository.kt, and `res/values{,-de}/strings_notes.xml`. Tests read: NoteEditorChecklistLinksViewModelTest, NoteEditorChecklistLinksScreenTest, MarkdownChecklistLinksTest, NoteEditorTagsViewModelTest, NoteTagsEditorTest, NoteEditorRevisionsViewModelTest, NoteRevisionsDialogTest, NoteEditorLinksPanelScreenTest, NoteEditorLinksRouteInAppTest.

Requirements:
- tasks/and-notes-editor.md acceptance criteria 1-7
- SPEC.md section 4.3 (inline links `[[type:id]]`, `[[` autocomplete, Linked items panel) and section 4.2 (ETag/If-Match)
- docs/PARITY.md (row "Note editor", and-notes-editor)
- AGENTS.md project notes (i18n, conventions)
- Cross-checked against server `NoteMarkdown.kt` (inline link format) and web `note-markdown.ts` (`inlineLinkMarkdown`)

Git range:
- Branch: main
- HEAD: a84e8ba57448cc91c892843f88dd8f21d56f4a6b
- Base/start: 685a8e6aa (claim commit); the integrated code comes from the subtasks already on main
- Commits reviewed: 1 (no net change); integrated code reviewed as of HEAD

What was checked (read-only; Gradle not run as instructed, a test-writer was using the build dir):
- Criterion 1, tags: `NoteTagsEditor` shows chips with a labelled remove button, adds on Enter, comma or focus loss, and suggests known tags (`NoteTagSource` -> `NotesRepository.tags()`, most used first, without tags already on the note). `addTag`/`removeTag` go through `change()`, so tags are saved in the same `notes.save(note, draft)` call as title and body (`NoteDraft(title, body, tags)`), with the same mutex, autosave and ETag (`etag(base.version)`).
- Criterion 2, checklist: `MarkdownView` makes checklist rows `toggleable(role = Checkbox)` only when `onToggleChecklist` is set, and the editor sets it only when `state.editable`. `toggleChecklist(line)` uses `change()`, the same path as typing: UNSAVED, autosave, conflict handling. Line indexes from `Markdown.parse` match `Markdown.toggleChecklist`. Checklist lines inside fenced code are not toggleable.
- Criterion 3, `[[`: `Markdown.linkQuery` gives the query at the cursor. `searchLinks` waits 250 ms and ignores an answer to an older query. `insertLink` writes `[[{lowercase type}:{uuid}]]`, which matches the server regex in `NoteMarkdown.INLINE_LINK` and the web `inlineLinkMarkdown`. The Hilt binding (`NoteLinkSearchModule`) uses the global search over all types.
- Criterion 4, revisions: the menu item opens `NoteRevisionsDialog`. Loading, empty, offline and failed states each have their own text, and a failed load offers Retry. Restore runs through `changeFlags`: it saves pending text first, then calls `restoreRevision(note, n)` with If-Match. On success `onSaved` sets note, title, body and tags from the returned note with status SAVED. The test `restore saves the text written here first...` asserts the tags too. During the restore nothing can be edited (`restoring`), and a restore is refused while a photo uploads.
- Criterion 5, links panel: the route places `LinkedItemsSection` (with a `ItemRef("NOTE", id)` view model) below the rendered text. It reloads on a new note version, so links the server syncs from inline links appear. Unsaved text is saved before a linked item opens in place.
- Criterion 6, strings: the key sets in `values/strings_notes.xml` and `values-de/strings_notes.xml` are identical (all `notes_tag_*`, `notes_link_*` and `notes_revisions_*` keys exist in both). No new hard-coded user-facing text in the editor files. Only the bullet and separator glyphs are literal.
- Feature interaction: the tag editor, checklist toggle, link insertion and revision restore all write through the one `MutableStateFlow` and the one `save()`/`saving` mutex path. None of them keeps its own save or ETag. The `useTheirs` and flag-conflict paths reset tags along with title and body. While a conflict waits the revisions dialog is hidden, the checklist is not tappable and tags are disabled.
- Criterion 7 (Gradle and check.sh results) was not run by this reviewer. `python tasks.py verify and-notes-editor` lists no recorded evidence yet. The parent must record it, so this is not a finding.

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Picking a link suggestion with the cursor inside an existing inline link corrupts it**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/notes/Markdown.kt:98` (linkQuery) and `:111` (insertLink); used at `NoteEditorScreen.kt:328-335`
    - Category: Correctness
    - Problem: `linkQuery` only looks backwards from the cursor (`\[\[([^\]\n]*)$`), so a cursor placed inside an existing link also counts as a query. Example: in `a [[task:0192...]] b` with the cursor after `[[task:01`, the query is `task:01`. The suggestion panel opens and a search for `task:01` is sent. If the user then picks a hit, `insertLink` replaces only `[[task:01`, because `]]` does not follow the cursor. The result is `a [[note:{id}]]92...]] b`, with the rest of the old link left behind as text.
    - Why it matters: a rare edit (moving the cursor into a link and then picking a suggestion), but it silently leaves broken Markdown. That costs an existing inline link and its synced `link` row. Moving the cursor through a link also sends search requests nobody asked for.
    - Suggested fix: in `linkQuery`, return `null` when the text after the cursor continues the same token up to `]]` without a newline or `[`, i.e. the cursor is inside a closed link. Add a case to `MarkdownChecklistLinksTest`.

## Review conclusion

No material issues found. All six feature criteria are implemented and work together in the one editor: tags, checklist toggle, link insertion and revision restore share one save and ETag path, a restore refreshes title, body and tags, and every new string has a German translation. The one P3 is optional cleanup. Criterion 7 (the Gradle run and `sh scripts/check.sh`) still needs evidence recorded by the parent worker.
