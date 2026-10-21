# Code Review

Date: 2026-10-21

Task: and-notes - Android notes

Scope: `git diff 9872626aae5d1f08309397788ffdc91eea3f4c42..HEAD -- android` (committed changes only)

Requirements:
- tasks/and-notes.md acceptance criteria and recorded assumptions
- SPEC.md §8.1 (Notes), §9.2 item 6
- api/openapi.yaml (`/api/v1/notes`, `/api/v1/notes/{id}`, `/api/v1/notes/{id}/images`, `/api/v1/files/{id}/content`)
- docs/ARCHITECTURE.md and AGENTS.md project conventions
- Parent instructions: security (no note contents logged, FileProvider scope), autosave concurrency, tests

Git range:
- Branch: main
- HEAD: 39a6326bab9f04e491a1fa1085fa32ab929600d0
- Base/start: 9872626aae5d1f08309397788ffdc91eea3f4c42
- Commits reviewed: 2 (81c82c16 implementation, 39a6326b tests; the parent named them ffcd24f7/b76c72ec, which are the same commits before a rebase)

Summary: 0 P0, 0 P1, 3 P2, 3 P3

Checked and found in order: the API contract (PATCH with `If-Match` `"<version>"`, matching the server's `ETags.of`; only `title`/`bodyMd` sent; `null` title clears it; multipart part `file`); conflict mapping (404/409/412 re-fetch the note, deleted note = `Conflict(null)`); autosave serialisation (one `Mutex`, only the delay is cancelled, each save goes over the version the previous one returned, typed text during a save is saved next); list ordering and cancellation of an older list load on tag change; FileProvider (not exported, `grantUriPermissions`, only `cache/notes-photos/`); no logging of note content (`NotesHttpFailure` carries only the status); Coil memory-only cache. No evidence is recorded yet with `tasks.py verify` (both criteria unticked); that is for the parent to do before `done`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | After a failed load, an existing note becomes editable as a new note and edits create a separate note**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorViewModel.kt:51`
    - Category: Correctness
    - Problem: `editable` is `!loading && conflict == null && !closed`. When `load(id)` fails (offline or error), `loading` is false and `note` stays `null`, so `editable` is true. The editor bar then shows Edit, camera and gallery (`NoteEditorScreen.kt:282`). Typing makes `save()` call `notes.save(null, draft)` (line 152), which POSTs a brand new note. Adding a photo calls `create()` (line 213), which creates a new empty note. Once `note` is set, `retryLoad` returns early (line 89), but the "could not load, Retry" banner stays on screen and its button does nothing.
    - Why it matters: Opening an existing note while offline or on a flaky network and pressing Edit or a photo button silently creates duplicate or stray notes. The real note is never loaded in that editor, and the user believes they edited it.
    - Suggested fix: Track "an existing note was requested but not loaded" (for example, keep the requested id) and make `editable` false until the load succeeds. Add a view-model test: load fails, then `setEditing(true)`, `setBody` or `addPhoto` must not call `repo.save(null, ...)`.

- [ ] **CR-2 | Reopening a note reuses the old editor view model and never reloads it, so it shows stale text**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorScreen.kt:105`, `NoteEditorViewModel.kt:76-78`
    - Category: Correctness
    - Problem: `hiltViewModel(key = "note:$key")` is scoped to the Notes `NavBackStackEntry` (`CockpitShell.kt:88`), not to the opened editor. Closing the editor (`open = null` in `NotesScreen.kt:92`) does not clear it. When the same note id is opened again, the same instance comes back, and `open()` returns immediately because `opened` is already true. The editor shows the text and version from the earlier visit. The list, which refreshes every time it is shown, already shows the newer server text.
    - Why it matters: A note changed on the web (or deleted) after it was first opened on the phone shows outdated content. The first edit then runs into a 412 conflict that the user did not cause. Every "new:<uuid>" editor also leaves a view model behind for the lifetime of the Notes tab entry.
    - Suggested fix: Scope the editor view model to one opening: for example, a key that is unique per opening, as already done for new notes, and clear it on close. Or reload in `open()` when the editor is shown again and no unsaved text or conflict is pending.

- [ ] **CR-3 | Web images in notes are fetched through the authenticated client and rewritten to the Cockpit server**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteImages.kt:36`, `NoteImages.kt:44`, `MarkdownView.kt:161-163`
    - Category: Correctness / Security
    - Problem: `ImageSource.Web` URLs (any `![](https://...)` that is not `cockpit-file:`) go to Coil, which uses the app's shared `OkHttpClient`. That client's `ServerUrlInterceptor` (`core/network/ServerUrl.kt:127-134`) replaces scheme, host and port of every request with the configured server and keeps only the path and query. `https://example.org/pic.png` therefore becomes an authenticated `GET https://<cockpit-server>/pic.png` with the access token.
    - Why it matters: External images in notes can never render. Note content can also make the app send authenticated GET requests to arbitrary paths on the user's own server. SPEC.md treats remote images cautiously elsewhere (mail blocks them by default).
    - Suggested fix: Use the authenticated loader only for `CockpitFile` sources. For `Web` sources, show a placeholder or link, or load them with a separate client that has no server-rewrite or token interceptors (a deliberate decision, recorded with `tasks.py assume`). Add a test that maps a web URL to the non-authenticated path or placeholder.

## P3 Low

- [ ] **CR-4 | The list reloads before the just-edited note is saved, so it can show the old text**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/notes/NotesScreen.kt:78`, `NotesScreen.kt:93`, `NoteEditorScreen.kt:135-138`
    - Category: Correctness
    - Problem: The system back (`BackHandler(onBack = close)`) closes without `saveNow()`, so the save only happens when the 1 s autosave delay ends. The toolbar back calls `saveNow()`, which only schedules the save. In both cases the list's `LaunchedEffect(Unit) { viewModel.refresh() }` starts its GET at once and can get the note before the PATCH lands. The comment says the reload exists to show "a note edited a moment ago" with its new text, which this defeats.
    - Why it matters: The edited note can show outdated text and position in the list until the next refresh.
    - Suggested fix: Call `saveNow()` from the system back path too, and refresh the list after the pending save finishes (for example, expose a "saved" signal or a suspend save before closing).

- [ ] **CR-5 | A photo uploaded while a conflict appears is silently not inserted**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorViewModel.kt:219-225`
    - Category: Correctness
    - Problem: If a conflict is raised while the upload runs, `editable` is false when the upload returns. The returned Markdown is then dropped with no message. The file stays stored in `/Attachments/Notes`, but no note refers to it.
    - Why it matters: The user sees the upload finish and the photo disappear, with no hint.
    - Suggested fix: Keep the pending image Markdown and insert it after "Keep my version", or show a message that the photo was not added.

- [ ] **CR-6 | Missing tests for the failed-load and tag-switch race paths**
    - File: `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteEditorViewModelTest.kt:246`, `android/app/src/test/java/ch/sonensei/cockpit/notes/NotesListViewModelTest.kt:42`
    - Category: Tests
    - Problem: `a failed load can be retried` does not check that nothing is editable or saved between the failure and the retry (the gap that hides CR-1). No test switches tags while an earlier list load is still running to show that the older result cannot overwrite the newer one, which `refresh()` handles by cancelling the job.
    - Why it matters: These are the regression-prone async paths in this task. The list cancellation is correct today, but nothing guards it.
    - Suggested fix: Add a view-model test for an edit or photo after a failed load. Add a list test with a deferred first `list()` answer, a `selectTag` call, and an assertion that the stale result is not applied.

## Review conclusion

Material issues found; fix before completing the task.
