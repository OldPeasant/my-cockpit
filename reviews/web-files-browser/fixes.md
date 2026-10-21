# Fix Report

Date: 2026-10-21

Task: web-files-browser - Files: browser and operations

Scope: current task. Findings CR-1..CR-5 from `reviews/web-files-browser/review.md` and TW-1 from `reviews/web-files-browser/tests.md`. Base b18d8200c9154fdb777423c4f469fe5b02c6f20a. Commits checked: the parent worker's first fix attempt 2279d182 and this fixer's commit c3969162, which adds tests only.

## Fixed findings

### TW-1: Cancelling the move dialog shows a "Moved" toast and reloads

Files changed:
- `web/src/app/features/files/browser/move-dialog.ts` (2279d182)
- `web/src/app/features/files/browser/files-page.spec.ts` (c3969162, test strengthened)

Problem:
The bare `mat-dialog-close` attribute closed the dialog with `''`. `FilesPage.move()` only skips on `undefined`, so a cancel showed "Moved ..." and reloaded the page.

Fix:
Cancel now uses `[mat-dialog-close]="undefined"`, so the dialog closes without a result, as the dialog's contract says (2279d182). The fixer checked this fix and kept it. The fixer also made the test stricter: after Cancel, it now asserts that no listing, tree or usage request is sent. A mutation check confirmed the test: restoring the bare `mat-dialog-close` makes `does not change anything when the move dialog is cancelled` fail, and with the fix it passes.

Validation:
- `npx ng test --watch=false --include src/app/features/files/browser/files-page.spec.ts`: Pass (and Fail with the fix reverted, as expected)

### CR-1: The folder tree and the usage can show stale data when operations overlap

Files changed:
- `web/src/app/features/files/browser/folder-tree.ts` (2279d182)
- `web/src/app/features/files/browser/files-page.ts` (2279d182)
- `web/src/app/features/files/browser/folder-tree.spec.ts`, `files-page.spec.ts` (2279d182)

Problem:
`FolderTree.load(id, true)` and `FilesPage.loadUsage()` applied whichever response arrived last. Also, `loading.delete(id)` cleared another request's in-flight mark.

Fix:
`FolderTree` keeps a `Map<id, seq>` of the latest load for each folder. A result or failure applies only if its sequence number is still the latest, and `finally` removes the mark only when it owns it. `FilesPage` has a `usageSeq` guard like `loadSeq`. The fixer checked both: an older answer can no longer overwrite a newer one, and a non-forced load still skips while a load is in flight. No further change was needed.

Tests: `FolderTree > with refreshes answered out of order > keeps the newer answer when the older one arrives last` and `> ignores a failure of the older load after the newer one succeeded`. `FilesPage > shows the newest usage when an older usage load answers last`.

Validation:
- `npx ng test --watch=false --include 'src/app/features/files/**/*.spec.ts'`: Pass (73 tests)

### CR-2: Closing a dialog while its save runs leaves the page out of date

Files changed:
- `web/src/app/features/files/browser/name-dialog.ts`, `move-dialog.ts` (2279d182)
- `web/src/app/features/files/browser/name-dialog.spec.ts`, `files-page.spec.ts` (2279d182)

Problem:
Cancel, Escape and a backdrop click could close a dialog while its create, rename or move request was still running. The page then skipped its refresh, even though the server may already have saved the change.

Fix:
While a request runs, both dialogs set `ref.disableClose = true` and disable Cancel with `[disabled]="saving()"` or `moving()`. A `finally` block turns closing back on, so a failed save can still be cancelled. The fixer checked this. `disableClose` blocks Escape and the backdrop, and the disabled button blocks Cancel. When the request succeeds, `ref.close(result)` runs before `finally`, so the dialog still closes. No further change was needed.

Tests: `NameDialog > cannot be closed while saving, and can again after a failed save`. `FilesPage > keeps the move dialog open until a running move has answered`.

Validation:
- `npx ng test --watch=false --include 'src/app/features/files/**/*.spec.ts'`: Pass

### CR-3: Pagination, tree failures and navigation races have no tests

Files changed:
- `web/src/app/features/files/browser/files-page.spec.ts` (c3969162)
- Earlier test-writer and parent commits: `drive.service.spec.ts`, `folder-tree.spec.ts`, `files-page.spec.ts`

Problem:
The review found no tests for these paths: "Show more" success and failure, `subfolders` paging, guards against out-of-order results, a failed tree branch and a DELETE that returns 404.

