# Test Report

Date: 2026-10-21

Task: web-files-browser - Files: browser and operations

Scope: second run, on the final code after the fixes. Base 2c1825c89ea94926fe6b46ba21166e5030844bd2, HEAD a224024851b5a22a2a0569beff01cc28fccd6136 (given by the parent worker). Task commits: the web-files-browser commits in `git log 2c1825c8..HEAD` (900578df, dd2934c0, d346dbee, 95e9cadd, 60443304, 9fd5366b, b03516a0, a2240248). Measured on HEAD plus the uncommitted test change in `files-page.spec.ts` listed below. Branch: main.

Requirements:
- `tasks/web-files-browser.md` acceptance criteria and notes (delete goes to the Trash without confirmation, special folders hide rename/move/delete, breadcrumbs walk up via `parentId`, one UrlMatcher for `/files` and `/files/:folderId`)
- SPEC.md §8.2 (drive UI) and §4.2 (If-Match, Problem Details), from the task goal
- `reviews/web-files-browser/review.md`, `fixes.md` and the earlier `tests.md` (TW-1, CR-1..CR-5)
- docs/ARCHITECTURE.md "Coverage"; AGENTS.md project notes

## Test files changed

- `web/src/app/features/files/browser/files-page.spec.ts` (3 tests added in this run)

Test files from earlier runs on this task, re-checked and unchanged in this run: `drive.service.spec.ts`, `folder-tree.spec.ts`, `name-dialog.spec.ts`, `files-page.spec.ts`, `web/src/app/features/files/files.routes.spec.ts`, `web/src/app/app.spec.ts` (`/files` in `BUILT`), `web/e2e/files-browser/browser.spec.ts`.

## Tests added or updated

Count: 3 added in this run (task total in vitest: 76 tests in the 5 files-feature specs, plus the `app.spec.ts` update)

