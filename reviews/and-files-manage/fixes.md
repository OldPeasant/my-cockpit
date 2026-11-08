# Fix Report

Date: 2026-11-08

Task: and-files-manage - Android files: new folder, rename, move, delete

Scope: Findings CR-1 to CR-4 of reviews/and-files-manage/review.md, limited to android/app/src/main/java/ch/sonensei/cockpit/files, android/app/src/test/java/ch/sonensei/cockpit/files and res/values{,-de}/strings_files.xml. Base c670a3fc, task commits 496bfdd6, 54955c19, 6493185f (the review cites the pre-rebase hashes 68e70cb9 / 9a43afc0 of the same commits).

## Fixed findings

### CR-1 - After a 412, retrying from the open rename dialog or move picker always fails again

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/files/FilesViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/files/FilesViewModelTest.kt`

Problem:
The reload after a stale refusal updated only `state.entries`. The open `NameDialog.entry` and `MovePicker.entries` kept the old version, so every retry sent the old `If-Match` and got 412 again.

Fix:
A refusal that maps to CHANGED (412, 404, and a 409 that means a concurrent change, see CR-2) now calls `reloadStale()`. This sets `adoptVersions` and reloads. The next successful load replaces the dialog's entry and the picker's entries with the reloaded versions by id (`FilesUiState.withVersionsOf`). Entries gone from the picker's origin folder are dropped. If none are left, the picker closes and shows the CHANGED message in the list. An ordinary reload (no refusal) does not change the version an open dialog sends, so optimistic locking still catches concurrent edits.

Validation:
- New tests: `a rename refused as outdated is retried with the reloaded version` (412, the reload brings version 2, the retry sends version 2), `a move refused as outdated is retried with the reloaded version`, `a move of something gone elsewhere closes the picker once the reload no longer has it`, `an ordinary reload does not change the version an open rename sends` - Pass

### CR-2 - 409 responses are mapped to messages that are wrong for files and for delete

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/files/FilesViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/files/FilesViewModelTest.kt`

Problem:
Every 409 became NAME_TAKEN (create/rename) or MOVE_CONFLICT (move and delete), and none of them triggered a reload. On the server (FilesService, openapi updateFile "File names need not be unique"), a 409 is a name clash or a move into the folder's own subtree only for folders. For file rename/move and for delete, a 409 means a concurrent change.

Fix:
`actionMessage(conflict = ...)` now takes the meaning of a 409 per operation and entry type. Create and folder rename map it to NAME_TAKEN, folder move to MOVE_CONFLICT. File rename, file move and delete map it to CHANGED, which reloads the list (and adopts versions, CR-1). Delete already reloaded after every attempt.

Tests changed, with the reason: two committed tests encoded the old meaning of a 409 on a file. They contradict the server contract, so they now use a folder and still cover NAME_TAKEN and MOVE_CONFLICT:
- `a taken name keeps the dialog open with the reason, and nothing is reloaded` now renames folder `docs` to `tax`.
- `a partly failed move keeps only what was not moved in the picker, with the reason` now gets the 409 on folder `tax`.

Validation:
- New tests: `a conflict on a file rename says it was changed elsewhere and reloads`, `a conflict on a file move says it was changed elsewhere and reloads`, `a delete refused with a conflict says it was changed elsewhere and reloads` - Pass
- Committed test `a delete the server refuses with a conflict does not say an item was not moved` - Pass (it failed before the fix)

### CR-3 - When the first entry failed, the confirmation of a partial move or delete names that failed entry

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/files/FilesViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/files/FilesViewModelTest.kt`

Problem:
`FilesEvent.Changed` used `entries.first().name`, whatever the outcome of each entry.

Fix:
The name now comes from the first entry whose result is `Ok`, for both move and delete. If no entry succeeded, no event is sent, as before.

Test changed, with the reason: the committed test `deleting the selection counts what was already gone as done, and ends the selection` expected the name "Documents". That entry returned 404 (already gone) and is not among the 2 trashed entries, so the confirmation now names "2025.pdf", the first entry actually trashed. A comment in the test says why.

Validation:
- Committed tests `the confirmation of a partly failed move names what was moved` and `the confirmation of a partly failed delete names what was trashed` - Pass (both failed before the fix)

### CR-4 - The client name check misses control characters, which the server refuses

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/files/FilesModels.kt`
- `android/app/src/main/res/values/strings_files.xml`
- `android/app/src/main/res/values-de/strings_files.xml`
- `android/app/src/test/java/ch/sonensei/cockpit/files/FilesModelsTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/files/FilesScreenTest.kt`

Problem:
`nameProblem` left out the "no ISO control characters" rule of the server's `FileNames.validate`.

Fix:
A name that still contains an ISO control character after trimming is now INVALID. `files_name_invalid` now names control characters in EN ("... or control characters such as tabs or line breaks") and DE ("... noch Steuerzeichen wie Tabulatoren oder Zeilenumbrüche ..."). The FilesScreenTest assertion of the English text was updated to the new string.

Validation:
- New test: `control characters inside a name are invalid` (tab, newline, BEL, DEL) - Pass
- `FilesScreenTest` `the new folder dialog creates only a valid name and says what is wrong once typed` - Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*'` - Pass, 141 tests, 0 failures (DeviceFilesTest 5, FilesModelsTest 18, FilesRepositoryTest 19, FilesRouteInAppTest 21, FilesScreenTest 26, FilesViewModelTest 52)

