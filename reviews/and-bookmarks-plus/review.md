# Code Review

Date: 2026-11-08

Task: and-bookmarks-plus - Android bookmarks: add, read-later toggle, reader, to inbox

Scope: final code of the task, `git diff 3e8f6408c2b032d9501fb91c336923da20fdb185..9defb91aa` (base = `git merge-base HEAD origin/main`). Commits 83196000 (implementation), 8c296159, cc46ef25, 90e31e3c, d5db8896, 9defb91a (tests and reports), 4b46f381, 8a553ef7, cc64e32d (fixes of the earlier review rounds). This round focuses on cc64e32d (fixes for P3 CR-1 and CR-2 of the third review) and the tests added after it, and re-checks the whole feature against the acceptance criteria. Committed HEAD only. The working tree was clean.

Requirements:
- tasks/and-bookmarks-plus.md acceptance criteria #1-#6
- SPEC.md §4.2 (ETag/If-Match, UUIDv7 idempotent creates), §4.5 (bookmark to Inbox task linked to its origin), §8.4 (bookmarks)
- docs/ARCHITECTURE.md and AGENTS.md project notes (lint is part of the contract, strings in EN and DE)
- Previous review rounds, `reviews/and-bookmarks-plus/fixes.md`, `reviews/and-bookmarks-plus/tests.md`

Git range:
- Branch: main
- HEAD: 9defb91aa
- Base/start: 3e8f6408c2b032d9501fb91c336923da20fdb185
- Commits reviewed: 9

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Previous findings (third review)

- CR-1 (local `taskId` shadowed the parameter in `BookmarksRepository.toInbox`): resolved. The local is now `createdId` (`BookmarksRepository.kt:231-232`), and the link uses it. Behavior is unchanged. The new repository test `to Inbox links the task the server answered with` pins it.
- CR-2 (a reader reopened during a running save showed no saving state): resolved. `BookmarksViewModel` keeps `snapshotSaves` (`BookmarksViewModel.kt:153`). `openReader` starts with `saving = bookmark.id in snapshotSaves` (`:302`). The save adds the id before it starts (`:330`). Its `finally` removes it and clears `saving` on a reader still open on that bookmark (`:334-338`), whatever the outcome. I traced these paths:
  - Save succeeds while the reopened reader's own load is still pending: `saveSnapshot` cancels that `readerJob` and loads the new copy (`:351-356`), so the late, older `get` cannot overwrite the saved version.
  - Save ends `Ok(null)`: the reader shows `UNREADABLE` with `saving = false` (`:349`).
  - Save fails with a conflict or offline: `failed()` reloads or keeps the version, and the reader gets `saving = false` (`:364`). If the bookmark is gone, only a reader open on it is closed (`:362`).
  - `retryReader` and `saveSnapshot` refuse while `saving` is set (`:309`, `:326`), and `busy` still blocks other changes, so no write goes out with an old `If-Match`.
  - `snapshotSaves` is only touched on the main dispatcher (`viewModelScope`), so the plain mutable set is safe.
  The commit added two view-model tests, and the test-writer added two more (late older load, offline load with retry blocked until the save ends). The earlier test that now expects `saving = true` on reopen was updated to the new, intended behavior, not weakened.

## Acceptance criteria

- #1 Add bookmark: dialog with URL, optional title and tags, read later (preset in the read-later view). Local URL and tag validation, server 400 field errors mapped to the right fields, offline result keeps the input and reloads a loaded list. Met in code and tests.
- #2 Read-later toggle: `PATCH` with only `readLater` and `If-Match`; conflicts reload; the row leaves the queue view when taken out. Met.
- #3 Snapshot reader: loads the latest bookmark, then `GET /files/{id}/preview` with `X-Preview-Truncated`; text only; links resolved against the page and limited to http(s) (`BookmarksModels.kt:158-163`). Save a new copy with `If-Match`, 502 as unreadable. Met.
- #4 To Inbox task: idempotent UUIDv7 task create (id kept only while the answer is unknown) plus an `origin` link TASK to BOOKMARK; snackbars for linked, unlinked, offline and failure. Met.
- #5 Strings: `strings_bookmarks.xml` has 56 entries in both `values/` and `values-de/`; `BookmarksStringsTest` checks key and placeholder parity, `lintDebug` checks `MissingTranslation`. Met.
- #6 Tests and checks: tests cover each criterion (view model, repository, Compose screen, route, models, strings). `tests.md` records `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` (4387 tests, 0 failures) and `sh scripts/check.sh` ("all checks passed") on cc64e32d plus the new tests. I did not run them in this read-only review. The parent worker must record the commands it ran on the final HEAD with `tasks.py verify`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.

The fixes for CR-1 and CR-2 are correct and add no regressions. The feature meets all six acceptance criteria in code, and the tests cover the new behavior, including the reopen-during-save paths. No secrets, sensitive logging, unsafe rendering or unsafe link schemes were found. All changed files are inside the task's `touches` (plus its own `reviews/` files).
