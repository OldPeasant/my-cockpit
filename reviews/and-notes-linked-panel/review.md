# Code Review

Date: 2026-11-09

Task: and-notes-linked-panel - Note editor: LinkedItemsPanel

Scope: `git diff 8c9fb0bfd..12e34fa37 -- android` (NoteEditorScreen.kt, new NoteEditorLinksPanelScreenTest.kt). HEAD 6aaa8e5bc has no further android changes relative to 12e34fa37.

Requirements:
- tasks/and-notes-linked-panel.md (acceptance criteria and the recorded assumption: panel only in view mode for saved notes; in-app linked items open in place via SearchItemScreen after saveNow())
- SPEC.md §4.3 (Linked items panel in every detail view; note inline links `[[type:id]]` are persisted into the `link` table)
- docs/ARCHITECTURE.md, AGENTS.md project notes
- Comparison with existing embeddings: ContactsScreen.kt, MailReadScreen.kt, TaskDetailScreen.kt, core/links/LinkedItemsPanel.kt

Git range:
- Branch: main
- HEAD: 6aaa8e5bc7784de183c7c2ee8622d910f162f9cd
- Base/start: 8c9fb0bfd4705cdca507bfd391ecd72e29a9a380
- Commits reviewed: 1 (12e34fa37b8586abc1d5044fc903031e8c4dc807)

Summary: 0 P0, 0 P1, 1 P2, 1 P3

Gradle was not run (per instruction; another helper runs it concurrently).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Links panel stays stale after the note's inline links change (edit, revision restore, reopen)**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorScreen.kt:231-243` (with `core/links/LinkedItemsPanel.kt:137,150` and `core/links/LinkedItemsViewModel.kt:87-88`)
    - Category: Correctness
    - Problem: The server syncs a note's inline links `[[type:id]]` into the link table on every save that changes the body (`server/.../notes/domain/NoteService.kt:106,225`; SPEC.md §4.3). The panel's view model is a `hiltViewModel(key = "links:NOTE:{id}")` scoped to the hosting ViewModelStoreOwner (the Notes / Dashboard / search nav entry), so it survives the panel leaving composition while editing and survives closing and reopening the same note. `LinkedItemsViewModel.open(item)` returns early for the same item ("the same item again keeps what is shown"), and nothing in the note editor calls `reload()`. Concrete flow: view a note (panel loads), tap Edit, insert `[[` link to a task via the suggestions, tap View (`setEditing(false)` triggers `saveSoon(0)`) - the panel reappears with the old list and never shows the new link; the same applies to deleting an inline link, restoring a revision with different links, or reopening the note from the list after it changed elsewhere. MailReadScreen handles the analogous case by calling `links?.reload()` when its content changes (`MailReadScreen.kt:126`); the note route has no equivalent.
    - Why it matters: The panel shows a wrong set of linked items in the normal editing flow of the very feature (inline links) whose links it is meant to render, and there is no user action in the editor that refreshes it short of leaving the Notes screen.
    - Suggested fix: Obtain the panel's view model in the route (as ContactsScreen/MailReadScreen do with `linksViewModelKey(ItemRef("NOTE", id))`, Hilt-guarded) and pass it to `LinkedItemsSection(..., viewModel = it)`; call `reload()` when a save that changed the body completes (e.g. a `LaunchedEffect` keyed on the saved note's version, `state.note?.version`) and once per opening. Add a test that a changed saved version triggers a reload.

## P3 Low

- [ ] **CR-2 | Route wiring for opening a linked item in place is untested**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorScreen.kt:153-160,232-242`
    - Category: Tests
    - Problem: `NoteEditorLinksPanelScreenTest` only covers the stateless `linksPanel` slot of `NoteEditorContent` (shown for saved notes in view mode, hidden while editing, loading, or for a new note). The new route behavior - an `InApp` target calls `viewModel.saveNow()` before switching to `SearchItemScreen`, back/close returns to the note, `Screen` targets are ignored - has no test. The existing `NoteEditorRouteTest` (fake view model) could at least assert that the open callback triggers a save and swaps the content; ContactsScreen/MailReadScreen have no such test either, so this follows project precedent and is low priority.
    - Why it matters: The "save before leaving the note" step is the part of the recorded assumption that protects unsaved view-mode changes (checklist toggles, tags); a regression there would go unnoticed.
    - Suggested fix: Extract the target handling into a small function (e.g. {openLinked}(target, save, open)) or expose it via a test seam, and test that `InApp` saves then opens, and `Screen`/`Web` do neither.

## Review conclusion

Material issues found; fix before completing the task.

The acceptance criteria are otherwise met by the code: the shared `LinkedItemsSection` is shown for the saved note below its text in view mode, with `ItemRef("NOTE", id)` matching the server type; no new strings were added (criterion 2 is vacuous); in-place opening mirrors ContactsScreen/MailReadScreen. CR-1 is a behavioral defect in normal use of the new panel and should be fixed (or explicitly accepted by the parent as out of scope via an assumption) before `done`.
