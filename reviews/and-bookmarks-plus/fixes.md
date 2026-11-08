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

## Final status

All validated findings fixed and validation passed. The exception is the full `.taskcheck`, which stops at the unrelated `web` part because of the Node.js version in this environment.
