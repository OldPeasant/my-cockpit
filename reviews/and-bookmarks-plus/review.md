# Code Review

Date: 2026-11-08

Task: and-bookmarks-plus - Android bookmarks: add, read-later toggle, reader, to inbox

Scope: final code of the task, base 6fbabadf to HEAD 79a096b9: commits 2f0bc106 (implementation), 4bb0f38f and b6ef785a (tests, reports), 79a096b9 (fixes for CR-1 to CR-5 of the previous review). Android bookmarks feature (`android/app/src/main/java/ch/sonensei/cockpit/bookmarks`), its tests, and `strings_bookmarks.xml` (en/de).

Requirements:
- tasks/and-bookmarks-plus.md acceptance criteria
- SPEC.md §4.2 (ETag/If-Match, UUIDv7 idempotent creates), §4.5 (bookmark to Inbox task, linked to its origin)
- Server contract: `bookmarks/domain/BookmarkService.kt` (URL and tag validation, field names in errors), `bookmarks/api/BookmarkController.kt`, `core/web/ApiExceptions.kt` (`errors[].field` in 400 Problem Details)
- AGENTS.md project notes
- Previous review and `reviews/and-bookmarks-plus/fixes.md`

Git range:
- Branch: main
- HEAD: 79a096b9
- Base/start: 6fbabadfbafe865bbdba739d8dc5de77cb446065
- Commits reviewed: 4

Review limitation: shell access (git, tasks.py) was denied in this session, so the exact diff and the recorded `tasks.py verify` evidence could not be inspected. The review reads the current files in full: `BookmarksViewModel.kt`, `BookmarksModels.kt`, `BookmarksRepository.kt`, `BookmarkAdder.kt`, `BookmarkReader.kt`, `BookmarksScreen.kt`, both `strings_bookmarks.xml`, `BookmarksRepositoryTest.kt` and `BookmarksAddReaderViewModelTest.kt` (the start of `BookmarksAddReaderScreenTest.kt` too). Before `done`, the parent worker should confirm that the criterion 6 evidence names the commands that were run (`./gradlew ktlintCheck testDebugUnitTest lintDebug`, `sh scripts/check.sh`). `fixes.md` says the full `scripts/check.sh` stopped at the unrelated `web` part because Node.js is v20.9.0 here. That part must be recorded as skipped with this reason, not as passed.

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Previous findings

- CR-1 (add dialog refused addresses the server accepts): resolved. `isWebAddress` (`BookmarksModels.kt:135-142`) now only checks the http(s) scheme, a host part without whitespace, and no control characters. The server decides the rest. Tests cover an IDN host, `_` in the host, `|` in the query and a space in the path (`BookmarksAddReaderViewModelTest.kt:297-316`).
- CR-2 (every 400 shown as an invalid address): resolved. The repository reads `errors[].field` from the Problem Details (`BookmarksRepository.kt:346-352`), which matches the server's `FieldError(field, message)`. The view model maps `url` to `INVALID_URL`, `tags` to `INVALID_TAGS` and anything else to `FAILED` (`BookmarksViewModel.kt:258-266`). Tags are also checked on the device with the server's limits (`tagsWithinLimits`: 50 tags, 50 characters, no NUL, the same as `BookmarkService.normalizeTags`). Both strings exist in EN and DE.
- CR-3 (no retry after "page could not be read"): resolved. The `UNREADABLE` branch renders `SaveCopyButton` with "Retry" (`BookmarkReader.kt:145-148`).
- CR-4 (save copy overlapping other writes): resolved. `saveSnapshot()` uses the per-bookmark `busy` set and releases it in `finally` (`BookmarksViewModel.kt:311-325`). The reader's actions and the save button are disabled while busy. Both directions are tested (`BookmarksAddReaderViewModelTest.kt:794-833`).
- CR-5 (duplicate Inbox tasks after a lost answer): resolved. A UUIDv7 `id` is sent in `CreateTaskRequest` and kept per bookmark while the result is `Offline` (`BookmarksViewModel.kt:277-286`, `BookmarksRepository.kt:213-233`). It is tested. The misleading "Nothing was changed" text that CR-5 also mentioned remains, see CR-2 below.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Leaving the reader while a copy is saved keeps an outdated version in the list**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksViewModel.kt:302-305` (`closeReader` cancels `readerJob`), `:317-324` (the save runs as `readerJob`)
    - Category: Correctness
    - Problem: `closeReader()` cancels the running `saveSnapshot` job, which cancels the HTTP call on the client. The server may already have fetched the page and committed the snapshot, which raises the bookmark's version. The list still holds the old version, and the `busy` guard is released right away. The next change from the list (mark read, read later, edit, delete) sends the old `If-Match`, gets 412, and shows "The bookmark was changed elsewhere and has been reloaded", although nothing else changed it. The test `a copy saved after the reader was left does not reopen it ...` (`BookmarksAddReaderViewModelTest.kt:864-886`) checks only that the reader stays closed. It does not check the list version.
    - Why it matters: a confusing conflict message and a lost tap after a normal "back" during a save. It fixes itself after the reload, so the priority is low.
    - Suggested fix: do not cancel a running save when the reader closes. Let the save finish in its own job and `put()` the result into the list. Only the reader updates should be skipped, which `updateReader` already does by id. Or, when a save is cancelled, reload the bookmark (`get` + `put`).

- [ ] **CR-2 | "Nothing was changed" is shown for creates whose outcome is unknown**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarkAdder.kt:104`, `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksViewModel.kt:262,284`, `android/app/src/main/res/values/strings_bookmarks.xml:31` (and `values-de`)
    - Category: Correctness
    - Problem: `BookmarksResult.Offline` covers an `IOException` after the request was sent and every 5xx (`BookmarksRepository.kt:300-303`). For the add dialog and "To Inbox task" it is shown as `bookmarks_change_offline`, "Can't reach the server. Nothing was changed.". For a create whose answer was lost, the bookmark or task may exist already. To Inbox is now safe to retry because of the kept task id. The bookmark create has no client id (`CreateBookmarkRequest`), so a retry after this message can make a duplicate bookmark, and the message tells the user it is safe.
    - Why it matters: the text is wrong in exactly the case where it matters, and for add it encourages a duplicate.
    - Suggested fix: use a separate string for creates, for example "Can't reach the server. The bookmark may not have been saved; check the list before trying again." (EN + DE), or drop "Nothing was changed" from the shared string. Optionally refresh the list after an offline add.

## Review conclusion

No material issues found.

The four acceptance criteria about behavior are implemented and match the server contract: add bookmark with URL, optional title and tags, and read later; the read-later toggle (`PATCH {"readLater":...}` with `If-Match`, conflicts reloaded); the snapshot reader (latest bookmark, then `GET /files/{id}/preview` with `X-Preview-Truncated`, HTML rendered as text, links resolved against the page and limited to http(s)); and "To Inbox task" (idempotent task create plus `origin` link TASK to BOOKMARK). All new strings exist in English and German. The view-model, repository and Compose tests cover the new behavior, including the fixes. No secrets, sensitive logging or unsafe rendering were found. CR-1 and CR-2 are small robustness improvements and do not block completion. The criterion 6 evidence and the skipped `web` part of `scripts/check.sh` must be recorded correctly (see the review limitation).
