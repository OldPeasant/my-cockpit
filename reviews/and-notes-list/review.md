# Code Review

Date: 2026-11-08

Task: and-notes-list - Android notes list: search, pin, archive, delete

Scope: `git diff 0848f1a7..18bbf04a`, production code only: Android notes package (`android/app/src/main/java/ch/sonensei/cockpit/notes`) and `strings_notes.xml` (en, de). A test-writer adds tests in parallel, so the test changes are not reviewed here.

Requirements:
- tasks/and-notes-list.md (acceptance criteria and the recorded assumptions #1-#3)
- SPEC.md §8.1 (Notes: pinned first, filter by tag, search)
- docs/PARITY.md (row "Notes: no search, pin, archive, delete", Android, and-notes-list)
- docs/ARCHITECTURE.md, AGENTS.md project notes
- Web reference: web/src/app/features/notes/list (notes-page, note-card, messages.de.xlf)

Git range:
- Branch: main
- HEAD: 18bbf04ac3150e5f75341991cdc72f07926ec60a
- Base/start: 0848f1a7fa85594323f98d002f50a1f4477b4bf5
- Commits reviewed: 1 (18bbf04a)

Summary: 0 P0, 0 P1, 1 P2, 4 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Clearing the search during a running search leaves the list loading forever**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/notes/NotesListViewModel.kt:143`
    - Category: Correctness
    - Problem: `search()` sets `loading = true` (line 156) before it calls `notes.search(query)` and only sets it back in the `state.update` after the call (lines 158-164). `setQuery("")` (the clear button or deleting the text) cancels `searchJob` (line 143) and sets only `hits = null`. If that happens while the request is running, the coroutine is cancelled before the update, so `loading` stays `true`. Nothing resets it until the next `refresh()` finishes. A related case: `refresh()` with a search text starts the load and `search(0)` together. When the load finishes first, it sets `loading = false` (line 112) while the search is still running, and the indicator disappears too early.
    - Why it matters: The progress bar keeps running with nothing loading. The banner's Retry button is disabled while `loading` is true (`NotesScreen.kt:346`), so if a message is shown, the user cannot recover by tapping Retry. They have to leave the list or change a filter. This is the "every path, including cancellation and clearing the input while a request runs, ends loading" rule.
    - Suggested fix: Track loading for the load and the search separately (for example `loadingList` and `searching` flags, or derive `loading` from both jobs). Reset the search flag when the search is cleared or cancelled, for example with `try/finally` in the search coroutine or explicitly in `setQuery("")`. Add a unit test: type a query, clear it while `search` suspends, then assert `loading == false`.

## P3 Low

- [ ] **CR-2 | The results of an older search are shown under a new search text**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/notes/NotesListViewModel.kt:141`
    - Category: Correctness
    - Problem: When the query changes from one non-blank text to another, `hits` keeps the ids of the previous query until the new answer arrives: through the 300 ms debounce and the request. If the new search fails, the old hits stay indefinitely (`?: it.hits`, line 160). `shown` filters by these old ids, and `emptyText` then says "No notes match “<new text>”" based on the old query's result. Also, a successful search keeps an earlier search's error banner (`message(result) ?: it.message`, line 162). Only a list reload clears it.
    - Why it matters: For a moment, or after a failed search for good, the list and the empty text do not match the text in the search field.
    - Suggested fix: Set `hits = null` when the trimmed query changes (the screen already treats `hits == null` as "not answered yet"). On a failed search, keep `hits = null` instead of the old value. Let a successful search clear the message it set itself.

- [ ] **CR-3 | A list load that started before a pin, archive or delete can bring back the old note**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/notes/NotesListViewModel.kt:110`
    - Category: Correctness
    - Problem: `refresh()` replaces `notes` with the server's answer when the answer arrives. `toggleArchived`, `delete` and `togglePin` change `notes` locally (`remove`/`replace`, lines 181, 193, 171) without cancelling a load that is already running or dropping its result. A load that started before the change (for example the refresh after an Undo, after a conflict, or when the list is shown) and finishes after it puts the archived or trashed note back, or the old pin state, with its old version. The next action on that row then fails with 412 and shows "changed elsewhere".
    - Why it matters: This is the out-of-order async rule: an older response overwrites newer state. The effect is temporary (the next refresh fixes it), so the priority is low.
    - Suggested fix: After a successful change, call `refresh()` again, or keep a list version counter that a change increments and ignore load results that started at an earlier version.

- [ ] **CR-4 | After a pin/archive conflict in the editor, "Keep mine" writes the older text over the other device's edit, and the pin is lost**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorViewModel.kt:376`
    - Category: Correctness
    - Problem: `changeFlags` saves the text first, so the user has no unsaved edits when the flag PATCH fails with 409/412. It then opens the save-conflict banner ("This note was changed on another device while you edited it", Keep mine / Use theirs). If the user chooses Keep mine (`keepMine`, line 256), the unchanged, older body and title are saved over the server's newer version. The pin or archive the user asked for is applied with neither choice, and no message says so. Assumption #2 records that the conflict banner is used, but not this effect.
    - Why it matters: The user only tried to pin or archive, but one choice in the banner silently undoes another device's edit.
    - Suggested fix: For a flag conflict without local edits, take the server's version (as `useTheirs` does) and show the existing `notes_change_conflict`/`CHANGE_FAILED` style message, or retry the flag over the new version. Open the full conflict banner only when there is local text to keep.

- [ ] **CR-5 | The German "Unpin" label differs from the web**
    - File: `android/app/src/main/res/values-de/strings_notes.xml:50`
    - Category: Quality
    - Problem: `notes_unpin` is "Loslösen". The web translates the same action as "lösen" (`web/src/locale/messages.de.xlf:9821`, `notes.unpinAria`). "Loslösen" is an unusual word for this action.
    - Why it matters: The task asks for web/Android parity, and the same action has a different label on each platform.
    - Suggested fix: Use "Lösen" (as on the web) or "Nicht mehr anheften".

## Review conclusion

Material issues found; fix before completing the task.

All four acceptance criteria are implemented in the production code. Search uses the server's `/api/v1/search?types=NOTE` (assumption #1). Pin, archive and delete are in the row menu and in the editor menu, and the Archived view is a segmented toggle. Delete goes to the Trash, and the snackbar's Undo calls `POST /api/v1/trash/NOTE/{id}/restore`, also after a delete from the editor. The new strings exist in `values/` and `values-de/`, with the same keys. CR-1 is a loading-state defect in a normal flow (clearing the search) and should be fixed. CR-2 to CR-5 are minor. Criterion 5 (tests, lint, `scripts/check.sh`) was not assessed: the test-writer adds the tests in parallel, and this review did not run the build.
