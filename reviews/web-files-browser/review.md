# Code Review

Date: 2026-10-21

Task: web-files-browser - Files: browser and operations

Scope: task commits e4d3f8a7, 27747043, d5d8ff48 on top of base 79a8ec19 (web/src/app/features/files/browser/*, web/src/app/features/files/files.routes.ts, web/e2e/files-browser/browser.spec.ts); server contract read from server/src/main/kotlin/ch/sonensei/cockpit/files/api/*

Requirements:
- tasks/web-files-browser.md (acceptance criteria and recorded assumptions)
- SPEC.md §8.2 (Files), §4.2 (ETag/If-Match, Trash)
- docs/ARCHITECTURE.md, web/src/app/features/README.md, AGENTS.md project notes
- Server API: DriveController.kt, FolderController.kt, FilesDtos.kt, FilesWebSupport.kt, FilesService.kt (special folder rules)

Git range:
- Branch: main
- HEAD: d5d8ff48
- Base/start: 79a8ec193e9fb82a82832b25d529dad71811ac07
- Commits reviewed: 3

Summary: 0 P0, 0 P1, 4 P2, 1 P3

Criteria check (from the code):
- #1 Folder tree, list/grid, breadcrumbs, new folder, rename, move dialog, delete (to Trash), download, folder as zip, disk usage: all present. Endpoints, methods, `If-Match` (`"<version>"`), PATCH bodies (`name`, `parentId`/`folderId`, explicit `null` for the root) and the usage fields match the server contract. Special folders hide rename/move/delete, consistent with the server's `ConflictException` rules.
- #2 Vitest covers create, rename, move (folder and file to root), delete, special folders, load error with retry, usage failure, name validation and server errors. Gaps are in CR-3.
- #3 The Playwright spec covers create folder, rename, move through the picker and delete to the Trash, and checks the Trash through the API.
- `python tasks.py verify web-files-browser` showed no criterion recorded yet when this review ran. The parent worker still has to record the evidence.

Security: no secrets or personal data in the diff. File names are rendered through interpolation only. Download URLs use `encodeURIComponent` on the id under the same-origin `rootUrl` (`''`). GET downloads use the session cookie and need no XSRF. No issues found.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | The folder tree and the usage can show stale data when operations overlap**
    - File: `web/src/app/features/files/browser/folder-tree.ts:123`, `web/src/app/features/files/browser/folder-tree.ts:149-166`; `web/src/app/features/files/browser/files-page.ts:259-265`
    - Category: Correctness
    - Problem: `refresh()` force-loads every known folder with no request identity. `load(id, true)` writes `children[id]` from whichever response arrives last. Two quick operations each call `changed()`, for example deleting two entries one after the other (only the deleted entry is busy, so the second delete is possible right away). If the first refresh of a folder answers after the second, the tree shows the older list again, including a folder that was already deleted. `loading.delete(id)` in `finally` also clears the in-flight mark that the other request set. The same applies to `loadUsage()`, which has no sequence guard. `FilesPage.load()` does guard this case with `loadSeq`, so the listing and the tree can then disagree.
    - Why it matters: after a quick series of operations the tree, and to a lesser degree the storage numbers, are wrong until the next refresh. Clicking a stale tree link to a deleted folder shows a load error.
    - Suggested fix: keep a per-folder request counter (or a single tree-wide generation) in `FolderTree.load`, and apply a result only if it is still the latest for that id. Do the same for `loadUsage` with a counter like `loadSeq`. Add a vitest that answers two refreshes out of order.

- [ ] **CR-2 | Closing a dialog while its save runs leaves the page out of date**
    - File: `web/src/app/features/files/browser/name-dialog.ts:75` (Cancel with `mat-dialog-close`, Escape and backdrop stay active while `saving()`); `web/src/app/features/files/browser/move-dialog.ts:67` (the same during `moving()`); `web/src/app/features/files/browser/files-page.ts:171`, `:182`, `:193`
    - Category: Correctness
    - Problem: neither dialog sets `disableClose`, and Cancel is not disabled while the request runs. If the user cancels or presses Escape after submitting, the dialog closes with `undefined`/`false`. The page then skips `changed()`, even though the server may already have committed the create, rename or move. The listing and tree keep the old name or place and the old `version`. The next rename, move or delete of that entry then fails with 412 ("could not be changed"), and only navigating away fixes it.
    - Why it matters: this is the "cancelled write may already have been committed" case. It leaves the screen stale and makes the next action fail.
    - Suggested fix: block closing while saving (`ref.disableClose = true` during the request, plus `[disabled]="saving()"`/`moving()` on Cancel). Alternatively, have the page call `changed()` after every close of a dialog that had started a save.

- [ ] **CR-3 | Pagination, tree failures and navigation races have no tests**
    - File: `web/src/app/features/files/browser/files-page.ts:130-150` (`loadMore`, "Show more", `moreError`); `web/src/app/features/files/browser/drive.service.ts:79-88` (`subfolders` paging and its stop rule); `web/src/app/features/files/browser/folder-tree.ts:83-89,156-163` (failed branch); `web/src/app/features/files/browser/files-page.ts:121,138` (sequence guards); `web/src/app/features/files/browser/files-page.ts:210` (404 on delete)
    - Category: Tests
    - Problem: `files-page.spec.ts` never returns a `nextCursor`, so loading more entries, removing duplicates, the "Show more" error and multi-page `subfolders` (the move dialog and tree for folders with more than 100 entries) never run. The guards against out-of-order results on fast folder changes, the tree's "could not be loaded" state and the delete 404 path are also untested. Criterion #2 asks for operations and error states to be covered.
    - Why it matters: these are the paths most likely to regress silently. The `subfolders` stop rule depends on the server ordering folders first.
    - Suggested fix: extend `FakeDrive` with a page size and cursor. Add tests for "Show more" (success and failure), `subfolders` across pages, two `params.next` calls answered out of order, a failed tree branch, and DELETE returning 404. (A concurrent test-writer run may already add some of these. Check its result before duplicating.)

- [ ] **CR-4 | Download and zip download have no runtime evidence**
    - File: `web/src/app/features/files/browser/drive.service.ts:163-166`; `web/e2e/files-browser/browser.spec.ts:9-71`
    - Category: Tests
    - Problem: criterion #1 lists "download" and "download folder as zip". The only check is a vitest assertion on the `href` value. The e2e spec does not start a download, so nobody has checked that the plain links, sent with the session cookie through the e2e stack's proxy, return the file or zip as an attachment and not a 401 or an SPA fallback.
    - Why it matters: under AGENTS.md "Evidence", a criterion about running behavior needs a run in the named configuration. An `href` string is not evidence that the download works.
    - Suggested fix: when recording evidence for #1, include a run against the e2e stack. For example, add a Playwright step that clicks "Download as zip", waits for the `download` event, and checks `suggestedFilename()` and a non-empty file. Otherwise mark the download part as skipped with a reason.

## P3 Low

- [ ] **CR-5 | The page has no heading while loading or after a load error, and `aria-labelledby` points to a missing id**
    - File: `web/src/app/features/files/browser/files-page.html:36`, `:43`, `:102`
    - Category: Quality
    - Problem: the `h1#files-title` is rendered only as the last crumb. While loading, and after a load error (crumbs never set, for example a deleted or invalid folder id), the page has no `h1`, and `section[aria-labelledby="files-title"]` refers to an element that does not exist.
    - Why it matters: screen-reader users get no page heading or region name in the error state, which is exactly when they need orientation.
    - Suggested fix: always render an `h1`, falling back to the route title "Files" (or "My drive") until the crumbs load. For example, render the heading outside the crumbs loop and keep the crumbs as links only.

## Review conclusion

Material issues found; fix before completing the task.

None of the findings blocks a criterion on its own. The parent worker should fix or explicitly disposition CR-1 to CR-3 and provide the runtime download evidence asked for in CR-4 before running `done`.