- `FilesPage > does not change anything when the rename dialog is cancelled`: Cancel in the rename dialog sends no PATCH, shows no toast and reloads nothing (the same check as the TW-1 regression test for the move dialog; the name dialog's Cancel also closes with `''`).
- `FilesPage > ignores a "Show more" failure that answers after navigating to another folder`: the failure of an older "Show more" request is dropped after navigation (before, only the stale success was tested).
- `FilesPage > ignores a picker load failure that answers after a newer picker load`: in the move dialog, a failed load of a folder the user already left shows no error (before, only the stale success was tested).

Re-check of the earlier findings against the current tests:
- TW-1 (move Cancel): `does not change anything when the move dialog is cancelled` passes and asserts no PATCH, no toast and no new request.
- CR-1: `FolderTree > with refreshes answered out of order` (2 tests), `FilesPage > shows the newest usage when an older usage load answers last`.
- CR-2: `NameDialog > cannot be closed while saving, and can again after a failed save`, `FilesPage > keeps the move dialog open until a running move has answered`.
- CR-3: paging, out-of-order loads, failed tree branch and DELETE 404 tests exist (see the table).
- CR-4: download and zip download in the Playwright spec (run by the parent).
- CR-5: `FilesPage > shows a load error with a retry` checks the "Files" heading and one `#files-title`.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 folder tree (lazy load, expand path, refresh, failures, out-of-order refresh) | folder-tree.spec.ts (10); files-page `lists the root folders first...`, `deletes to the Trash and refreshes the list, tree and usage` | `npx ng test --watch=false --include 'src/app/features/files/**/*.spec.ts'` | Pass |
| AC1 list/grid | files-page `switches between list and grid...`, `opens in the grid view when it was chosen before`, `falls back to the list view when the storage is unavailable` | same | Pass |
| AC1 breadcrumbs | drive.service `walks up the parents...`, `stops walking up at a parent cycle...`, `gives only the root crumb...`; files-page `shows a folder with breadcrumbs...`, `shows a load error with a retry` | same | Pass |
| AC1 new folder | drive.service `creates a folder in a parent, or in the root...`; files-page `creates a folder in the shown folder`, `keeps the name dialog open with the server error...`, `rejects names the server would refuse...` | same | Pass |
| AC1 rename (If-Match, dialog errors, cancel) | drive.service `renames a folder and a file...`; name-dialog.spec.ts (12); files-page `renames a file with its version as If-Match`, `does not change anything when the rename dialog is cancelled` (new) | same | Pass |
| AC1 move (dialog, cancel, paging, failures, races) | drive.service `moves a folder by parentId and a file by folderId`, subfolders paging tests; files-page `moves a folder through the picker...`, `moves a file to the drive root`, `shows a failed move in the picker`, `does not change anything when the move dialog is cancelled`, `keeps the move dialog open until a running move has answered`, `lists every subfolder in the move picker...`, `shows in the move dialog when its folders cannot be loaded`, `shows the newest picker folder...`, `ignores a picker load failure that answers after a newer picker load` (new) | same | Pass |
| AC1 delete (Trash, 404, busy, error) | drive.service `deletes a folder and a file with If-Match...`; files-page `deletes to the Trash...`, `shows a failed delete next to the list`, `reloads without an error when the deleted entry was already gone (404)`, `disables the actions of an entry while its delete runs` | same | Pass |
| AC1 download, download folder as zip | drive.service `builds download URLs...`; files-page `shows a folder with breadcrumbs, ... download links`, `offers only the download for special folders`; Playwright download + zip test | vitest command above; `npm run e2e -- e2e/files-browser/browser.spec.ts` (run by the parent worker on the docker e2e stack, not rerun here) | Pass (vitest); Pass per parent: 6 passed |
| AC1 disk usage | drive.service `loads the usage`; files-page `lists the root folders first...`, `hides the usage when it cannot be loaded`, `shows the newest usage when an older usage load answers last` | vitest command above | Pass |
| AC1 listing paging ("Show more") | files-page `loads more entries page by page...`, `keeps the loaded entries and shows the error when "Show more" fails...`, `drops entries a "Show more" page repeats...`, `drops a "Show more" page that answers after navigating...`, `ignores a "Show more" failure that answers after navigating...` (new) | same | Pass |
| AC1 route `/files` and `/files/:folderId` keep one page | files.routes.spec.ts (4) | same | Pass |
| AC2 vitest covers operations and error states; `npm run check` passes | all of the above | `cd web && npm run check` | Pass: api:check, lint ("All files pass linting."), e2e typecheck, 79 files / 1435 tests passed, `ng build` OK (existing initial-bundle budget warning 647.52 kB of 500 kB; the files browser is lazy-loaded) |
| AC3 Playwright create folder, rename, move, delete | `web/e2e/files-browser/browser.spec.ts` | `cd web && npm run e2e -- e2e/files-browser/browser.spec.ts` | Not run by the test-writer (parent instruction). The parent reports 6 passed against the e2e stack; the spec type-checks inside `npm run check` |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include 'src/app/features/files/**/*.spec.ts'` (before the additions: 5 files, 73 tests passed) and `npx ng test --watch=false --include src/app/features/files/browser/files-page.spec.ts` (after: 35 tests passed); `npx prettier --check src/app/features/files/browser/files-page.spec.ts` passed.

Result:

Pass

## Full test execution

Command:

`cd web && npm run check`

Result:

Pass (exit 0; 79 test files, 1435 tests passed)

## .taskcheck

Result:

Pass: `sh scripts/check.sh` -> "== all checks passed" (Android skipped because COCKPIT_NO_ANDROID=1 is set in this environment).

## Bugs found

None in this revision. TW-1 from the first run is fixed: its regression test passes.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured. All scoped files are new in this task except `files.routes.ts` (100% in every metric), so whole-file coverage is close to changed-line coverage.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-files-browser; base 2c1825c89ea94926fe6b46ba21166e5030844bd2; HEAD a224024851b5a22a2a0569beff01cc28fccd6136; task commits and file scope from the parent, checked with `git log --oneline 2c1825c8..HEAD` (only web-files-browser commits) and `git diff --stat 2c1825c8..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): sha256 of the production files, identical before and after the run: drive.service.ts b7f7cb95…6d74, files-page.ts 5e0a5664…fcc1, files-page.html 2f00c324…c2fc, folder-tree.ts 47be5dc5…a530, move-dialog.ts 3b43fa57…6533, name-dialog.ts 5cb81bbc…fda4, files.routes.ts c451c0a8…2372. Tests: files-page.spec.ts 6b9117ec…1e36 (uncommitted, with the 3 new tests), drive.service.spec.ts 46e814b4…4914, folder-tree.spec.ts 77da645d…aa8d, name-dialog.spec.ts 578d79d0…572c, files.routes.spec.ts 3e44bc88…5e52, app.spec.ts d3b1f880…abe4.
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/features/files/browser/{drive.service.ts, files-page.ts, files-page.html, folder-tree.ts, name-dialog.ts, move-dialog.ts}` and `web/src/app/features/files/files.routes.ts`. Excluded: `files-page.scss`, `folder-tree.scss` (styles, nothing executable), `*.spec.ts` and `web/e2e/files-browser/browser.spec.ts` (tests), `reviews/` (docs). No renamed or deleted files; none missing from the report.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with @vitest/coverage-v8 (via `ng test`); `npm run coverage`; cwd `web/`; full suite.
- Run start/completion time and exit status: 2026-10-21T02:47:45Z to 02:47:57Z; exit 0; 79 files, 1435 tests passed.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 b269c74c…f289; `web/coverage/web/coverage-summary.json` sha256 fc871c19…8d63 (both rewritten 04:47 local time by this run; the earlier run in this session produced different hashes 770072bc…/cf244a2f…).
- Evidence outputs are fresh and inputs stayed unchanged: yes. Vitest rewrote `coverage/`; the source hashes after the run match those before.
- Final run after latest test additions and production fixes: yes (production code at HEAD a2240248 includes all fixes; the run includes the 3 new tests).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/files/browser/drive.service.ts` | 55/55 (100%) | 35/35 (100%) | 16/16 (100%) | 47/47 (100%) |
| `web/src/app/features/files/browser/files-page.ts` | 133/137 (97.08%) | 52/58 (89.65%) | 31/32 (96.87%) | 115/115 (100%) |
| `web/src/app/features/files/browser/files-page.html` | 183/183 (100%) | 28/28 (100%) | 8/8 (100%) | 116/116 (100%) |
| `web/src/app/features/files/browser/folder-tree.ts` | 110/110 (100%) | 38/39 (97.43%) | 17/17 (100%) | 80/80 (100%) |
| `web/src/app/features/files/browser/name-dialog.ts` | 73/73 (100%) | 31/32 (96.87%) | 5/5 (100%) | 50/50 (100%) |
| `web/src/app/features/files/browser/move-dialog.ts` | 104/105 (99.04%) | 25/28 (89.28%) | 8/8 (100%) | 70/70 (100%) |
| `web/src/app/features/files/files.routes.ts` | 8/8 (100%) | 4/4 (100%) | 2/2 (100%) | 6/6 (100%) |
| **Aggregate (summed counters, 7 files; scss excluded, none missing)** | 666/671 (99.25%) | 213/224 (95.09%) | 87/88 (98.86%) | 484/484 (100%) |

