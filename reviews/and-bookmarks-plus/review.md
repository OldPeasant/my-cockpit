# Code Review

Date: 2026-11-08

Task: and-bookmarks-plus - Android bookmarks: add, read-later toggle, reader, to inbox

Scope: final code of the task, `git diff 7adc3abf..42d64867`, commits 51bbd34f (implementation), 6ab3aee9 and b738bf99 (tests, reports), 1712ee50 (fixes for CR-1 to CR-5 of the first review), c11ff7b9 (tests, second review), 42d64867 (fixes for CR-1 and CR-2 of the second review). Android bookmarks feature (`android/app/src/main/java/ch/sonensei/cockpit/bookmarks`), its tests, and `strings_bookmarks.xml` (en/de). Committed HEAD only. The working tree was clean when the review started.

Requirements:
- tasks/and-bookmarks-plus.md acceptance criteria
- SPEC.md §4.2 (ETag/If-Match, UUIDv7 idempotent creates), §4.5 (bookmark to Inbox task, linked to its origin)
- docs/ARCHITECTURE.md and AGENTS.md project notes (lint is part of the contract, strings in EN and DE)
- Previous review rounds and `reviews/and-bookmarks-plus/fixes.md`

Git range:
- Branch: main
- HEAD: 42d6486716e7e0745a7a3f364a885de6d43ac777
- Base/start: 7adc3abf42d567c81db59173e481e988a4d835ce
- Commits reviewed: 6

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Previous findings (second review)

- CR-1 (leaving the reader during a save left an outdated version in the list): resolved. `saveSnapshot()` now runs in its own coroutine, not as `readerJob`, so `closeReader()` no longer cancels it (`BookmarksViewModel.kt:321-334`). On success the saved bookmark is `put()` into the list (`:344`). The new copy is loaded only if the reader is still open on the same bookmark, and that load becomes the cancellable `readerJob` (`:346-349`). On failure with the bookmark gone, only a reader still open on that bookmark is closed (`:355`), so a reader opened on another bookmark stays open. The `busy` guard stays until the save ends, so no list change goes out with the old `If-Match`. Tests cover this: `leaving the reader while a copy is saved lets the save finish and puts the new version into the list` and `a save that fails after the reader was left does not close a reader opened on another bookmark` (`BookmarksAddReaderViewModelTest.kt:857-927`). I traced reopening the reader on the same bookmark during the save: the save's `readerJob?.cancel()` stops the reopened reader's load, and the reader then loads the newer copy. That path is correct. See CR-2 below for a small display gap.
- CR-2 (the "Nothing was changed" text for creates whose result is unknown): resolved. The add dialog uses `bookmarks_add_offline` (`BookmarkAdder.kt:104`). "To Inbox" shows the new `BookmarksSnackbar.InboxOffline` with `bookmarks_to_inbox_offline` (`BookmarksViewModel.kt:292`, `BookmarksScreen.kt:687-688`). Both strings exist in EN and DE, and `BookmarksStringsTest` lists them. An offline add also reloads a loaded list (`BookmarksViewModel.kt:274`), so a bookmark the server did save shows up. View-model, Compose and string tests were updated.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Local `taskId` shadows the `taskId` parameter in `toInbox`**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksRepository.kt:215,231`
    - Category: Quality
    - Problem: `toInbox(bookmark, taskId)` sends the client id `taskId`. Inside the `load { }` lambda, `val taskId = UUID.fromString(task["id"]...)` declares a second `taskId` (the id the server returned) that shadows the parameter, and the link uses that one. The behavior is correct, because the server returns the same id for an idempotent create. But two different values have the same name in one function, and Kotlin reports name shadowing in this pattern as a compiler warning. The project treats warnings as defects ("fix warnings, don't suppress them").
    - Why it matters: a later edit can easily use the wrong id, and the build gets a warning that can be avoided.
    - Suggested fix: rename the local, for example `val createdId = ...`, and pass it to `link(createdId, bookmark.id)`.

- [ ] **CR-2 | A reader reopened during a running save shows no saving state**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksViewModel.kt:298-300` (`openReader` starts with `saving = false`), `BookmarkReader.kt:82,179`
    - Category: Correctness
    - Problem: since the CR-1 fix, a save continues after the reader closes. If the user opens the reader on the same bookmark again before the save ends, `openReader` creates a new `ReaderState(bookmark, loading = true)` with `saving = false`. When the reader's own load finishes, the progress bar goes away (`reading.loading || reading.saving` is false), but the save button and the actions stay disabled (`busy`). Nothing says why. The new copy appears later, when the save ends.
    - Why it matters: a small UX gap. The state stays correct and fixes itself, so the priority is low.
    - Suggested fix: keep the ids of running snapshot saves (or check `busy` together with a save marker) and start `openReader` with `saving = true` for them. Alternatively, show the progress bar in the reader while its bookmark is `busy`.

## Review conclusion

No material issues found.

All four behavior criteria are implemented and match the server contract. Add bookmark: URL, optional title and tags, read later, with server field errors mapped to the right messages. Read-later toggle: `PATCH` with `If-Match`, and conflicts are reloaded. Snapshot reader: loads the latest bookmark, then `GET /files/{id}/preview` with `X-Preview-Truncated`. It renders text only, and links are resolved and limited to http(s). To Inbox task: an idempotent UUIDv7 task create plus an `origin` link from TASK to BOOKMARK. All new strings exist in English and German. The fixes from the second review are correct and add no regressions. No secrets, sensitive logging or unsafe rendering were found.

The two P3 findings are optional cleanups. I did not run the Gradle suites in this review (read-only). The acceptance criteria are still unchecked in the task file. The parent worker must record criterion 6 with the commands actually run (`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`, `sh scripts/check.sh`). The task notes say the `web` part of `scripts/check.sh` fails here because Node.js is v20.9.0. If so, record that part as skipped with this reason, not as passed.
