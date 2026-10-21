# Test Report

Date: 2026-10-21

Task: web-files-browser - Files: browser and operations

Scope: base 79a8ec193e9fb82a82832b25d529dad71811ac07 .. d5d8ff48 (task commits e4d3f8a7, 27747043, d5d8ff48, given by the parent worker). Measured at HEAD a13d8377 (adds only `reviews/web-files-browser/review.md` on top of d5d8ff48, so the production code is the same), plus the uncommitted test changes listed below. Branch: main.

Requirements:
- `tasks/web-files-browser.md` acceptance criteria and notes (assumptions: delete goes to the Trash with no confirmation, special folders hide rename/move/delete, breadcrumbs walk up via `parentId`, one UrlMatcher for `/files` and `/files/:folderId`)
- SPEC.md §8.2 (drive UI) and §4.2 (If-Match optimistic locking, Problem Details), from the task goal
- docs/ARCHITECTURE.md "Coverage"; AGENTS.md project notes
- The parent worker's list of gaps to cover

## Test files changed

- `web/src/app/features/files/browser/drive.service.spec.ts` (new)
- `web/src/app/features/files/browser/folder-tree.spec.ts` (new)
- `web/src/app/features/files/browser/name-dialog.spec.ts` (new)
- `web/src/app/features/files/files.routes.spec.ts` (new)
- `web/src/app/features/files/browser/files-page.spec.ts` (12 tests added. The `FakeDrive` helper gained optional paging (`pageSize`, offset cursor) and an `answer(http, only)` filter so tests can choose the order of responses)
- `web/src/app/app.spec.ts` (`/files` added to the `BUILT` list, see "Pre-existing test made stale by the task" below)

## Tests added or updated

Count: 50 added, 1 updated