Full test suite:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` - Pass (BUILD SUCCESSFUL)

Lint/type-check/build:
- Included above: ktlintCheck and lintDebug pass. Formatting was applied with `./gradlew ktlintFormat`, and no lint warning was suppressed.

`.taskcheck`:
- Fail, for a reason outside this task: `sh scripts/check.sh` finished, but the server suite had 1 failure among 5205 tests, `PageFetcherSizeLimitTests > pages larger than max-size are cut off reliably, also over a reused HTTP 2 connection()`. The task notes already record this as unrelated and possibly flaky. These fixes touch no server code. The Android part of the check passes.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed. The Android gate passes. `.taskcheck` fails only in the unrelated server test PageFetcherSizeLimitTests, which the parent worker should re-run before `done`.

## Second review (4e45f4c65) findings

Scope: the two P3 findings of the second review (`git show 4e45f4c65:reviews/and-files-manage/review.md`, reviewed HEAD 8c57f7a9). The previous parent worker fixed both itself in commit c700dfcff. The fixer did not change any production or test code in this round. It read the diff of c700dfcff, the code at HEAD 4294509ec and the tests, and confirmed that each fix resolves its finding. The third review (reviews/and-files-manage/review.md) also confirms both as resolved. The two new P3s of the third review are outside this report: the parent worker accepted them without a fix.

### Fixed findings

#### Review 2 CR-1 - A rename or move refused with 404 says "try again", but every retry gets 404 again

Fixed by: the parent worker in c700dfcff. Verified by the fixer.

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/files/FilesViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/files/FilesViewModelTest.kt` (c700dfcff, plus test-writer tests in 4294509ec)

Problem:
A 404 maps to CHANGED and triggers `reloadStale()`. Two cases stayed stuck. In the rename dialog, `withVersionsOf` kept the old entry when the reload no longer contained it (`byId[it.id] ?: it`), so every retry got 404 again. In the move picker, a 404 can also mean the target folder was trashed. The entries were still in the origin folder and the picker's folder list was not reloaded, so "Move here" kept targeting the trashed folder.

Fix:
- Rename dialog: `FilesUiState.withVersionsOf` now closes the dialog and sets `actionMessage = CHANGED` when the dialog's entry is missing from the reloaded folder. The check runs only when the dialog is not `saving`, so a reload cannot close a dialog whose save is still in progress. A create dialog (no entry) is unaffected. This matches how the picker already handled gone entries.
- Move picker: `confirmMove` computes `targetGone`, which is true when any failure is `FilesResult.Failed` with status 404. In that case the picker keeps the entries that failed, its `path` goes back to `picker.origin`, and `loadPicker()` reloads that folder, so a trashed target is no longer offered. Back at the origin, `canMove` is false, so the user has to pick another folder instead of retrying the dead target. If the 404 actually means the entry itself is gone, the stale reload that follows (`reloadStale()`, the picker no longer `moving`, origin equals the reloaded folder) drops that entry from the picker. When no entry is left, it closes the picker with the move message, as before.

Verification by the fixer (read-only):
- `saveName` sets `saving = false` before it calls `reloadStale()`, so the `gone` check applies to the reload that follows a 404. The `saving` guard stops an unrelated reload from closing a dialog while a save is running.
- `loadPicker()` reads the updated `movePicker.path` (origin) from state after the `state.update`, so the reload lists the origin folder, not the trashed target.
- The CHANGED path and the `adoptVersions` behavior from the first round (CR-1, CR-2) are unchanged.

Tests:
- c700dfcff: `a rename of something gone elsewhere closes the dialog once the reload no longer has it`, `a move into a folder gone elsewhere sends the picker back to where the entries are`
- 4294509ec (test-writer): `a move into a folder gone elsewhere sends the picker back to a subfolder the entry is in`, `a partly moved selection whose other entry is gone elsewhere closes the picker after the reload`, `a rename dialog is not closed by a reload while its save is still running`

Validation (run by the parent worker at HEAD 4294509ec, with no other Gradle process running. The fixer did not run Gradle because the parent was running scripts/check.sh at the same time):
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.files.FilesViewModelTest'` (test-writer, focused) - Pass, 61/61
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` - Pass, BUILD SUCCESSFUL, 3430 tests, 0 failures, 0 skipped

#### Review 2 CR-2 - The formatter split simple expressions in the move picker across several lines

Fixed by: the parent worker in c700dfcff. Verified by the fixer.

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/files/FilesScreen.kt` (`MovePickerDialog`, picker row)

Problem:
The `KindTile` kind and the name color in the picker row took up 22 lines, and `picker.canOpen(folder)` was evaluated three times per row.

Fix:
The row now computes `openable = picker.canOpen(folder)`, `kind` (SPECIAL_FOLDER or FOLDER) and `nameColor` once, before the `Row`, and passes them in. Behavior is unchanged: the row is still clickable only when `openable && !picker.moving`, the tile is dimmed when not openable, and the name is drawn in `outline` when not openable. Test tags are unchanged.

Validation:
- No new test is needed for a behavior-preserving refactor. The existing FilesScreenTest and FilesRouteInAppTest picker tests and ktlintCheck pass in the parent's run above (3430 tests, 0 failures, BUILD SUCCESSFUL).

### Not fixed

None.

### Second-round summary

Remaining unresolved findings of the second review: 0. Both fixes in c700dfcff are correct and covered by tests. The fixer made no code changes in this round.
