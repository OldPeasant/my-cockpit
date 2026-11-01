# Code Review

Date: 2026-11-01

Task: and-bookmarks - Android: Bookmarks screen (list, read later, open, edit)

Scope: git diff 126a16a6b6e4fabc79733a53fd22976a920e83c8..69efcda30c4515a8690e2bd5b51c8d2be537be62. Commits: 55a1f3dbd implementation; e22139971 and ec6be222a tests; 443d2373a fixes for CR-1..CR-4 of the previous review; 69efcda30 test-only additions to BookmarksViewModelTest.kt. Committed changes only. The final code was reviewed in full, every earlier finding was checked, and the five tests added in 69efcda30 were reviewed against the production code. 69efcda30 does not change production code.

Requirements:
- tasks/and-bookmarks.md acceptance criteria and the five recorded assumptions
- SPEC.md §8.4, docs/ARCHITECTURE.md, AGENTS.md project conventions
- api/openapi.yaml (BookmarkPatchRequest, BookmarkResponse, /api/v1/trash/{type}/{id}/restore)
- Previous review (CR-1..CR-4) and reviews/and-bookmarks/fixes.md

Git range:
- Branch: main
- HEAD: 69efcda30c4515a8690e2bd5b51c8d2be537be62
- Base/start: 126a16a6b6e4fabc79733a53fd22976a920e83c8
- Commits reviewed: 5

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Earlier findings

- CR-1 (equal failure snackbars get stuck): resolved.
    - `BookmarksSnackbar` now has an `id`. The view model numbers every `Trashed` and `Failed` it shows (`BookmarksViewModel.kt:307`), so two snackbars with the same content are different values.
    - When a new snackbar arrives, `LaunchedEffect(snackbar)` restarts. That cancels the running `showSnackbar` without calling `onDismiss` for it.
    - `dismiss` compares with `==`, which includes the id (`BookmarksViewModel.kt:203`).
    - `NoBrowser` keeps id 0. Tapping again while the first message is still visible shows the same message, which is acceptable.
- CR-2 (a failed save loses the draft): resolved.
    - `failed()` returns the latest known version of the bookmark: the reloaded one after a 409/412, the version that was sent otherwise, and `null` after a 404.
    - `save` then calls `reopen` to open the editor again with the draft (`BookmarksViewModel.kt:170-174`, `:222-230`). It skips this when the bookmark is gone or another bookmark's editor is open.
    - `BookmarkEditor` starts from the draft (`BookmarksScreen.kt:514-516`). `edit` and `cancelEdit` clear the draft.
    - The editor reopens before `run`'s `finally` clears `busy`. Nothing suspends between the two steps on the main dispatcher, so a new save cannot be blocked by the busy flag.
- CR-3 (an older list load brings back a changed bookmark): resolved.
    - `put` and `remove` call `reloadIfLoading()`. If a load is running, it is cancelled and a new one starts with the same pulled flag (`BookmarksViewModel.kt:250-270`).
    - The load calls `ensureActive()` before it writes its result (`:123`).
- CR-4 (the offline text claims old bookmarks are shown when none are): resolved.
    - New string `bookmarks_offline_empty` in English and German.
    - `ListMessage` picks the text depending on `loaded` (`BookmarksScreen.kt:320-326`).
    - Both string files have the same 32 keys.

## Tests added in 69efcda30

I checked each new test against the code paths it exercises:
- **A save conflict whose reload is offline:** a 409 followed by an offline reload reopens the editor on the version that was sent, with the draft. A 409 followed by a 404 reload removes the bookmark and does not reopen the editor. `cancelEdit` clears the draft. This matches `failed()` lines 286-294 and `reopen`.
- **A failed save while another editor is open:** the failed save of bookmark 1 does not replace an editor opened for bookmark 2 in the meantime. This matches the `it.editing != null` guard in `reopen`.
- **Marking a bookmark read during a pulled refresh:** the load restarts with `refreshing` still set. The result of the cancelled load is discarded, and the new result is applied. This exercises the CR-3 fix through `put` and the cancellation of the old load.
- **A change without a running load:** it does not start a load. This checks that `reloadIfLoading` reloads only when a load is running.
- **Trashing the same bookmark twice:** after an undo, it gives two different `Trashed` snackbars, and dismissing the first keeps the second. This covers the CR-1 identity for `Trashed`.

The tests assert observable state through the repository fake: list contents, `editing`, `draft`, the snackbar, loading/refreshing, and call counts. No existing test was weakened or removed. Test data is fictitious.

## Acceptance criteria check

Checked against the code and the tests. Gradle results are as the parent reported them; I did not re-run them:
- `cd android && ./gradlew ktlintCheck createDebugUnitTestCoverageReport lintDebug`: exit 0, 2313 tests with 0 failures, lint 0 errors and 0 warnings.
- Bookmarks tests: 67 of 67 pass.

| # | Criterion | Status |
|---|---|---|
| 1 | List shows title, site, favicon and tags; Read later (default) and All views; tag filter; search | Implemented and tested. Favicons are https only; otherwise the site's initial is shown. Search runs on the client. |
| 2 | Open in the browser; mark read/unread, edit, delete with undo | Implemented and tested. Opening uses ACTION_VIEW, with a message when no browser is installed. Delete moves to the Trash with an Undo snackbar. |
| 3 | Pull to refresh; loading, empty and error states | Implemented and tested. |
| 4 | A shared bookmark appears without restarting the app | Implemented and tested in the view model: the list reloads on resume and when the CaptureQueue pendingCount drops. |
| 5 | English and German strings | values and values-de have the same keys. |
| 6 | Unit tests; ktlintCheck, testDebugUnitTest and lintDebug pass | View model, repository (MockWebServer) and screen (Robolectric Compose) tests exist. The full Android ktlint, unit test and lint run passed, as reported by the parent. |

The parent worker still has to record the evidence for each criterion with `tasks.py verify`.

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
