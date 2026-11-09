# Code Review

Date: 2026-11-09

Task: and-notes-linked-panel - Note editor: LinkedItemsPanel

Scope: round 2 on the final code, `git diff 9fd07318f..e07cfb3b5 -- android` (NoteEditorScreen.kt, NoteEditorLinksPanelScreenTest.kt, NoteEditorLinksRouteInAppTest.kt). Round-1 findings CR-1 and CR-2 re-checked against reviews/and-notes-linked-panel/fixes.md.

Requirements:
- tasks/and-notes-linked-panel.md (acceptance criteria and the recorded assumption: panel only in the view of a saved note; in-app linked items open in place via SearchItemScreen after saveNow(); Screen targets ignored, web targets open in the browser)
- SPEC.md §4.3 (linked items panel in every detail view; inline links `[[type:id]]` synced into the link table on save)
- docs/ARCHITECTURE.md, AGENTS.md project notes
- Existing embeddings for comparison: core/links/LinkedItemsPanel.kt, core/links/LinkedItemsViewModel.kt, search/SearchItemScreen.kt, notes/NotesScreen.kt, notes/NoteEditors.kt, NoteEditorViewModel.kt

Git range:
- Branch: main
- HEAD: e07cfb3b536a19219f7a3de40e8bcda074b5cecc
- Base/start: 9fd07318fb01864c36cd0950d41f412a8fabd24a
- Commits reviewed: 3 (da6ecadbf, 9e6b8e797, e07cfb3b5); no uncommitted changes

Summary: 0 P0, 0 P1, 0 P2, 1 P3

Gradle was not run (per instruction; another helper runs it concurrently). Findings are from reading the code and tests.

## Round-1 findings

- CR-1 (stale panel after the note's inline links change): resolved. `NoteEditorRoute` now obtains the panel's view model itself via `noteLinks(state.note?.id)` (same key `linksViewModelKey(ItemRef("NOTE", id))` as `LinkedItemsSection` would use, Hilt-guarded like ContactsScreen/MailReadScreen) and passes it to `LinkedItemsSection`. `LaunchedEffect(links, state.note?.version)` calls `reload()` only once the view model already shows this note, so a new saved version (edit, checklist toggle, revision restore) and a reopened note reload, while the first open is still loaded once by the panel's own `open()`. `LinkedItemsViewModel.load()` cancels the previous load and keeps the shown links while reloading, so overlapping reloads resolve to the latest and do not flicker. Covered by the two new route tests (`a new version of the note saved with an inline link loads the panel's links again`, `the note opened again loads its links again`) plus the `exactly = 1` assertion on first open.
- CR-2 (route wiring untested): resolved. NoteEditorLinksRouteInAppTest drives the real route in MainActivity with fake repositories and covers in-place opening, close and system back returning to the note, save-before-open ordering, and a Screen target keeping the note open.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Panel links are reloaded on every autosave while the panel is hidden in edit mode**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorScreen.kt:160-163`
    - Category: Quality
    - Problem: The reload effect is keyed only on `state.note?.version`. While editing, `NoteEditorViewModel` autosaves 1 s after each pause in typing (`AUTOSAVE_DELAY_MS`, `saveSoon(AUTOSAVE_DELAY_MS)` at NoteEditorViewModel.kt:330), and each saved version triggers `links.reload()`, i.e. a `GET` of the note's links, although `NoteEditorContent` does not compose the panel in edit mode. Only the last of these results is ever shown, after switching back to the view (which saves once more and reloads again).
    - Why it matters: Extra network calls per autosave during normal editing (more on a slow or metered connection); no wrong data is shown, so the impact is small.
    - Suggested fix: Skip the reload while `state.editing` is true and reload when the view is shown again with a version newer than the last one loaded (e.g. remember the last reloaded version and include `state.editing` in the effect keys). Optional; acceptable to leave as is.

## Review conclusion

No material issues found.

All acceptance criteria are met by the code: the shared `LinkedItemsSection` is shown for the saved note below its text in the view (criterion 1), with `ItemRef("NOTE", id)` matching the server type and the recorded assumption's behavior for in-app, Screen and Web targets; no new strings were added, so criterion 2 needs no translations; screen tests for the `linksPanel` slot and Hilt route tests cover the new behavior (criterion 3, test part). Whether `./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass was not verified by this review and must be recorded by the parent worker.
