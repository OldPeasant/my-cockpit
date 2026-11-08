# Code Review

Date: 2026-11-08

Task: and-files-manage - Android files: new folder, rename, move, delete

Scope: Production code of the task, commit 68e70cb9 (android/app/src/main/java/ch/sonensei/cockpit/files, res/values*/strings_files.xml) on top of base f4d52874. Test commit 9a43afc0 was read for context only; uncommitted test changes in the working tree (FilesRouteInAppTest.kt, being edited by a concurrent test-writer) were not reviewed.

Requirements:
- tasks/and-files-manage.md acceptance criteria
- SPEC.md §4.2 (ETag/If-Match), §8.2 (Files)
- docs/PARITY.md (row "Files: no new folder/rename/move/delete")
- api/openapi.yaml (createFolder, updateFile, updateFolder, deleteFile, deleteFolder, FilePatchRequest, FolderPatchRequest)
- server FilesService / FileNames / ETags (actual 409/412 semantics)
- web reference web/src/app/features/files/browser (files-page.ts, move-dialog.ts, name-dialog.ts)
- AGENTS.md project notes

Git range:
- Branch: main
- HEAD: 9a43afc070496be5f0b0c060097e869cb0d22676
- Base/start: f4d52874950632743a0ab4f704c61c947c4acd87
- Commits reviewed: 2 (68e70cb9 production, primary; 9a43afc0 tests, context)

Summary: 0 P0, 0 P1, 2 P2, 2 P3

What was checked and holds: the If-Match value matches the server's strong ETag (`"<version>"`, core/web/ETags.kt). A move to the drive root sends an explicit `null` for `folderId`/`parentId` through the hand-built JSON body, as FilePatchRequest/FolderPatchRequest require. Folders use `parentId` and files use `folderId`. `Response<Unit>` for the 200 PATCH responses is handled by Retrofit's built-in Unit converter. The move picker can't open the folders being moved, so a folder can't be moved into its own subtree from the UI. Special folders have no menu and can't be selected (the server refuses them anyway). Deleting has no confirmation, which matches the web (files-page.ts "no confirmation, as it can be restored"). Double submits are guarded (`saving`, `moving`, `working`), and picker loads cancel the earlier load. Every new string is in both values/ and values-de/, plurals included. No secrets and no logging of names or contents were found.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | After a 412, retrying from the open rename dialog or move picker always fails again**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/files/FilesViewModel.kt:235` (rename dialog), `:311` (move picker)
    - Category: Correctness
    - Problem: When a rename fails, the dialog stays open with `dialog.copy(...)`, so `NameDialog.entry` keeps the old `version`. When a move partly fails, the picker keeps `picker.copy(entries = failures...)`, which also holds the old versions. After a 412, `refresh()` reloads `state.entries`, but it never updates the entry held by `nameDialog` or `movePicker`. The message tells the user "The folder was reloaded; try again" (`files_action_changed`). Pressing Rename or "Move here" again sends the same old `If-Match` and gets 412 every time. The only way out is to cancel and start over from the list.
    - Why it matters: The recovery path the UI offers can never succeed, so a normal concurrent edit (e.g. the same file renamed on the web) leaves the dialog in a retry loop.
    - Suggested fix: On a stale result, either close the dialog or picker so the user restarts from the reloaded list, or, once the refresh finishes, replace the entries held by the dialog or picker with their reloaded versions by id (drop the ones that are gone). Add a ViewModel test: rename returns 412, the list reloads with a newer version, and the retry sends the new version.

- [ ] **CR-2 | 409 responses are mapped to messages that are wrong for files and for delete**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/files/FilesViewModel.kt:464-470` (used at `:235`, `:303`, `:338`)
    - Category: Correctness
    - Problem: `actionMessage(nameTaken)` turns every 409 into NAME_TAKEN (create/rename) or MOVE_CONFLICT (everything else). On the server, though:
      - `deleteFile`/`deleteFolder` can only return 409 for a concurrent change (the client blocks special folders). The delete path still shows MOVE_CONFLICT, "Not moved: the folder already has an item with that name, or a folder would go into itself", after a delete.
      - `updateFile` never checks names ("File names need not be unique", openapi updateFile). A 409 on a file rename or move is a concurrent change, but it shows "That name is already in this folder" or "Not moved: ...".
      
      Because `isStale()` only covers 412/404, these concurrent-change 409s also don't trigger a reload. The test-writer's committed test `a delete the server refuses with a conflict does not say an item was not moved` (FilesViewModelTest.kt:961) expects the opposite of the current behavior.
    - Why it matters: Users get a false explanation and the list isn't reloaded after a concurrent change.
    - Suggested fix: Choose the 409 meaning per operation and entry type: NAME_TAKEN/MOVE_CONFLICT only for folder create, rename and move. For delete and for file rename/move, map 409 to CHANGED and reload, as for 412.

## P3 Low

- [ ] **CR-3 | When the first entry failed, the confirmation of a partial move or delete names that failed entry**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/files/FilesViewModel.kt:316`, `:342`
    - Category: Correctness
    - Problem: The `Changed` event uses `picker.entries.first().name` / `entries.first().name` no matter which entries succeeded. Take a selection [A, B] where A fails and B succeeds: the toast says "Moved A" or "Moved A to the Trash", and the banner says A failed. The committed tests `the confirmation of a partly failed move names what was moved` (:816) and `... delete names what was trashed` (:946) expect the name of the entry that succeeded.
    - Why it matters: The confirmation contradicts the error shown at the same time.
    - Suggested fix: Take the name from the first entry whose result is `Ok`.

- [ ] **CR-4 | The client name check misses control characters, which the server refuses**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/files/FilesModels.kt:42-47`
    - Category: Correctness
    - Problem: `nameProblem` mirrors `FileNames.validate` but leaves out its "no ISO control characters" rule. A pasted name with a tab or newline passes the dialog's check, the server returns 400, and the user only sees the generic "That did not work."
    - Why it matters: Small, but the dialog promises to explain refusals before sending.
    - Suggested fix: Add `trimmed.any { it.isISOControl() }` to the INVALID case and to the text of `files_name_invalid` (EN + DE).

## Review conclusion

Material issues found; fix before completing the task.

Note for the parent worker: the committed tests at FilesViewModelTest.kt:816, :946 and :961 (commit 9a43afc0) encode CR-2 and CR-3. They are expected to fail against the production code in 68e70cb9 until those findings are fixed. This review did not run the Gradle suites (read-only review).
