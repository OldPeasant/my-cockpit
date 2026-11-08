# Code Review

Date: 2026-11-08

Task: and-files-manage - Android files: new folder, rename, move, delete

Scope: The final task code in c43ccaaf8..HEAD, limited to android/app/src/main/java/ch/sonensei/cockpit/files, android/app/src/test/java/ch/sonensei/cockpit/files, res/values/strings_files.xml and res/values-de/strings_files.xml. The parent worker supplied the commit list: 199268e29 (implementation), 9838aa27c and 1b147bd12 (tests), 2496829f5 (fixes for the first review's CR-1..CR-4), 4e45f4c65 (tests and the second review), c700dfcff (the parent's fixes for the second review's P3s), and the latest commit (three new FilesViewModelTest tests). Merge 258c31e3b brought in and-scheduled-list, which is outside this scope. This review looked most closely at c700dfcff and the new tests, but it covers the whole final code.

Limitation: Bash was denied in this review session, so `git diff`, `git log` and `git rev-parse` could not be run. The scope and commits above are as the parent worker supplied them. Instead of reading the diff, this review read the current version of every file in scope in full: FilesViewModel.kt, FilesScreen.kt, FilesRepository.kt, FilesModels.kt, both strings_files.xml files, FilesViewModelTest.kt in full, and the start of FilesScreenTest.kt. It also read the server contract (FilesService.kt). Gradle was not run, as the parent instructed.

Requirements:
- tasks/and-files-manage.md acceptance criteria 1-4
- SPEC.md §4.2 (ETag/If-Match) and §8.2 (Files), as summarised in the earlier reviews and AGENTS.md project notes
- Server contract: server/src/main/kotlin/ch/sonensei/cockpit/files/domain/FilesService.kt (createFolder, updateFolder and deleteFolder throw NotFoundException/ConflictException)
- Previous review (the second review, 2 P3) in reviews/and-files-manage/review.md

Git range:
- Branch: main
- HEAD: the current HEAD, i.e. the test-writer commit on top of c700dfcff (the hash could not be resolved because Bash was denied)
- Base/start: c43ccaaf8c21f7cdacfd56882a1bd7c2400066a2
- Commits reviewed: 7 task commits (199268e29, 9838aa27c, 1b147bd12, 2496829f5, 4e45f4c65, c700dfcff, the latest test commit); merge 258c31e3b is out of scope

Summary: 0 P0, 0 P1, 0 P2, 2 P3

Status of the second review's findings, checked against the current code:
- CR-1 (a 404 retry dead end in the rename dialog and the move picker) is resolved for rename and move.
  - Rename: `withVersionsOf` closes the dialog and sets `actionMessage = CHANGED` when the reloaded folder no longer contains the entry. It does this only while no save is running (FilesViewModel.kt:525-532).
  - Move: `confirmMove` notices a 404, sends the picker back to `origin` and reloads it, so a trashed target is no longer listed (FilesViewModel.kt:327-346). A gone entry is still dropped by `withVersionsOf`.
  - Tests cover each case: FilesViewModelTest.kt:628 (rename, entry gone), :972 and :1413 (move into a trashed target, at the root and in a subfolder), :1453 (partly moved selection with a gone entry), and :1487 (a reload arriving while a second save is still running does not close the dialog).
  - The same dead end remains for creating a folder; see CR-2 below.
- CR-2 (picker row formatting) is resolved. `openable` and `kind` are now computed once per row (FilesScreen.kt:519-521), and the row is compact.

What else was checked and holds:
- Criterion 1:
  - New folder, rename, move with the folder picker, and delete to the Trash work for both files and folders.
  - If-Match carries the strong ETag of the entry's version.
  - Folder moves send `parentId` and file moves send `folderId`. A move to the root sends an explicit JSON null.
  - Special folders have no menu and cannot be selected.
- Criterion 2:
  - A long press selects an entry, and while selecting a tap toggles it.
  - The selection bar offers move and delete.
  - Back ends the selection first.
  - Entries that disappear on a reload are dropped from the selection.
  - A partly failed move or delete keeps the selection, and the picker keeps only what was not moved, so nothing is moved twice.
- Criterion 3: every string and plural used by the new UI is in both values/ and values-de/, with the same format arguments.
- Mapping of 409/412/404 to messages: it matches FilesService.
  - A 409 means NAME_TAKEN when creating or renaming a folder, MOVE_CONFLICT when moving a folder, and CHANGED otherwise.
  - A 404 on delete counts as done.
- Double submits are guarded by `saving`, `moving` and `working`.
- A picker load cancels the one before it, and a closed picker is not reopened by a late answer.
- The adoption of reloaded versions (`adoptVersions`) applies only after a refusal, and it is skipped while a move or save is running.
- Nothing logs names or contents, and no secrets were found.
- The new tests assert observable state and events, not internals.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | A failed move restores the picker as it was when "Move here" was tapped, so a folder load that finished during the move is lost**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/files/FilesViewModel.kt:316-341`
    - Category: Correctness
    - Problem: `confirmMove` captures `picker` at the tap. When the move fails, it writes back `picker.copy(entries = ..., path = ..., moving = false, moveMessage = ...)`. That brings back `folders`, `loading` and `message` as they were at the tap. "Move here" is enabled while the picker is still loading, because `canMove` does not check `loading`. Say the user opens a folder and taps "Move here" before its list arrives. The list then arrives during the move (`updatePicker` sets the folders and `loading = false`), and the move fails, for example with a 409, OFFLINE or 412. In that case the picker goes back to `loading = true` with no folders, and nothing ever clears that state, because the picker load has already finished. The 404 branch is not affected, because it calls `loadPicker()` again.
    - Why it matters: The progress bar runs forever and the subfolders don't show, so the user can't browse further down from there. The user can recover by tapping a breadcrumb, and no data is lost, but the screen is misleading.
    - Suggested fix: Build the failure state from the current picker (`updatePicker { it.copy(entries = ..., path = ..., moving = false, moveMessage = ...) }`), or disable "Move here" while `loading`. Add a test where the picker load finishes during a failing move.

- [ ] **CR-2 | A new folder inside a folder trashed elsewhere says "try again", but every retry gets 404 again**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/files/FilesViewModel.kt:249`, `:255-256` (locked in by FilesViewModelTest.kt:1296)
    - Category: Correctness
    - Problem: FilesService.createFolder throws NotFoundException when the parent is in the Trash. `actionMessage` turns that 404 into CHANGED, "It was changed elsewhere meanwhile. The folder was reloaded; try again.", and `reloadStale()` runs. The reload of the trashed folder fails as well (the list shows FAILED), and the dialog stays open. Each Create press sends the same request and gets 404 again. This is the dead end the second review's CR-1 described, but for creating a folder instead of renaming or moving. The new test at FilesViewModelTest.kt:1296 asserts this state (the dialog stays open with CHANGED, and the list shows FAILED) as the intended behaviour. `adoptVersions` also stays set after the failed reload.
    - Why it matters: The message says the folder was reloaded and asks the user to try again, but the reload failed and trying again can't work. Cancel is the only way out. This is small: it is rare, and nothing is lost.
    - Suggested fix: When a create fails with 404, close the dialog and show the reason in the list (or go up to the last folder that still exists). As an alternative, keep the dialog open but show a message that doesn't promise a retry. Update the test at :1296 to match.

## Review conclusion

No material issues found.

The second review's two P3 findings are resolved, although the same 404 dead end remains for creating a folder (CR-2). The two new findings are low priority and optional. This review did not run Gradle or `sh scripts/check.sh`, because the parent is running them. Because Bash was denied, the exact diff could not be listed with git. Instead, every file in scope was read in full at its current version.
