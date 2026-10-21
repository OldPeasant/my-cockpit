# Fix Report

Date: 2026-10-21

Task: and-notes - Android notes

Scope: findings CR-1 to CR-6 of `reviews/and-notes/review.md` (all accepted by the parent worker as valid and in scope), inside `android/app/src/main/java/ch/sonensei/cockpit/notes`, its tests, and `android/app/src/main/res/values/strings_notes.xml`.

## Fixed findings

### CR-1 — After a failed load, an existing note becomes editable as a new note

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorViewModel.kt`

Problem:
When loading an existing note failed, `editable` was true with `note == null`, so Edit, typing and photos created a separate new note, and Retry then did nothing because `note` was set.

Fix:
New state flag `unloaded` (an existing note was asked for and is not loaded). It is set by `open(id)` and cleared only by a successful load. `editable` is false while it is set, and `save()` refuses to run while it is set. `retryLoad` now keys on `unloaded` instead of `note != null`, so Retry keeps working until the note is loaded.

Validation:
- New test `after a failed load nothing can be edited, saved or uploaded until the retry loads the note` (NoteEditorViewModelTest) — Pass

### CR-2 — Reopening a note reuses the old editor view model and shows stale text

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorScreen.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/notes/NotesScreen.kt`

Problem:
The editor view model for a note id is scoped to the Notes back stack entry. Opening the same note again returned the same instance, and `open()` ignored the call, so the editor showed the text and version from the earlier visit.

Fix:
Every opening of an existing note now gets a unique token (`<id>#<random>`, kept in `rememberSaveable`). It is passed to `NoteEditorRoute(opening = ...)` and to `open(id, opening)`. The view model key is still the note id, so the number of retained view models does not grow with each opening. A call with the same token, for example after a configuration change, does nothing. A new token reloads the note from a fresh state when nothing is pending: no unsaved, unsent or failed text, no running save, upload, load or conflict. A note closed after "discard" on a deleted note is also reloaded. When something is pending, the local text stays and its save goes through as before.

Validation:
- New tests `a note opened again is loaded again, but not on the same opening`, `a note opened again while its text is not saved keeps that text`, `a note deleted elsewhere and discarded is loaded again when opened again` (NoteEditorViewModelTest) — Pass
- New test `a note opened again is loaded again` (NoteEditorRouteTest, Robolectric) — Pass

### CR-3 — Web images are fetched through the authenticated, server-rewriting client

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteImages.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/notes/MarkdownView.kt`
- `android/app/src/main/res/values/strings_notes.xml`
- `android/app/src/test/java/ch/sonensei/cockpit/notes/MarkdownViewTest.kt`

Problem:
`![](https://...)` images went to Coil with the app's OkHttp client. That client rewrites every request to the Cockpit server and adds the access token.

Fix:
`ImageSource.url()` now returns `null` for `ImageSource.Web`, so only `cockpit-file:` images go through the authenticated loader. A web image is rendered as a placeholder with the text "Remote image not loaded: <address>" (test tag `markdown-web-image`) and is never requested. The existing test that asserted the web URL was passed through was changed to assert the new behaviour (`null`), as required by the finding.

Validation:
- `a web image is not loaded, a drive image goes through the authenticated endpoint` (asserts `url()` is null for web) — Pass
- `images load through the notes image loader, described by their alt text` now also asserts the placeholder text and that the client's interceptor never saw a request for the web image — Pass

### CR-4 — The list reloads before the just-edited note is saved

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorScreen.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/notes/NotesScreen.kt`

Problem:
The system back closed the editor without `saveNow()`. The list then refreshed at once, before the save landed.

Fix:
`saveNow()` now cancels the pending autosave delay, starts the save directly and returns its `Job`. The `BackHandler` moved into `NoteEditorRoute`, so the system back and the toolbar back share one `close` that calls `saveNow()` and passes the job to `onClose(saving: Job?)`. `NotesRoute` keeps that job and, when the list is shown, waits for it (`saving?.join()`) before `viewModel.refresh()`.

Validation:
- New test `save now returns a job that ends once the text is saved` (NoteEditorViewModelTest) — Pass
- New test `the system back also saves at once, and hands the save to the list` (NoteEditorRouteTest, presses the activity's `onBackPressedDispatcher`) — Pass
- Existing `NotesScreenInAppTest` (Hilt activity, new note and back) — Pass

### CR-5 — A photo uploaded while a conflict appears is silently dropped

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorViewModel.kt`

Problem:
When a conflict came up during an upload, the returned image Markdown was discarded.

Fix:
The returned Markdown and its cursor position are kept (`pendingImage`; there is at most one, because only one upload runs at a time and none can start during a conflict). "Keep my version" inserts it at its cursor. "Use the other version" inserts it at the end of the server's text and saves. Discarding a note that was deleted elsewhere closes the editor and drops the image. Reopening the note also clears it.

Validation:
- New tests `a photo uploaded while a conflict came up is inserted once the user keeps their text` and `... is added to the other version when the user takes it` (NoteEditorViewModelTest) — Pass

### CR-6 — Missing tests for the failed-load and tag-switch race paths

Files changed:
- `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteEditorViewModelTest.kt`

Problem:
There was no test for editing or adding a photo after a failed load, and none for switching tags while an earlier list load is still pending.

Fix:
Added the failed-load test listed under CR-1: no edit, title, body, photo or save after a failed load, then Retry loads the note and it becomes editable. The tag-switch race test already exists in `NotesListViewModelTest` (`a list for an earlier filter that answers last is dropped`, added in commit 17f9578f after the reviewed revision). It uses a deferred `list(null)`, calls `selectTag("work")` and asserts that the late result is not applied, so no new list test was needed.

Validation:
- `NotesListViewModelTest` (7 tests) — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*'` — Pass (104 tests across 10 classes: NoteEditorViewModelTest 32, NoteEditorRouteTest 8, NotesScreensTest 16, NotesRepositoryTest 17, MarkdownTest 11, NotesListViewModelTest 7, NotePhotoFilesTest 5, NoteOrderTest 4, MarkdownViewTest 3, NotesScreenInAppTest 1; 0 failures)

Full test suite:
- `sh scripts/check.sh` (includes all Android unit tests) — Pass ("all checks passed")

Lint/type-check/build:
- `cd android && ./gradlew ktlintCheck lintDebug` — Pass

`.taskcheck`:
- Pass (`sh scripts/check.sh`: server, web and android checks, "all checks passed")

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