Fix:
The test-writer had already added most of these tests (`FakeDrive.pageSize` with an offset cursor, and `answer(http, only)` to choose the order of responses). The fixer checked each item and added tests for the gaps that were left:
- "Show more" success: `loads more entries page by page and hides "Show more" after the last page`. New: `drops entries a "Show more" page repeats after the listing shifted` (de-duplication).
- "Show more" failure: `keeps the loaded entries and shows the error when "Show more" fails, then retries`. Also `drops a "Show more" page that answers after navigating to another folder`.
- `DriveService.subfolders` across several pages: `follows the cursor for subfolders while pages hold only folders`, `stops paging subfolders at the first page that contains a file`, `rejects subfolders with the ApiError of a failed page` (drive.service.spec.ts). New at the component level: `lists every subfolder in the move picker when they span several pages`.
- Folder loads that answer out of order: `shows the newest folder when an older folder load answers last`, `ignores a failure of an older folder load after a newer one succeeded`, `shows the newest picker folder when an older picker load answers last`, and the tree and usage tests listed under CR-1.
- Failed tree branch: `FolderTree > shows a note when a branch cannot be loaded and loads it again on the next expand`, `shows the failure note for the root when the drive cannot be listed`.
- DELETE returning 404: `reloads without an error when the deleted entry was already gone (404)`.

Validation:
- `npx ng test --watch=false --include 'src/app/features/files/**/*.spec.ts'`: Pass (5 files, 73 tests)

### CR-4: Download and zip download have no runtime evidence

Files changed:
- `web/e2e/files-browser/browser.spec.ts` (2279d182)

Problem:
Only the link `href` values were tested. Nothing showed that the downloads work through the e2e stack.

Fix:
The Playwright test (now `create folder, rename, move, delete, download`) uploads a fictitious `notes.txt` through the API. It then clicks the file's download link and checks the suggested file name and the file content. Next, it clicks the toolbar's "Download as zip", then checks the suggested name `E2E files <run>.zip`, that the file is not empty, and the `PK\x03\x04` zip signature. The parent worker ran `cd web && npm run e2e -- e2e/files-browser/browser.spec.ts` against the e2e stack and got 6 passed, including this test. As instructed, the fixer did not run it again. The spec type-checks as part of `npm run check` (`e2e:typecheck`).

Validation:
- `npm run e2e -- e2e/files-browser/browser.spec.ts` (run by the parent worker on the docker e2e stack, chromium): Pass, 6 passed
- `npm run e2e:typecheck` (inside `npm run check`): Pass

### CR-5: The page has no heading while loading or after a load error

Files changed:
- `web/src/app/features/files/browser/files-page.html`, `files-page.ts` (2279d182)
- `web/src/app/features/files/browser/files-page.spec.ts` (2279d182)

Problem:
The `h1#files-title` existed only as the last breadcrumb. While loading, and after a load error, the page had no heading, and `aria-labelledby="files-title"` pointed to an id that did not exist.

Fix:
When there are no crumbs, the template renders `<h1 id="files-title">Files</h1>`. Otherwise it renders the breadcrumbs, whose last item is the `h1`. So there is always exactly one `#files-title`. The unused `title` computed was removed. The fixer checked this. No further change was needed.

Test: `FilesPage > shows a load error with a retry` asserts the "Files" heading and the section label in the error state, and one `#files-title` ("Documents") after the retry.

Validation:
- `npx ng test --watch=false --include src/app/features/files/browser/files-page.spec.ts`: Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `npx ng test --watch=false --include 'src/app/features/files/**/*.spec.ts'` (in `web/`): Pass, 5 files, 73 tests
- `npx prettier --check src/app/features/files/browser/`: Pass

Full test suite:
- `npm run check` (in `web/`: api:check, `ng lint --max-warnings=0`, e2e typecheck, `ng test --watch=false`, `ng build`): Pass, 79 test files, 1432 tests, "All files pass linting."

Lint/type-check/build:
- Included in `npm run check`: Pass. `ng build` prints the existing initial-bundle budget warning (647.52 kB of 500 kB). The files browser is lazy-loaded and does not add to the initial bundle.

`.taskcheck`:
- `sh scripts/check.sh`: Pass ("all checks passed". The script skipped Android because COCKPIT_NO_ANDROID=1 is set in this environment.)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