- drive.service.spec.ts (15): `toEntry` mapping; `list` root/folder params (`limit`, `cursor`, no `folderId` for root, SILENT_ERRORS); `subfolders` follows `nextCursor` while pages hold only folders and stops at the first page with a file; `subfolders` rejects with an ApiError and shows no toast; `crumbs` for the root makes no request; `crumbs` walk-up order; `crumbs` cycle guard; `createFolder` with parent and with `parentId: null`; rename folder/file with `If-Match` `"2"`/`"7"`; move folder (`parentId`) and file (`folderId: null`); delete folder/file with If-Match, and a 412 rejects without a toast; `usage`; `downloadUrl` for a file, a folder zip and an id that needs URL encoding.
- folder-tree.spec.ts (8): root shown collapsed with root current; lazy load on first expand, no reload after collapse/expand; loading note; path expanded and current marked; branch load failure note, and a retry on the next expand; root listing failure; `refresh()` reloads every loaded folder; no duplicate load while one is running.
- name-dialog.spec.ts (11): `nameError` (valid names, blank, `/`, `\`, `.`, `..`); maxlength; saves the trimmed name and closes with true; an unchanged name (after trimming) closes with false and does not save; the first `ApiError.errors[].message` is shown and the dialog stays open; problem detail without field errors, and a generic text for non-API errors; retry after an error; submit disabled while saving, and a second submit is ignored; an invalid name is not saved.
- files.routes.spec.ts (4): `driveMatcher` for root, one segment (posParam `folderId`) and deeper paths; `/files` and `/files/:id` resolve to the same `FilesPage` instance (the page and tree are kept).
- files-page.spec.ts (12): "Show more" paging until the last page; "Show more" failure keeps loaded entries, shows the error and can be retried; delete 404 reloads with no error and no toast; actions disabled and entry marked busy while delete runs; newest folder wins when an older load answers last; an older load's failure is ignored after a newer load succeeded; a stale "Show more" page after navigation is dropped; **cancelled move dialog changes nothing (fails, TW-1)**; move picker load error; picker shows the newest folder when an older picker load answers last; storage unavailable falls back to list view; a stored grid view is restored and switching back to List is saved.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 folder tree (lazy load, expand path, refresh, failures) | folder-tree.spec.ts (8); files-page `lists the root folders first...`, `deletes to the Trash and refreshes the list, tree and usage` | `npx ng test --watch=false --include src/app/features/files/browser/folder-tree.spec.ts` | Pass |
| AC1 list/grid | `switches between list and grid...`, `opens in the grid view when it was chosen before`, `falls back to the list view when the storage is unavailable` | `npx ng test --watch=false --include src/app/features/files/browser/files-page.spec.ts` | Pass |
| AC1 breadcrumbs | drive.service `walks up the parents for the crumbs, root first`, `stops walking up at a parent cycle...`, `gives only the root crumb...`; files-page `shows a folder with breadcrumbs...` | `npx ng test --watch=false --include src/app/features/files/browser/drive.service.spec.ts` | Pass |
| AC1 new folder | drive.service `creates a folder in a parent, or in the root...`; files-page `creates a folder in the shown folder` | as above | Pass |
| AC1 rename (If-Match, name dialog errors) | drive.service `renames a folder and a file...`; name-dialog.spec.ts (11); files-page `renames a file with its version as If-Match` | `npx ng test --watch=false --include src/app/features/files/browser/name-dialog.spec.ts` | Pass |
| AC1 move (dialog) | drive.service `moves a folder by parentId and a file by folderId`; files-page `moves a folder through the picker...`, `moves a file to the drive root`, `shows a failed move in the picker`, `shows in the move dialog when its folders cannot be loaded`, `shows the newest picker folder...` | files-page spec command | Pass |
| AC1 move dialog cancel changes nothing | files-page `does not change anything when the move dialog is cancelled` | files-page spec command | **Fail (TW-1)** |
| AC1 delete (Trash, 404, busy, error) | drive.service `deletes a folder and a file with If-Match...`; files-page `deletes to the Trash...`, `shows a failed delete next to the list`, `reloads without an error when the deleted entry was already gone (404)`, `disables the actions of an entry while its delete runs` | files-page spec command | Pass |
| AC1 download, download folder as zip | drive.service `builds download URLs...`; files-page `shows a folder with breadcrumbs, its tree branch expanded and download links`, `offers only the download for special folders` | as above | Pass |
| AC1 disk usage | drive.service `loads the usage`; files-page `lists the root folders first...`, `hides the usage when it cannot be loaded` | as above | Pass |
| AC1 paging of listings | drive.service `follows the cursor...`, `stops paging subfolders...`; files-page `loads more entries page by page...`, `keeps the loaded entries and shows the error when "Show more" fails...`, `drops a "Show more" page...` | as above | Pass |
| AC1 route `/files` and `/files/:folderId` keep one page | files.routes.spec.ts (4) | `npx ng test --watch=false --include src/app/features/files/files.routes.spec.ts` | Pass |
| AC2 vitest covers operations and error states; `npm run check` passes | all of the above | `npm run check` | **Fail**: 1 failing test (TW-1). Lint, api:check and e2e typecheck passed. `ng build` did not run because `test:ci` failed first |
| AC3 Playwright create folder, rename, move, delete | `e2e/files-browser/browser.spec.ts` (existing, not changed) | `npm run e2e -- e2e/files-browser/browser.spec.ts` (docker e2e stack, chromium) | Pass: 6 passed (includes the phase0 demo project), stack torn down |

## Focused test execution

Command:

`npx ng test --watch=false --include <each new/changed spec>` (drive.service 15/15, folder-tree + name-dialog + files.routes 23/23, files-page 27/28)

Result:

Fail: 1 test fails (TW-1). All other new tests pass.

## Full test execution

Command:

`npm run check` (in `web/`)

Result:

Fail: 78 of 79 test files pass; 1424 passed, 1 failed (TW-1). `npm run lint` (all files pass) and Prettier pass on the test files.

## .taskcheck

Result:

Not run. Its web step is `npm run -s check`, which fails deterministically on TW-1 (see above). The parent should run `.taskcheck` after the fix.

## Bugs found

### Cancelling the move dialog shows a "Moved" toast and reloads

Finding ID:

`TW-1`

Test:

`FilesPage > does not change anything when the move dialog is cancelled` (`web/src/app/features/files/browser/files-page.spec.ts`)

Production source:

`web/src/app/features/files/browser/files-page.ts:193` (`if (target === undefined) return;`) together with `web/src/app/features/files/browser/move-dialog.ts:67` (`<button mat-button type="button" mat-dialog-close ...>Cancel</button>`)

Requirement:

AC1 "move (dialog)". MoveDialog's documented contract: "Closes with the target folder id once moved, without a result on cancel."

Expected behavior:

Cancel closes the dialog with no result. The page does nothing: no toast, no reload.

Actual behavior:

A bare `mat-dialog-close` attribute sets the dialog result to `''` (empty string), not `undefined`. Cancel therefore closes with `''`, `target === undefined` is false, and the page shows the toast `Moved “Documents”` and reloads the listing, tree and usage, although no PATCH was sent. Escape and a backdrop click close with `undefined` and are not affected. The name dialog's Cancel is also not affected, because there `!!''` is false.

Severity:

Medium. The user gets a false confirmation that something moved. No data changes.

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.` (Two possible fixes: `[mat-dialog-close]="undefined"` on the Cancel button, or checking for a string/null target in `move()`.)

### Pre-existing test made stale by the task (not a production defect)

`src/app/app.spec.ts > App > lazy-loads a coming soon page for /files` failed: the task replaced the `/files` placeholder with the browser page. I added `/files` to the spec's `BUILT` list, as earlier feature tasks did for their routes (e.g. commit 2d807355 for `/projects`). The page is now covered by `files.routes.spec.ts`. Without this change `npm run check` fails at d5d8ff48 regardless of TW-1. The parent should check whether its own earlier `npm run check` result predates commit e4d3f8a7.

## Coverage

Status: partial-failed. The full run fails on TW-1, and Vitest writes no coverage report when a test fails. The numbers below come from a full-suite run with only the TW-1 test deselected through `--filter`. That test only adds hits to `files-page.ts` `move()`, which other move tests already cover. Treat these as diagnostic until a fresh run after the fix.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured. All scoped files are new in this task except `files.routes.ts`, so whole-file coverage is close to changed-line coverage for them.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-files-browser; base 79a8ec193e9fb82a82832b25d529dad71811ac07; HEAD a13d8377f0489f24f19be56be88bcc5c775d6c00 (= d5d8ff48 + review.md only); task commits e4d3f8a7, 27747043, d5d8ff48 and the file scope were given by the parent and checked with `git diff --stat 79a8ec19..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): sha256 of the production files was identical before and after the run (drive.service.ts b7f7cb95…, files-page.ts be464121…, files-page.html fbb40678…, folder-tree.ts e4e28763…, move-dialog.ts 0e7cf416…, name-dialog.ts 12708938…, files.routes.ts c451c0a8…). Test files: app.spec.ts d3b1f880…, files.routes.spec.ts 3e44bc88…, drive.service.spec.ts 46e814b4…, files-page.spec.ts 71f17605…, folder-tree.spec.ts dbd55c65…, name-dialog.spec.ts 02c643e8….
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/features/files/browser/{drive.service.ts, files-page.ts, files-page.html, folder-tree.ts, name-dialog.ts, move-dialog.ts}` and `web/src/app/features/files/files.routes.ts`. Excluded: `files-page.scss` and `folder-tree.scss` (styles, nothing executable); `*.spec.ts` (tests); `web/e2e/files-browser/browser.spec.ts` (e2e test). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with @vitest/coverage-v8 5.0.5 (via `ng test`); `npm run coverage -- --filter '^(?!.*when the move dialog is cancelled)'`; cwd `web/`; full suite minus the TW-1 test.
- Run start/completion time and exit status: 2026-10-21T02:25:18Z to 02:25:34Z; exit 0; 79 files, 1424 passed, 1 skipped (deselected).
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 60c42bdd…7c83b; `web/coverage/web/coverage-summary.json` sha256 47db6312…cabfab4 (both rewritten 04:25 local time by this run).
- Evidence outputs are fresh and inputs stayed unchanged: yes. Vitest rewrote `coverage/`, and the before/after hash diff of the scoped sources and specs was empty.
- Final run after latest test additions and production fixes: yes for the latest test additions. There is no production fix yet: TW-1 is open, and coverage must be measured again after the fix.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/files/browser/drive.service.ts` | 55/55 (100%) | 35/35 (100%) | 16/16 (100%) | 47/47 (100%) |
| `web/src/app/features/files/browser/files-page.ts` | 130/135 (96.29%) | 48/58 (82.75%) | 31/33 (93.93%) | 113/113 (100%) |
| `web/src/app/features/files/browser/files-page.html` | 177/177 (100%) | 25/26 (96.15%) | 8/8 (100%) | 114/114 (100%) |
| `web/src/app/features/files/browser/folder-tree.ts` | 103/103 (100%) | 32/33 (96.96%) | 17/17 (100%) | 76/76 (100%) |
| `web/src/app/features/files/browser/name-dialog.ts` | 70/70 (100%) | 31/32 (96.87%) | 5/5 (100%) | 45/45 (100%) |
| `web/src/app/features/files/browser/move-dialog.ts` | 100/101 (99.00%) | 24/28 (85.71%) | 8/8 (100%) | 65/65 (100%) |
| `web/src/app/features/files/files.routes.ts` | 8/8 (100%) | 4/4 (100%) | 2/2 (100%) | 6/6 (100%) |
| **Aggregate (summed counters, 7 files; scss excluded, none missing)** | 643/649 (99.08%) | 199/216 (92.13%) | 87/89 (97.75%) | 466/466 (100%) |

For comparison, the first coverage run (before the 3 last added tests: stale "Show more", stale picker, List click) gave an aggregate of 640/649 statements and 197/216 branches.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `files-page.ts:84` (`title` computed, function never called) | none: `title` is not used by the template or the code | Gap left: unused code. The reviewer/fixer may remove it |
| `files-page.ts:88` (usage with `totalSpaceBytes` 0, meter 0%) | defensive guard against division by zero | Gap left: low risk, the server reports the disk size |
| `files-page.ts:132` (loadMore while already loading) | double "Show more" | Gap left: the button is disabled while loading (template), so the guard is defensive |
| `files-page.ts:139,141` (`entries() ?? []` null branches) | defensive null coalescing | Gap left: entries are never null when "Show more" is visible |
| `files-page.ts:146[1]` (stale loadMore failure) | an older "Show more" failure after navigation | Gap left: the stale success path is tested (`drops a "Show more" page...`); the failure branch is symmetric |
| `files-page.ts:182[1]` (rename not saved) | rename cancelled | Gap left: same `openName` path as new folder; covered by name-dialog tests |
| `files-page.ts:201` (remove while busy) | double delete | Gap left: the actions button is disabled while busy (tested by `disables the actions of an entry while its delete runs`) |
| `files-page.ts:277[1]`, `move-dialog.ts:196[1]` (non-ApiError message) | generic error text | Gap left: HTTP errors are always ApiError; the same branch is tested in name-dialog |
| `files-page.ts:62` (`viewChild` arrow) | generated query glue | Not material |
| `files-page.html:26[1]` (no trash bytes) | the Trash line is hidden when empty | Gap left: cosmetic |
| `folder-tree.ts:94[0]`, `move-dialog.ts:141[0]`, `name-dialog.ts:91[0]` | decorator/class compile branches | Not material |
| `move-dialog.ts:175[1]` (stale picker failure) | an older picker failure after a newer open | Gap left: the stale success path is tested (`shows the newest picker folder...`) |
| `move-dialog.ts:180` (move while moving) | double move | Gap left: "Move here" is disabled while moving |

## Unable to test

- `ng build` inside `npm run check` was not reached because `test:ci` fails on TW-1 first. Production code is unchanged by this run, so the parent should confirm the build after the fix.
- `.taskcheck` was not run (see above).
- Real file downloads (zip streaming and the browser saving the file) were not run. Unit tests check the URLs only; AC3 does not name downloads in the Playwright scope. No follow-up is needed for this task.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion.
