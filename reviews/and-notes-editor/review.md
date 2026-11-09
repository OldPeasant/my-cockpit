# Code Review

Date: 2026-11-09

Task: and-notes-editor - Android note editor: tags, checklists, links, revisions

Scope: integration review of the note editor as it stands on the final revision 0a620f04634a057c7ceff82a4bc65fc90bf0bf0e. The task adds no production code of its own: a84e8ba57 merges an obsolete wip branch (`git diff a84e8ba57~1 a84e8ba57` is empty). Its four subtasks (and-notes-checklist-links, and-notes-tags, and-notes-revisions, and-notes-linked-panel) are done and on main. The only code change in the task is the new test `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteEditorIntegrationTest.kt` in 0a620f046, re-reviewed below. Files reviewed in full: `android/app/src/main/java/ch/sonensei/cockpit/notes/` NoteEditorScreen.kt, NoteEditorViewModel.kt, NoteEditors.kt, NoteTagsEditor.kt, NoteRevisionsDialog.kt, Markdown.kt, MarkdownView.kt, the save/flags/revisions/tags calls in NotesRepository.kt, and `res/values{,-de}/strings_notes.xml`. Tests read: NoteEditorIntegrationTest (new), NoteEditorRouteTest, NoteEditorChecklistLinksViewModelTest, NoteEditorChecklistLinksScreenTest, MarkdownChecklistLinksTest, NoteEditorTagsViewModelTest, NoteTagsEditorTest, NoteEditorRevisionsViewModelTest, NoteRevisionsDialogTest, NoteEditorLinksPanelScreenTest, NoteEditorLinksRouteInAppTest.

Requirements:
- tasks/and-notes-editor.md acceptance criteria 1-7
- SPEC.md section 4.3 (inline links `[[type:id]]`, `[[` autocomplete, Linked items panel) and section 4.2 (ETag/If-Match)
- docs/PARITY.md (row "Note editor", and-notes-editor)
- AGENTS.md project notes (i18n, conventions)
- Cross-checked against server `NoteMarkdown.kt` (inline link format) and web `note-markdown.ts` (`inlineLinkMarkdown`)

Git range:
- Branch: main
- HEAD: 0a620f04634a057c7ceff82a4bc65fc90bf0bf0e (final reviewed revision)
- Base/start: 685a8e6aa (claim commit); the integrated code comes from the subtasks already on main
- Commits reviewed: 3 (a84e8ba57 merge with no net change, 1ba8b693e review report only, 0a620f046 new integration test and tests.md); integrated code reviewed as of HEAD
- Later merge commits from main on the local branch change nothing under `android/app/src/{main,test}/java/ch/sonensei/cockpit/notes` or `reviews/and-notes-editor` (`git diff --stat 0a620f046 HEAD` on those paths is empty).

What was checked (read-only; Gradle not run as instructed, the parent runs it concurrently):
- Criterion 1, tags: `NoteTagsEditor` shows chips with a labelled remove button, adds on Enter, comma or focus loss, and suggests known tags (`NoteTagSource` -> `NotesRepository.tags()`, most used first, without tags already on the note). `addTag`/`removeTag` go through `change()`, so tags are saved in the same `notes.save(note, draft)` call as title and body (`NoteDraft(title, body, tags)`), with the same mutex, autosave and ETag (`etag(base.version)`).
- Criterion 2, checklist: `MarkdownView` makes checklist rows `toggleable(role = Checkbox)` only when `onToggleChecklist` is set, and the editor sets it only when `state.editable`. `toggleChecklist(line)` uses `change()`, the same path as typing: UNSAVED, autosave, conflict handling. Line indexes from `Markdown.parse` match `Markdown.toggleChecklist`. Checklist lines inside fenced code are not toggleable.
- Criterion 3, `[[`: `Markdown.linkQuery` gives the query at the cursor. `searchLinks` waits 250 ms and ignores an answer to an older query. `insertLink` writes `[[{lowercase type}:{uuid}]]`, which matches the server regex in `NoteMarkdown.INLINE_LINK` and the web `inlineLinkMarkdown`. The Hilt binding (`NoteLinkSearchModule`) uses the global search over all types.
- Criterion 4, revisions: the menu item opens `NoteRevisionsDialog`. Loading, empty, offline and failed states each have their own text, and a failed load offers Retry. Restore runs through `changeFlags`: it saves pending text first, then calls `restoreRevision(note, n)` with If-Match. On success `onSaved` sets note, title, body and tags from the returned note with status SAVED. The test `restore saves the text written here first...` asserts the tags too. During the restore nothing can be edited (`restoring`), and a restore is refused while a photo uploads.
- Criterion 5, links panel: the route places `LinkedItemsSection` (with a `ItemRef("NOTE", id)` view model) below the rendered text. It reloads on a new note version, so links the server syncs from inline links appear. Unsaved text is saved before a linked item opens in place.
- Criterion 6, strings: the key sets in `values/strings_notes.xml` and `values-de/strings_notes.xml` are identical (all `notes_tag_*`, `notes_link_*` and `notes_revisions_*` keys exist in both). No new hard-coded user-facing text in the editor files. Only the bullet and separator glyphs are literal.
- Feature interaction: the tag editor, checklist toggle, link insertion and revision restore all write through the one `MutableStateFlow` and the one `save()`/`saving` mutex path. None of them keeps its own save or ETag. The `useTheirs` and flag-conflict paths reset tags along with title and body. While a conflict waits the revisions dialog is hidden, the checklist is not tappable and tags are disabled.
- Criterion 7 (Gradle and check.sh results) was not run by this reviewer. The parent records that evidence, so it is not a finding here.

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

