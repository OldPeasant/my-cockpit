# Fix Report

Date: 2026-11-08

Task: and-bookmarks-plus - Android bookmarks: add, read-later toggle, reader, to inbox

Scope: current task; findings CR-1 to CR-5 of `reviews/and-bookmarks-plus/review.md` (base 6fbabadf, task commits up to b6ef785a)

## Fixed findings

### CR-1 — Add dialog rejects web addresses the server accepts

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksModels.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/bookmarks/BookmarksModelsTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/bookmarks/BookmarksAddReaderViewModelTest.kt`

Problem:
`isWebAddress` parsed with `java.net.URI` and required a non-null host. That refused IDN hosts, `_` in hosts, and spaces, `|`, `{`, `[` in the path or query, all of which the server's `BookmarkUrls.normalize` accepts and percent-encodes.

Fix:
`isWebAddress` is now a lenient check. It trims the text, refuses control characters, and requires the scheme `http://` or `https://` (case-insensitive) with a non-empty host part that has no whitespace (user info and port are skipped, IPv6 literals are allowed). The server stays the authority. `resolveLink` reuses the check and still opens only http(s) links with a host.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.bookmarks.*'` — Pass. New tests cover the IDN host, the `_` host, `|` in the query, a space in the path, `{}`/`[]`, user info plus port, IPv6, and that the view model sends these addresses to the server.

### CR-2 — Every 400 on create is shown as an invalid web address

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksRepository.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksModels.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarkAdder.kt`
- `android/app/src/main/res/values/strings_bookmarks.xml`, `android/app/src/main/res/values-de/strings_bookmarks.xml`
- tests: `BookmarksRepositoryTest.kt`, `BookmarksAddReaderViewModelTest.kt`, `BookmarksAddReaderScreenTest.kt`, `BookmarksModelsTest.kt`, `BookmarksStringsTest.kt`

Problem:
`add()` turned every 400 into `AddFailure.INVALID_URL`, including the server's tag errors (a tag over 50 characters, more than 50 tags, NUL).

Fix:
- `BookmarksResult.Failed` has a new `fields` set. On a 400 the repository reads the field names from the Problem Details `errors` list. An unreadable body gives an empty set, and only field names are kept, never messages or the body.
- The view model maps a 400 that names `url` to `INVALID_URL` and one that names `tags` to the new `AddFailure.INVALID_TAGS`. Any other 400 becomes the generic `FAILED`, which is not attached to the URL field.
- Tags are also checked on the device with the server's limits (`tagsWithinLimits`: at most 50 distinct tags, each at most 50 characters, no NUL). The tags field is then marked as the error, with a new string in English and German.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.bookmarks.*'` — Pass. Covers Problem Details parsing (fields, no errors list, non-JSON body), the 400 mapping to url/tags/generic, the local tag check (too long, too many), the error text in English and German, and both string files.

### CR-3 — Reader offers no retry after "page could not be read"

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarkReader.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/bookmarks/BookmarksAddReaderScreenTest.kt`

Problem:
In the `UNREADABLE` state the reader showed the error and the earlier copy, but no button to save again.

Fix:
The `UNREADABLE` branch now shows the save-copy button labelled with the existing "Retry" string (`bookmarks_retry`), so no new string was needed. The button calls `saveSnapshot`. The existing test that asserted "offers no retry" now asserts this button: it exists, is enabled, calls save, and is disabled while the bookmark is busy. The reload retry (`RETRY`) is still not shown in this state.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.bookmarks.*'` — Pass.

### CR-4 — Saving a copy can overlap other reader writes

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarkReader.kt`
- tests: `BookmarksAddReaderViewModelTest.kt`, `BookmarksAddReaderScreenTest.kt`

Problem:
`saveSnapshot()` did not use the per-bookmark `busy` guard, so a save and a read, read-later or Inbox change could go out at the same time with the same `If-Match`.

Fix:
`saveSnapshot()` returns early when the bookmark is busy. Otherwise it adds the bookmark to `busy` and removes it in a `finally`, which also runs when the reader job is cancelled. The reader's other actions are disabled by `busy`, so they are now disabled while a copy is saved. The save-copy button is also disabled while `busy`.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.bookmarks.*'` — Pass. A new test checks both directions: a toggle during a save is ignored, and a save during a toggle is ignored.

### CR-5 — "To Inbox task" can create duplicate tasks

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksRepository.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksViewModel.kt`
- tests: `BookmarksRepositoryTest.kt`, `BookmarksAddReaderViewModelTest.kt`

Problem:
The task create had no client id, so a retry after a lost answer made a second Inbox task.

Fix:
`BookmarksRepository.toInbox(bookmark, taskId)` now sends `"id"` in `CreateTaskRequest`. Per `api/openapi.yaml` `createTask`, a repeated create with the same id returns the existing task with 200. The view model makes the id with the existing `UuidV7.generate(...)` (from `capture.queue`, also used by `EventEditorViewModel`) and keeps it per bookmark while the result is `Offline`, meaning the answer is unknown. A retry therefore sends the same id. After any server answer the id is dropped, so a later deliberate "To Inbox task" makes a new task.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.bookmarks.*'` — Pass. The repository test asserts the request body includes the id. A new view-model test checks that a retry after offline repeats the id, that a later action and another bookmark get new ids, and that all ids are version 7.

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.bookmarks.*'` — Pass

Full test suite:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` — BUILD SUCCESSFUL
- `sh scripts/check.sh android` — "all checks passed (android)"

Lint/type-check/build:
- ktlintCheck and lintDebug — Pass (included above)

`.taskcheck`:
- Fail in this environment, for a reason outside this change. `sh scripts/check.sh` passed the `tfp` and `server` parts and then stopped at the `web` part: the installed Node.js is v20.9.0, and the Angular CLI requires v22.22.3 or newer. No `web/` files were changed. The `android` part passes on its own (see above).

Remaining unresolved findings:
0

## Second round (second review, base e08b952e, task commits up to 00f1c5d4)

Scope: P3 findings CR-1 and CR-2 of the second `reviews/and-bookmarks-plus/review.md`.

### Fixed findings

#### CR-1 (second review) — Leaving the reader while a copy is saved keeps an outdated version in the list

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/bookmarks/BookmarksAddReaderViewModelTest.kt`

Problem:
`closeReader()` cancelled `readerJob`, which was also the running `saveSnapshot` job. If the server had already saved the copy, the bookmark's version went up but the list kept the old one, and `busy` was released at once. The next change from the list then got a 412 and a "changed elsewhere" message.

Fix:
The save now runs in its own job that `closeReader()` and `loadReader()` do not cancel. The bookmark stays in `busy` until the server answers (released in `finally`, as before), so no other change goes out with the old version. The answer is always `put()` into the list. Reader updates still go only to a reader open on the same bookmark (`updateReader` by id). The new copy is loaded only when that reader is still open, as a new `readerJob`, so leaving the reader stops that load. A save that finds the bookmark gone closes the reader only when it still shows that bookmark.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.bookmarks.*'` — Pass. The test `leaving the reader while a copy is saved lets the save finish and puts the new version into the list` replaces the old `... frees the bookmark for other changes` test, whose expectation (the bookmark freed at once on close) was the cause of the bug. It checks that the bookmark stays busy and a toggle is ignored while the save runs, that the saved version (2) is in the list afterwards, that no copy is loaded for the closed reader, and that the next toggle sends the saved version with no conflict note. The new test `a save that fails after the reader was left does not close a reader opened on another bookmark` covers the gone case.

#### CR-2 (second review) — "Nothing was changed" is shown for creates whose outcome is unknown

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarkAdder.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksScreen.kt`
- `android/app/src/main/res/values/strings_bookmarks.xml`, `android/app/src/main/res/values-de/strings_bookmarks.xml`
- tests: `BookmarksAddReaderViewModelTest.kt`, `BookmarksAddReaderScreenTest.kt`, `BookmarksStringsTest.kt`

Problem:
`Offline` also covers lost answers and 5xx after the request was sent. The add dialog and "To Inbox task" showed `bookmarks_change_offline` ("Nothing was changed"), which is wrong in that case. For add it encouraged a retry that could make a duplicate bookmark.

Fix:
- Add dialog: new string `bookmarks_add_offline`, "Can't reach the server. The bookmark may have been saved; check the list before trying again." (DE: "Der Server ist nicht erreichbar. Das Lesezeichen wurde vielleicht schon gespeichert; prüfe die Liste, bevor du es noch einmal versuchst."). After an offline add, the view model also reloads a list that was loaded before, so a bookmark the server did save shows up.
- To Inbox: new snackbar `BookmarksSnackbar.InboxOffline` with the new string `bookmarks_to_inbox_offline`, "Can't reach the server. The task may have been added already; trying again won't add it twice." (DE: "Der Server ist nicht erreichbar. Die Aufgabe wurde vielleicht schon angelegt; ein erneuter Versuch legt sie nicht doppelt an."). This is accurate because a retry sends the kept task id.
- `bookmarks_change_offline` is unchanged for the other list changes (read, read later, edit, delete, save copy). These send `If-Match`, so a repeat after a lost answer cannot apply twice.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.bookmarks.*'` — Pass. The screen test asserts the exact new add text and the new To Inbox snackbar text. The view-model test `a failed to Inbox says why` now expects `InboxOffline` for an offline result. The new test `an add without an answer loads the list again, so a bookmark the server saved shows up` checks the reload. `BookmarksStringsTest` lists both new keys, and its existing check confirms that EN and DE have the same keys.

### Not fixed

None.

### Validation summary (second round)

Focused tests, lint and build:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.bookmarks.*' lintDebug` — BUILD SUCCESSFUL. 151 bookmarks tests in 8 classes, 0 failures, 0 skipped.

Full test suite:
- Not run in this round. Only bookmarks files changed.

`.taskcheck`:
- Not run in this round. The parent worker runs it before `done`. The `web` part could not run in this environment in round one (Node.js version, see above).

Remaining unresolved findings:
0

## Third round (third review, base bb2aec8a)

Scope: P3 findings CR-1 and CR-2 of the third `reviews/and-bookmarks-plus/review.md` (reviewed HEAD 42d64867).

### Fixed findings

#### CR-1 (third review) — Local `taskId` shadows the `taskId` parameter in `toInbox`

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksRepository.kt`

Problem:
Inside `toInbox(bookmark, taskId)` a local `val taskId` (the id the server returned) shadowed the parameter (the client id).

Fix:
The local is now `createdId` and is passed to `link(createdId, bookmark.id)`. No behavior change.

Validation:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` — Pass (the existing `BookmarksRepositoryTest` To Inbox tests still pass).

#### CR-2 (third review) — A reader reopened during a running save shows no saving state

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/bookmarks/BookmarksAddReaderViewModelTest.kt`

Problem:
`openReader` always started with `saving = false`. Reopening the reader on a bookmark whose copy was still being saved showed no progress while the actions stayed disabled by `busy`.

Fix:
The view model keeps the ids of running snapshot saves (`snapshotSaves`). `openReader` starts with `saving = true` for those. When the save ends (success, unreadable page, failure or cancellation, in the `finally`), the id is removed and a reader still open on that bookmark gets `saving = false`. The existing paths already cleared `saving` on the reader, so this only adds the clear for the reopened reader and covers cancellation.

Test change:
The existing test `a reader opened again on the bookmark while its copy is saved shows the new copy once it is saved` asserted `ReaderState(bookmark(1))` (with `saving = false`) for the reopened reader, which is the behavior CR-2 reports as the gap. Only that assertion changed, to `saving = true`. The rest of the test is unchanged. New tests:
- `a reader reopened while its copy is saved shows the save until it ends`: a reader on another bookmark is not saving, the reopened reader is saving after its own load, the saved copy is shown with `saving = false` and `busy` empty after the save, and a reader opened after the save is not saving.
- `a reader reopened while its copy is saved stops showing the save when it fails`: the server cannot read the page (Ok with no bookmark), so the reopened reader ends with `saving = false` and `UNREADABLE`.

Validation:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` — BUILD SUCCESSFUL. 158 bookmarks tests, 0 failures.

### Not fixed

None.

### Validation summary (third round)

Focused tests, lint and build:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.bookmarks.*'` — Pass after the test update (first run failed on one ktlint chain rule in the new test and on the old assertion described above; both fixed).

Full test suite:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` — BUILD SUCCESSFUL.

`.taskcheck`:
- Not run in this round. Only Android bookmarks files changed. The parent worker runs it before `done`.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed (all three rounds: CR-1 to CR-5 of the first review, CR-1 and CR-2 of the second review, CR-1 and CR-2 of the third review). The focused Android validation passes. The full `.taskcheck` was not rerun after the second or third round. In round one it stopped at the unrelated `web` part because of the Node.js version in this environment.
