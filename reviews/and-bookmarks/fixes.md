# Fix Report

Date: 2026-11-01

Task: and-bookmarks - Android: Bookmarks screen (list, read later, open, edit)

Scope: current task; findings CR-1 to CR-4 from reviews/and-bookmarks/review.md, all approved for fixing by the parent worker. Base commit 21f4c93132a459c2c8e8af31f820803e16126865, task commits 35e89034e, e1b3c5a02, 39ce4e1e4. Changes are uncommitted in the working tree.

## Fixed findings

### CR-1 — Overlapping equal failure snackbars get stuck and hide later failures

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/bookmarks/BookmarksViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/bookmarks/BookmarksScreenTest.kt`

Problem:
`BookmarksSnackbar.Failed` (and `Trashed`) were data classes without identity, so two failures of the same kind were equal. The screen's `LaunchedEffect(snackbar)` did not restart for the second one, and `dismiss` compared by `===`. Verified against the code: because `MutableStateFlow` drops an update whose value is equal to the current one, the state actually keeps the first instance, so the second equal failure was silently merged into the first (never shown on its own). Either way the user lost feedback for the second failure, and the `===`/`equals` mismatch was fragile.

Fix:
`BookmarksSnackbar` now has an `id`. `Trashed` and `Failed` take `id` as a constructor property (default 0, so screen tests and previews can still build them); `NoBrowser` stays a singleton with id 0 (a second NoBrowser while the first is visible is the same message). The view model numbers every `Trashed`/`Failed` it shows (`nextSnackbar()`), so equal-content snackbars are distinct values: the state changes, the screen effect restarts and shows the new one, and `dismiss` now matches with `==` (which includes the id), so the end of the first snackbar never clears the second.

Tests:
- New view model test "two equal failures in a row are two snackbars, and the end of the first keeps the second": two offline failures produce two unequal snackbars, dismissing the first keeps the second, dismissing the second clears it, a later equal failure is shown again.
- New screen test "an equal failure after another is shown again and reports its own end": `Failed(OFFLINE, id = 1)` replaced by `Failed(OFFLINE, id = 2)` shows again and the dismiss callback reports id 2.
- Existing view model assertions that compared against `BookmarksSnackbar.Failed(kind)` by equality now compare the failure kind through a `failure(vm)` helper (the snackbar now carries the view model's number). The asserted behavior is unchanged; no assertion was weakened.

Validation:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.bookmarks.*' lintDebug` — Pass

### CR-2 — A failed edit save throws away what the user typed

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/bookmarks/BookmarksViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/bookmarks/BookmarksScreenTest.kt`

Problem:
`save` closed the editor before the PATCH ran; on failure (offline, 409/412 conflict, 400) only a snackbar was shown and the draft was gone.

Fix:
`BookmarksUiState` has a new `draft: BookmarkDraft?`. `failed(...)` now returns the latest known version of the bookmark (the reloaded one after a conflict, the sent one otherwise, `null` when the bookmark is gone). `save` passes an `onFailure` to `change` that reopens the editor (`reopen`) on that version with the unsaved draft, unless the bookmark is gone (404) or another bookmark is being edited meanwhile. `BookmarkEditor` starts from the draft when one is given. `edit`, `cancelEdit` and `save` clear the draft. The dialog still closes immediately on Save (existing behavior), and the failure snackbar is still shown.

Tests:
- New view model test "a failed save opens the editor again with the draft, after a conflict on the reloaded version": offline save reopens on the same version with the draft; a 412 reopens on the reloaded version with the draft; the next save sends the reloaded version and closes the editor; a save of a bookmark gone meanwhile (404) does not reopen.
- New screen test "the editor opened again after a failed save shows the unsaved draft": the fields show the draft and Save sends it.

Validation:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.bookmarks.*' lintDebug` — Pass

### CR-3 — An older list load can bring back a bookmark that was just deleted or marked read

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/bookmarks/BookmarksViewModelTest.kt`

Problem:
A local change (delete, read toggle, save, conflict reload, 404 drop) did not affect a running list load, so a GET started before the change could overwrite the list with the old state.

Fix:
Every local list change now goes through `put` or the new `remove`, which call `reloadIfLoading()`: if a load is running, it is cancelled and a new one is started (keeping the pulled-refresh flag), so the list that arrives reflects the change and still picks up whatever the reload was for (for example a share that just left the queue). The load coroutine also calls `ensureActive()` before writing its result, so a cancelled load never applies its list even if its last call returned without a suspension point.

Tests:
- New view model test "a load that started before a change does not bring the old bookmark back": a reload is held open, a bookmark is deleted, a new load runs, the held load completes later with the deleted bookmark; the list keeps only the remaining bookmark, loading is false, three list calls were made.

Validation:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.bookmarks.*' lintDebug` — Pass

### CR-4 — Offline after switching view or tag says the last bookmarks are shown when the list is empty

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/BookmarksScreen.kt`
- `android/app/src/main/res/values/strings_bookmarks.xml`
- `android/app/src/main/res/values-de/strings_bookmarks.xml`
- `android/app/src/test/java/ch/sonensei/cockpit/bookmarks/BookmarksScreenTest.kt`

Problem:
The offline message always read "Showing the last loaded bookmarks", also when nothing was loaded for the current view or tag.

Fix:
New string `bookmarks_offline_empty` ("Can't reach the server." / "Der Server ist nicht erreichbar."). `ListMessage` now gets `loaded` and uses the new text when nothing is loaded for the view and tag, and the existing text otherwise. The values and values-de files still have the same keys (32 each).

Tests:
- New screen test "offline with nothing loaded for the view does not claim to show the last bookmarks": the short text is shown when not loaded, the long text once a list is loaded.
- New screen test "offline with nothing loaded is translated to German" (`@Config(qualifiers = "de")`).

Validation:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.bookmarks.*' lintDebug` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.bookmarks.*' lintDebug` — BUILD SUCCESSFUL (2m 13s). Bookmarks tests 62/62 passed, 0 skipped: BookmarksViewModelTest 25, BookmarksScreenTest 21, BookmarksModelsTest 7, BookmarksRepositoryTest 6, BookmarksRouteTest 3. ktlintFormat changed no files beyond these edits; ktlintCheck and lintDebug clean.

Full test suite:
- Not run by the fixer (the parent runs the full Android suite and `.taskcheck`).

Lint/type-check/build:
- ktlintCheck and lintDebug passed in the command above; compileDebugKotlin and compileDebugUnitTestKotlin ran as part of it.

`.taskcheck`:
- Not run (left to the parent worker; `sh scripts/check.sh` covers all parts).

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
