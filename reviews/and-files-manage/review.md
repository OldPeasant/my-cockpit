# Code Review

Date: 2026-11-08

Task: and-files-manage - Android files: new folder, rename, move, delete

Scope: Committed changes c670a3fc..8c57f7a9 (reviewed HEAD 8c57f7a976ef08f518a37152760e4e91f4857517). Primary: production code in android/app/src/main/java/ch/sonensei/cockpit/files and res/values{,-de}/strings_files.xml from 496bfdd6 (implementation) and 8c57f7a9 (fixes for the previous review's CR-1..CR-4). Test commits 54955c19 and 6493185f, plus the test part of 8c57f7a9, were read for coverage. The working tree was clean when the review was done.

Requirements:
- tasks/and-files-manage.md acceptance criteria
- SPEC.md §4.2 (ETag/If-Match), §8.2 (Files)
- AGENTS.md project notes
- Server contract: server/src/main/kotlin/ch/sonensei/cockpit/files/domain/FilesService.kt (409/404 semantics of createFolder, updateFolder, updateFile, deleteFolder, deleteFile), files/api/FilesWebSupport.kt (412/428)
- Previous review reviews/and-files-manage/review.md (revision 9a43afc0) and fixes in reviews/and-files-manage/fixes.md

Git range:
- Branch: main
- HEAD: 8c57f7a976ef08f518a37152760e4e91f4857517
- Base/start: c670a3fc7efe1beb679d18f688a3bd7e90a4ec49
- Commits reviewed: 4 (496bfdd6, 54955c19, 6493185f, 8c57f7a9)

Summary: 0 P0, 0 P1, 0 P2, 2 P3

Status of the earlier findings (checked against the code at HEAD):
- CR-1 (retrying after a 412 from the rename dialog or move picker) is resolved. After a refusal that maps to CHANGED, `reloadStale()` sets `adoptVersions`. The next successful load runs `FilesUiState.withVersionsOf`, which swaps in the reloaded versions by id for the dialog entry and the picker entries (FilesViewModel.kt:176-189, :512-526). An ordinary reload leaves the versions alone. Tests at FilesViewModelTest.kt:545, :571, :897 and :928 cover this.
- CR-2 (wrong meaning given to a 409) is resolved, and the mapping matches FilesService. A 409 on create or folder rename means NAME_TAKEN, a 409 on a folder move means MOVE_CONFLICT, and a 409 on a file rename, file move or any delete means CHANGED with a reload (FilesViewModel.kt:249, :325, :364, :489). updateFile throws no ConflictException, and the client never sends a delete for a special folder, so those 409s can only come from something else.
- CR-3 (confirmation names a failed entry) is resolved. The name now comes from the first entry whose result is `Ok` (FilesViewModel.kt:339, :368).
- CR-4 (control characters) is resolved. `nameProblem` now rejects ISO control characters (FilesModels.kt:50), and both EN and DE `files_name_invalid` mention them.

What else was checked and holds:
- If-Match carries the strong ETag `"<version>"`.
- A move to the root sends an explicit JSON `null` through the hand-built JsonObject body, which goes through the kotlinx-serialization converter registered in NetworkModule.
- Folder moves use `parentId` and file moves use `folderId`.
- Special folders have no menu and cannot be selected, and the folders being moved cannot be opened in the picker.
- Double submits are guarded by `saving`, `moving` and `working`.
- A picker load cancels the one before it.
- Back clears the selection before it leaves the folder.
- Every new string is in both values/ and values-de/, plurals included.
- Nothing logs names or contents, and no secrets were found.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | A rename or move refused with 404 says "try again", but every retry gets 404 again**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/files/FilesViewModel.kt:517` (rename dialog), `:520` and `:502` (move picker)
    - Category: Correctness
    - Problem: `actionMessage` turns a 404 into CHANGED ("It was changed elsewhere meanwhile. The folder was reloaded; try again"), and `reloadStale()` runs. Two cases get stuck after that:
      - Rename dialog: `withVersionsOf` keeps the old entry when the reloaded list doesn't have it (`byId[it.id] ?: it`). If the entry was trashed elsewhere, the dialog stays open, and every Rename press gets 404 and CHANGED again. The picker already handles this case: it drops gone entries and closes when none are left.
      - Move picker: according to FilesService.updateFile/updateFolder, a 404 on a move also means "the target folder does not exist or is in the Trash". The entries being moved are still in the origin folder, so they stay in the picker. The picker's own folder list isn't reloaded, so "Move here" into the trashed folder keeps returning 404 and CHANGED. The test at FilesViewModelTest.kt:588 confirms that a 404 rename keeps the dialog open.
    - Why it matters: The message tells the user to try again, but trying again can't work. The user has to work out that Cancel is the only way out. This is small (Cancel always works, and nothing is lost), but it is the same kind of dead end CR-1 of the previous review fixed for 412.
    - Suggested fix: In `withVersionsOf`, close the name dialog when its entry isn't in `fresh`, and set `actionMessage = CHANGED`, the same way the picker handles gone entries. For a move that fails with 404 while its entries are still there, reload the picker's folder (`loadPicker()`) or send it back to `origin`, so a trashed target disappears from view. Add a test for each case.

- [ ] **CR-2 | The formatter split simple expressions in the move picker across several lines**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/files/FilesScreen.kt:529-550`
    - Category: Quality
    - Problem: `if (folder.special !=\n null) { ... }` and `if (picker.canOpen(\n folder,\n )) { ... }` are spread over 22 lines. `picker.canOpen(folder)` is also evaluated three times in the same row (lines 519, 537, 543).
    - Why it matters: This is hard to read in a composable that is already long, and it doesn't match the compact style used elsewhere in the file (for example `FolderRow`, line 704).
    - Suggested fix: Compute `val openable = picker.canOpen(folder)` and `val kind = if (folder.special != null) FileKind.SPECIAL_FOLDER else FileKind.FOLDER` before the `Row`, then pass these values in. ktlint keeps short expressions like these on one line.

## Review conclusion

No material issues found.

The four findings of the previous review are resolved, and the two new findings are low priority. Note for the parent worker: `python tasks.py verify and-files-manage` still lists all four criteria as unrecorded. They need evidence before `done`, including a fresh `sh scripts/check.sh` run, because fixes.md records a server test failure there that it treats as unrelated. This review did not run Gradle or the check, because it was a read-only review.
