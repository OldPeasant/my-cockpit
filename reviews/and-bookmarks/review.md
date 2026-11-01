# Code Review

Date: 2026-11-01

Task: and-bookmarks - Android: Bookmarks screen (list, read later, open, edit)

Scope: git diff 126a16a6b6e4fabc79733a53fd22976a920e83c8..443d2373a (commits 55a1f3dbd implementation, e22139971 and ec6be222a tests, 443d2373a fixes for CR-1..CR-4 of the previous review); committed changes only. Re-review of the final code in full, plus a check of every earlier finding.

Requirements:
- tasks/and-bookmarks.md acceptance criteria and the five recorded assumptions
- SPEC.md §8.4, docs/ARCHITECTURE.md, AGENTS.md project conventions
- api/openapi.yaml (BookmarkPatchRequest, BookmarkResponse, /api/v1/trash/{type}/{id}/restore)
- Previous review (CR-1..CR-4) and reviews/and-bookmarks/fixes.md

Git range:
- Branch: main
- HEAD: 443d2373af3fd1b4242d35b65933ff7774508e5a
- Base/start: 126a16a6b6e4fabc79733a53fd22976a920e83c8
- Commits reviewed: 4

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Earlier findings

- CR-1 (equal failure snackbars get stuck): resolved. `BookmarksSnackbar` now has an `id`. The view model numbers every `Trashed` and `Failed` it shows (`BookmarksViewModel.kt:307`), so two failures with the same content are different values. The `LaunchedEffect(snackbar)` restarts, which cancels the first `showSnackbar` without calling `onDismiss`, and `dismiss` matches with `==`, which includes the id (`BookmarksViewModel.kt:203`). Covered by the new view model test and the new screen test. `NoBrowser` keeps id 0. A repeat while the first one is still visible shows the same message, which is acceptable.
- CR-2 (failed save throws away the draft): resolved. `failed()` returns the latest known version: the reloaded one after a 409/412, the sent one otherwise, and `null` after a 404. `save` reopens the editor with the draft through `reopen` (`BookmarksViewModel.kt:170-174`, `:222-230`), unless the bookmark is gone or another editor is open. `BookmarkEditor` starts from the draft (`BookmarksScreen.kt:514-516`). `edit` and `cancelEdit` clear the draft. The reopen runs inside `run` before its `finally` clears `busy`, but nothing suspends in between on the main dispatcher, so the user cannot hit a busy-blocked save. Covered by the view model test for offline, 412 and 404 and by the screen test.
- CR-3 (older load brings back a changed bookmark): resolved. `put` and `remove` call `reloadIfLoading()`, which cancels a running load and starts a new one that keeps the pulled flag (`BookmarksViewModel.kt:250-270`). The load checks `ensureActive()` before it writes (`:123`). On the single-threaded main dispatcher, nothing can run between that check and `state.update`. Covered by the held-load test, which expects 3 list calls and only the remaining bookmark.
- CR-4 (offline text claims old bookmarks are shown): resolved. The new `bookmarks_offline_empty` string exists in English and German, and `ListMessage` picks the text from `loaded` (`BookmarksScreen.kt:320-326`). Covered by the English and German screen tests. Both string files now have the same 32 keys.

## Acceptance criteria check

Checked against the code and the tests. Gradle results are as the parent reported them: ktlintFormat, ktlintCheck, the bookmarks unit tests (62 passed) and lintDebug passed. They were not re-run here.
- #1 List with title, site, an https-only favicon (otherwise the site's initial), notes and tags; Read later as the default view, plus All; tag filter; client-side search: implemented and tested.
- #2 ACTION_VIEW open with a fallback when no browser is installed; mark read/unread, edit title/tags/notes, delete to Trash with an Undo snackbar: implemented and tested.
- #3 Pull to refresh, loading, empty and error states: implemented and tested.
- #4 Reload on resume and when CaptureQueue pendingCount drops: implemented and tested in the view model.
- #5 values and values-de contain the same keys.
- #6 Tests exist for the view model, the repository (MockWebServer) and the screen (Robolectric Compose). `python tasks.py verify and-bookmarks` shows no evidence recorded yet for #1-#6. The parent worker must record it, including a full `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` run, because the fixer's run was limited to the bookmarks tests.

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
