# Code Review

Date: 2026-11-08

Task: and-notes-checklist-links - Note editor: checklist toggle in preview and [[ link autocomplete

Scope: `git diff c741b2fc1..HEAD -- android` (package `ch.sonensei.cockpit.notes`, `res/values{,-de}/strings_notes.xml`), task commits 0090917b3, 4fe500427, d270787ff, 66a3335c3, merged via cb6840932. Re-review of the final code, including the tests added for the earlier finding CR-1.

Requirements:
- tasks/and-notes-checklist-links.md (acceptance criteria and the recorded ASSUMPTION: global search over all types, 250 ms debounce, up to 6 hits, inserts `[[<lowercase type>:<id>]]`, checklist tap only while editable)
- SPEC.md 4.3 (inline links `[[type:id]]`, `[[` autocomplete) and 4.4 (global search)
- docs/ARCHITECTURE.md, AGENTS.md project notes
- Previous review of this task (CR-1) and reviews/and-notes-checklist-links/tests.md

Git range:
- Branch: main
- HEAD: cb684093212279168a1a5b05cb04ce3023ffcc11
- Base/start: c741b2fc111a6310813ddc6b7e5e4517fb3328ca
- Commits reviewed: 4 (plus the merge commit cb6840932)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Status of the previous finding

- CR-1 (no ViewModel/Compose tests for the new behavior): **resolved.**
    - `NoteEditorChecklistLinksViewModelTest.kt` tests `toggleChecklist`: the body changes, the note is marked unsaved, and it autosaves after `AUTOSAVE_DELAY_MS` with the toggled draft. Toggling back needs no save. Non-checklist and out-of-range lines (0, 3, -1, 99) change nothing. Nothing toggles while the note loads or a conflict waits. It also tests `searchLinks`: the 250 ms debounce (no search at 249 ms), the trimmed query, hits flattened across all groups, the same query not searched twice, a blank query giving the hint without a search, `null` clearing the suggestions and cancelling a pending search, OFFLINE/FAILED mapping, and older hits kept while the next query is pending. It covers the out-of-order case too: a slow search for "a" that finishes after the newer "ab" results does not overwrite them.
    - `NoteEditorChecklistLinksScreenTest.kt` (Robolectric Compose) tests that tapping checklist items in the rendered note calls back with the right source line, that a plain list item does nothing, and that there are no toggle targets during a conflict. It checks that typing `See [[bo` reports query `bo` and shows no panel before the ViewModel answers. Picking a hit puts `See [[task:<id>]]` into the body and clears the search, the panel shows at most 6 hits, and the offline state still renders the panel.
    - `MarkdownTest.kt` expectations now include the new `ListItem.line` (4, 5 and 2, which are the correct source lines). `NoteEditorsTest.kt` passes the new `NoteLinkSearch` constructor argument. No assertions were loosened or removed.

## Notes (verified, no finding)

- The production code is the same as the code covered by the previous review. Only the commit hashes changed with the rebase. The notes in that review still hold: the link format matches the server `INLINE_LINK` regex and the web `LINK_TRIGGER`, the checklist line indices line up between `parse` and `toggleChecklist`, and the toggle goes through `change()`, so it honors `editable` and schedules the autosave.
- `NoteEditorContent` keeps its local `TextFieldValue` in sync with `state.body` (lines 220-224), so a toggle made in the preview shows up when the user goes back to editing. The suggestion panel needs the local `[[` query, `state.links` and `state.editable`, so after a pick (cursor after `]]`) or a cursor move away from the `[[`, stale suggestions are not shown.
- After a failed or offline search the user can retry by changing the query. The same-query check only skips an identical query string.
- Known, accepted test gaps (listed in tests.md): the panel texts for failed, hint, searching and none are not asserted (presentation only, strings checked by lint); a non-collapsed selection is not tested; the Hilt `NoteLinkSearchModule` is a one-line provider. None of these is a material risk.
- Not re-run by this reviewer. The parent reported that `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*'` passes, and tests.md records a full `ktlintCheck testDebugUnitTest lintDebug` run with exit 0. Criterion 4 still needs `sh scripts/check.sh` from the parent.

## Review conclusion

No material issues found.