Before the 3 new tests (same production code, run at 02:46Z): files-page.ts branches 50/58, move-dialog.ts branches 24/28, aggregate branches 210/224. The new tests cover `files-page.ts:147` (stale "Show more" failure), `files-page.ts:183` (rename not saved) and `move-dialog.ts:183` (stale picker failure).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `files-page.ts:87` (`totalSpaceBytes` 0 -> meter 0%) | guard against division by zero | Gap left: defensive; the server always reports the disk size |
| `files-page.ts:133` (loadMore without cursor or while loading) | double "Show more" | Gap left: the button only shows with a cursor and is disabled while loading |
| `files-page.ts:140,142` (`entries() ?? []` null branches) | defensive null coalescing | Gap left: entries are never null while "Show more" is visible |
| `files-page.ts:202` (remove while busy) | double delete | Gap left: the actions button is disabled while busy (tested by `disables the actions of an entry while its delete runs`) |
| `files-page.ts:280[1]`, `move-dialog.ts:207[1]` (non-ApiError message) | generic error text | Gap left: HTTP errors are always ApiError here; the same branch is tested in name-dialog |
| `files-page.ts:62` (`viewChild` arrow) | generated query glue | Not material |
| `move-dialog.ts:188` (move while moving) | double move | Gap left: "Move here" is disabled while moving (tested by `keeps the move dialog open until a running move has answered`) |
| `folder-tree.ts:94[0]`, `move-dialog.ts:149[0]`, `name-dialog.ts:99[0]` | decorator/class compile branches | Not material |

## Unable to test

- AC3 Playwright spec: not rerun by the test-writer, on the parent's instruction. The parent's run (6 passed, including the download and zip test) is the evidence.
- Android part of `.taskcheck` skipped by the environment (COCKPIT_NO_ANDROID=1); the task does not touch Android. No follow-up.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.
