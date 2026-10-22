# Code Review

Date: 2026-10-22

Task: and-files - Android files

Scope: `git diff 99220d2c8..HEAD -- android` (commits 0978e7bd1, 8621c16f9); committed changes only

Requirements:
- tasks/and-files.md acceptance criteria and recorded assumptions (multipart streaming upload, "Save to device" via ACTION_CREATE_DOCUMENT, "Open" via cache + dedicated FileProvider, browse/open/save/upload only)
- SPEC.md §8.2, §9.2 item 7
- api/openapi.yaml (`GET /api/v1/drive` limit 1-200, `POST /api/v1/files` 413, `GET /api/v1/files/{id}/content`)
- AGENTS.md "Project notes", docs/ARCHITECTURE.md conventions; sibling feature `contacts` as the pattern

Git range:
- Branch: main
- HEAD: 8621c16f9929cfac68970b8ad15765c2c19ffb46
- Base/start: 99220d2c8a1dcc6c87d3935a955abc276f6b7942
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 3 P2, 2 P3

Reviewer note: Gradle could not be run from this sub-agent (Bash restricted to read-only inspection). The review is based on reading the code; the parent reported ktlintCheck and lintDebug clean and `testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*'` 29/29 passing (the count matches the 29 test methods in the four committed test classes).

Checked and found sound: paging loop (limit 200 is inside the contract's 1-200, cursor followed, de-duplicated by id); error mapping (IOException -> Offline, 5xx -> Offline, 4xx -> Failed(status), 413 -> TOO_LARGE, unreadable source/unwritable target -> Failed(null), CancellationException rethrown); streaming download via a separate `@Streaming` interface on the shared Retrofit (auth interceptor and authenticator apply) on Dispatchers.IO; streaming upload with a re-openable `RequestBody` (safe for authenticator retries) and unknown length; provider `exported="false"`, `grantUriPermissions="true"`, only `cache/files-open/` shared, per-URI read grant on ACTION_VIEW; `safeName` blocks separators, control characters, empty and dot-only names (no path traversal); no logging of names or contents; folder-load races handled by cancelling the previous `loadJob` and by clearing entries on navigation; upload reload only when the same folder is still shown.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Tapping a second file while one downloads deletes the file being downloaded, then the viewer gets a missing file**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/files/FilesScreen.kt:135`, `android/app/src/main/java/ch/sonensei/cockpit/files/DeviceFiles.kt:84`, `android/app/src/main/java/ch/sonensei/cockpit/files/FilesViewModel.kt:145`
    - Category: Correctness
    - Problem: `openFile` calls `OpenedFiles.target(context, it.id, it.name)` before the view model's single-flight check. `target()` deletes every `files-open/<id>` directory except the new id. If file A is downloading and the user taps file B (B's row stays clickable; only A's trailing menu is replaced by a spinner), A's directory is deleted, the view model then ignores B (`downloadJob?.isActive`), A's download keeps writing to the unlinked file, and on success `FilesEvent.Open(targetA)` hands out a content URI to a file that no longer exists, so the other app fails to open it while Cockpit reports nothing.
    - Why it matters: A normal double-tap sequence on a slow connection breaks "open with intents", and the user sees neither an error nor the file.
    - Suggested fix: Choose/clean the cache target only after the single-flight check, e.g. pass a target factory into `FilesViewModel.open` that is invoked inside the launched download (or ignore `openFile`/`saveFile` in the route while `state.downloading != null`). Add a test that a second open while downloading does not delete the first target.

- [ ] **CR-2 | The file to save is lost when the activity is recreated while the save-as picker is open**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/files/FilesScreen.kt:122`
    - Category: Correctness
    - Problem: The pending save is kept in `var saving by remember { mutableStateOf<DriveEntry.File?>(null) }`. `MainActivity` declares no `configChanges`, so rotating the device (or the system reclaiming the activity) while the ACTION_CREATE_DOCUMENT picker is shown recreates the activity; `rememberLauncherForActivityResult` still delivers the URI, but `saving` is `null`, so the callback silently drops it.
    - Why it matters: The picker has already created an empty document at the place the user chose; nothing is written into it and no message is shown, so "Save to device" appears to succeed but leaves a 0-byte file.
    - Suggested fix: Keep the pending file in state that survives recreation, e.g. `rememberSaveable` of the file id (look it up in `state.entries`) or a pending-save field in the view model backed by `SavedStateHandle`.

- [ ] **CR-3 | A failed or interrupted "Save to device" leaves an empty or truncated document behind**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/files/FilesViewModel.kt:149`, `android/app/src/main/java/ch/sonensei/cockpit/files/FilesRepository.kt:103`
    - Category: Correctness
    - Problem: The CreateDocument picker creates the document before the download starts. When the download fails (offline mid-stream, 404, write error) or is cancelled (view model cleared), only a banner is shown; the document with the drive file's name stays in the user's chosen folder, empty or truncated. The same applies to the open-cache file (less severe, it is replaced by the next open).
    - Why it matters: A truncated `report.pdf` in Downloads looks like a saved copy; once the banner is dismissed nothing indicates it is corrupt.
    - Suggested fix: On a non-`Ok` result (and on cancellation), delete the created document (`DocumentsContract.deleteDocument(resolver, uri)`, ignoring failures) and the partial cache file; cover it with a test of the failure path.

## P3 Low

- [ ] **CR-4 | Content-provider queries and cache deletion run on the main thread**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/files/FilesScreen.kt:120`, `android/app/src/main/java/ch/sonensei/cockpit/files/FilesScreen.kt:135`
    - Category: Quality
    - Problem: `describePicked` (a `ContentResolver.query` and `getType` per picked URI) runs in the activity-result callback, and `OpenedFiles.target` (`listFiles`, `deleteRecursively`, `mkdirs`) runs in the click handler, both on the main thread.
    - Why it matters: Cloud document providers can answer `query` slowly; picking several files from one can stall the UI (StrictMode disk/IPC violations, ANR risk).
    - Suggested fix: Pass the URIs to the view model and resolve names/sizes and the cache target inside its coroutine on `Dispatchers.IO`.

- [ ] **CR-5 | Cache file names are cut by characters, not bytes**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/files/DeviceFiles.kt:118`
    - Category: Correctness
    - Problem: `safeName` truncates to 120 UTF-16 chars. File systems limit a name to 255 bytes; 86+ CJK characters (3 bytes each) or many emoji exceed it, so `FileOutputStream` fails and the user sees "The file could not be downloaded." for a valid drive file. `take(120)` can also split a surrogate pair, and truncation drops the extension the viewer may rely on.
    - Why it matters: Valid drive files with long non-Latin names cannot be opened.
    - Suggested fix: Truncate by UTF-8 byte length (e.g. at most 200 bytes) on a code-point boundary, keeping the extension; add a test with a long multibyte name.

## Review conclusion

Material issues found; fix before completing the task. The acceptance criteria are implemented in substance (browse, open via FileProvider, save, streaming upload; browse and upload states covered by view-model and Compose tests), but CR-1 to CR-3 are correctness defects in the open/save flows that should be fixed, ideally with tests for the `FilesRoute` wiring, which currently has none.
