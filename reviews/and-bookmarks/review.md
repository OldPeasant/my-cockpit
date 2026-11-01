# Code Review

Date: 2026-11-01

Task: and-bookmarks - Android: Bookmarks screen (list, read later, open, edit)

Scope: git diff e775c74b2b952c94a7329b753dfe59155008dc6e..HEAD (commits b7eefdaef implementation, 1c3351ed5 tests); committed changes only

Requirements:
- tasks/and-bookmarks.md acceptance criteria and the five recorded assumptions (python tasks.py assumptions)
- SPEC.md §8.4, docs/ARCHITECTURE.md, AGENTS.md project conventions
- api/openapi.yaml (BookmarkPatchRequest, BookmarkResponse, /api/v1/trash/{type}/{id}/restore)
- Reference code: notes/, files/ list screens, web/src/app/features/bookmarks/, server bookmarks module (URL validation)

Git range:
- Branch: main
- HEAD: 1c3351ed55349bf1f6036a7e04bd324685fd0fcd
- Base/start: e775c74b2b952c94a7329b753dfe59155008dc6e
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 2 P2, 2 P3

Acceptance criteria check (from code and tests; Gradle results as reported by the parent: ktlintFormat and compileDebugKotlin clean, bookmarks tests 29/29):
- List with title, site, favicon (https only, else initial), notes and tags; views Read later (default) and All; tag filter; search: implemented (BookmarksScreen.kt, BookmarksViewModel.kt), covered by BookmarksViewModelTest and BookmarksScreenTest.
- Open via ACTION_VIEW with no-browser fallback; mark read/unread, edit title/tags/notes, delete to Trash with undo snackbar: implemented. Server only stores absolute http(s) URLs (server BookmarkUrls.kt), so ACTION_VIEW on the URL is safe.
- Pull to refresh, loading/empty/error states: implemented and tested.
- Share appears without restart: reload on resume and on CaptureQueue pendingCount drop; tested in the view model.
- Strings: values and values-de contain the same 31 keys; German test present.
- Tests: view model, repository (MockWebServer) and Robolectric Compose tests present. The full `ktlintCheck testDebugUnitTest lintDebug` run is the parent's evidence to record; not re-run here.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Overlapping equal failure snackbars get stuck and hide later failures**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksScreen.kt:568` (with `BookmarksViewModel.kt:182` and `:252`)
    - Category: Correctness
    - Problem: `LaunchedEffect(snackbar)` compares its key with `equals`, but `dismiss` clears the state only when the shown snackbar is the identical object (`===`). `BookmarksSnackbar.Failed` is a data class, so two failures of the same kind are equal but not identical.
    - Why it matters: Scenario: offline, the user marks bookmark A read and within ~4 s deletes bookmark B. Both fail with `Failed(OFFLINE)`. The second `show()` replaces the state with an equal object, so the effect does not restart. When the first snackbar ends, `dismiss(first)` does nothing because `state.snackbar` is now the second object. The state then keeps `Failed(OFFLINE)` for good, and every later `Failed(OFFLINE)` is equal to it, so it never shows. Later offline failures are silent until a different kind of snackbar (Trashed, NoBrowser, another failure kind) replaces it. The user gets no feedback that nothing changed.
    - Suggested fix: Give each snackbar its own identity. For example, add a unique id or sequence number to `Failed` (and `Trashed`), or key the effect by identity, and make `dismiss` match the same identity. Add a view model or screen test that shows two equal failures in a row.

- [ ] **CR-2 | A failed edit save throws away what the user typed**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksViewModel.kt:149-153`
    - Category: Correctness
    - Problem: `save` sets `editing = null` (the dialog closes) before the PATCH runs. If the PATCH fails, `failed()` only shows a snackbar. Offline, a 412/409 conflict and a 400 all end this way. The draft (title, tags, up to 2000 characters of notes) is not kept anywhere.
    - Why it matters: Scenario: the user writes a long note on a bookmark while the connection is down, or while another device changed the bookmark, and taps Save. The dialog closes and the snackbar says "Nothing was changed" or "changed elsewhere ... Try again". To retry, the user has to type everything again. The conflict text asks for a retry, but there is nothing left to retry with.
    - Suggested fix: Keep the dialog open until the save succeeds and show the error in the dialog or a snackbar. Or reopen the editor with the unsaved draft when the save fails (on a conflict, against the reloaded version). Add a view model test for a failed save that checks the draft is kept.

## P3 Low

- [ ] **CR-3 | An older list load can bring back a bookmark that was just deleted or marked read**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksViewModel.kt:106-108` (with `:160` and `:216-220`)
    - Category: Correctness
    - Problem: `refresh()` replaces `bookmarks` with whatever its GET returned. Changes (`delete`, `toggleRead`, `save`) do not cancel a running load or mark it outdated. Loads start on resume, on pull, on a share leaving the queue, and after undo.
    - Why it matters: Scenario: the list reloads because a queued share was just sent (pendingCount drops) or the app resumes. While that GET is running, the user deletes a bookmark or marks it read in the queue. The delete or PATCH answers first and removes the row. Then the older list arrives and puts the row back with its old version. The bookmark shows again until the next reload, and acting on it gives a 404 (silently dropped) or a 412 conflict snackbar.
    - Suggested fix: Cancel the running load and start a new one after a successful change. Or ignore a load result that started before the latest local change, for example with a change counter compared when the load completes.

- [ ] **CR-4 | Offline after switching view or tag says the last bookmarks are shown when the list is empty**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksViewModel.kt:127` and `:135`; text in `android/app/src/main/res/values/strings_bookmarks.xml:12` (and values-de)
    - Category: Quality
    - Problem: `selectView` and `selectTag` clear `bookmarks`. If the next load is Offline, `message` becomes OFFLINE, and its text reads "Can't reach the server. Showing the last loaded bookmarks." But the list is empty, and since `loaded` is false no empty text is shown either.
    - Why it matters: The message describes content that is not on the screen, which makes it look as if the user has no bookmarks in that view.
    - Suggested fix: Use a separate text such as "Can't reach the server." when nothing is loaded for the current view or tag (`!loaded`). Or keep the old list until the new one arrives.

## Review conclusion

Material issues found; fix before completing the task.