## Re-review of 0a620f046 (NoteEditorIntegrationTest)

New file: `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteEditorIntegrationTest.kt` (182 lines, test only, no production change). Read in full and checked against the production code it drives. Gradle was not run by this reviewer; the test-writer reports the class passing within 343 notes tests and the full suite and `sh scripts/check.sh` passing (reviews/and-notes-editor/tests.md).

What the three tests do, and why they are sound:
- Setup follows `NoteEditorRouteTest`: Robolectric, `createComposeRule` (junit4.v2), a real `NoteEditorViewModel` with a mockk `NotesRepository`, a real `NoteImages`, and the `linkSearch`/`tagSource` constructor parameters as fakes. `NoteEditorRoute` is shown with `viewModel = vm`. All referenced test tags (`NoteTagsTags.INPUT/SUGGESTION/CHIP`, `NoteEditorTags.TOGGLE/BODY/LINK_HIT/LINK_SUGGESTIONS/MENU/REVISIONS`, `NoteRevisionsTags.ITEM/RESTORE/DIALOG`) and constants (`AUTOSAVE_DELAY_MS` 1000, `LINK_SEARCH_DELAY_MS` 250) exist in production code. Fixture data is fictitious.
- Tags plus checklist: picks the suggested `#travel` chip and taps the `passport` checklist row in the rendered view, asserts UNSAVED, advances the main looper by the autosave delay, then checks exactly one `repo.save` call, with `NoteDraft("Trip", toggled, listOf("travel"))` against the loaded note. That shows the two features end up in one debounced save, not two.
- `[[` link: types `See [[boo`, asserts no search before the 250 ms debounce and exactly `["boo"]` after it, picks the hit, and checks the body holds `[[task:{uuid}]]` (lowercase type, matching the server format) and the panel closes. Leaving edit mode saves that body once.
- Revision restore: toggles a checklist row (UNSAVED), opens the dialog from the menu, selects revision 1 and restores. `coVerifyOrder` checks the pending toggle is saved first and `restoreRevision` is then called with the saved note (version 4), so If-Match uses the newer version. The test then checks the dialog is closed, the restored title and checklist text are shown, the tag chip stays, and status is SAVED.

Flakiness: time is advanced explicitly with `shadowOf(Looper.getMainLooper()).idleFor(...)`. The view model's delays run on `Dispatchers.Main`, which is the Robolectric main looper, so this is deterministic and no wall-clock sleeps are used. Async results are waited for with `composeRule.waitUntil(5_000)` on concrete conditions (`status == SAVED && vm.settled`, hit count, restored body and `!restoring`), not with fixed waits. The UNSAVED assertions made right after a tap rely on Robolectric's paused looper not advancing the clock by itself; the existing NoteEditorChecklistLinksScreenTest uses the same pattern. No flakiness risk found.

Conventions: `ktlint_official` line limit is 140; the longest lines are 132 and 133 characters. No debug output, no suppressed lint, no `@Ignore`. No existing test was changed or weakened.

Limits, not findings:
- The Linked items panel (criterion 5) is not part of this test: `noteLinks` returns `null` outside a Hilt activity, so the panel does not render under `createComposeRule`. That is acceptable because NoteEditorLinksRouteInAppTest covers the panel in the Hilt app; the class KDoc of the new test does not claim the panel.
- The comment at line 95 says suggestions come "most used first", but the test only asserts that there are two suggestions, not their order. The order is covered by NoteTagsEditorTest and NoteEditorTagsViewModelTest, so this is not reported as a finding.

New findings from this re-review: none. CR-1 is unchanged and still applies to the production code at 0a620f046 (the new test does not touch `linkQuery`).

## Review conclusion

No material issues found. All six feature criteria are implemented and work together in the one editor: tags, checklist toggle, link insertion and revision restore share one save and ETag path, a restore refreshes title, body and tags, and every new string has a German translation. The new NoteEditorIntegrationTest at 0a620f046 is correct, deterministic and follows project conventions, and it adds route-level evidence that the features share one save path. The one P3 (CR-1) is optional cleanup. Criterion 7 (the Gradle run and `sh scripts/check.sh`) still needs evidence recorded by the parent worker.
