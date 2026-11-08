# Code Review

Date: 2026-11-08

Task: and-notes-checklist-links - Note editor: checklist toggle in preview and [[ link autocomplete

Scope: committed changes of the task, `git diff 1f9b236ce..HEAD -- android` (commits 2e03999cf, 15be26a4d, dc174e159); uncommitted test files of the concurrent test-writer were ignored.

Requirements:
- tasks/and-notes-checklist-links.md (acceptance criteria and the recorded assumption)
- SPEC.md 4.3 (inline links `[[type:id]]`, `[[` autocomplete) and 4.4 (global search)
- docs/ARCHITECTURE.md, AGENTS.md project notes
- Cross-checked server `notes/domain/NoteMarkdown.kt` (INLINE_LINK regex, lower-case ItemType) and web `features/notes/editor/markdown-editor.ts` (LINK_TRIGGER)

Git range:
- Branch: main
- HEAD: dc174e15964a2080e4eed8f3e15df81b1212d79a
- Base/start: 1f9b236cecc7df569344fa6ebb10786079d68d8f
- Commits reviewed: 3

Summary: 0 P0, 1 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | New ViewModel and Compose behavior has no committed tests**
    - File: `android/app/src/test/java/ch/sonensei/cockpit/notes/MarkdownChecklistLinksTest.kt:1`
    - Category: Tests
    - Problem: The committed tests cover only the pure helpers `Markdown.toggleChecklist`, `Markdown.linkQuery`, `Markdown.insertLink` and the `line` field set by `parse`. Nothing tests `NoteEditorViewModel.toggleChecklist` (marks the note unsaved and autosaves, does nothing when the note is not editable), `NoteEditorViewModel.searchLinks` (250 ms debounce, a slower older search must not replace newer results, OFFLINE/FAILED mapping, clearing with `null`), `MarkdownView(onToggleChecklist)` (tapping a `markdown-check-item` calls back with the right line, read-only when the callback is `null`), or the `NoteEditorContent` flow (typing `[[` shows `note-link-suggestions`, tapping a `note-link-hit` puts `[[<lowercase type>:<id>]]` into the body with the cursor after it, no toggling in preview while there is a conflict).
    - Why it matters: Criterion 4 asks for "Unit/Compose tests cover the new behavior". Criteria 1 and 2 describe the wiring and the async search. Right now the helper tests are the only evidence for them, and the stale-result guard in `searchLinks` is exactly the kind of out-of-order defect that unit tests of pure helpers can't catch.
    - Suggested fix: Add ViewModel tests with a fake `NoteLinkSearch` and a test dispatcher (debounce, a stale result dropped, errors mapped, toggle saved like an edit and ignored when not editable). Add Robolectric Compose tests for `MarkdownView` toggling and for the suggestions panel insert in `NoteEditorContent`. The test-writer may already be adding these. If its committed tests cover these cases, this finding is resolved.

## P2 Medium

None.

## P3 Low

None.

## Notes (verified, no finding)

- Link format matches the server and web: `hit.type.lowercase()` (Kotlin `lowercase()` uses Locale.ROOT) of server ItemType names produces `[[task:<uuid>]]`. That matches the server `INLINE_LINK` regex `[A-Za-z_]+:<uuid>`. The Android trigger regex `\[\[([^\]\n]*)$` is the same as the web `LINK_TRIGGER`.
- Checklist line indices line up: `parse` normalizes CRLF before it splits on `\n`, and `toggleChecklist` splits on `\n` only, so both get the same line count. Fenced code lines never produce a `ListItem`. The toggle offset is computed relative to the `rest` group, which is correct.
- The toggle goes through `change()`, so it honors `editable` and schedules the autosave like a text edit. The screen also passes the callback only when `state.editable`.
- `searchLinks` cancels the previous job, and it applies a result only when `links.query` still equals the query it searched for. `SearchRepository.search` maps IO, serialization and HTTP failures to results and rethrows cancellation, so an error ends `searching`.
- The new strings exist in both `values/strings_notes.xml` and `values-de/strings_notes.xml`.

## Review conclusion

Material issues found; fix before completing the task. (CR-1 is resolved if the test-writer's committed tests cover the listed behavior.)
