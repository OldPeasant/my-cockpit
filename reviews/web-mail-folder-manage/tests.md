# Test Report

Date: 2026-11-01

Task: web-mail-folder-manage - Mail folder tree: create, rename and delete folders

Scope: task base `f27bdf89f0f3ec14d4faa2077df0a7752dd9381f` to final code `0fa8fd22d40387b317b138b74d569833f7893058` (HEAD at measurement). Task commits touching `web/`: `3447cbb95` (implementation), `63e2ee051` (first test-writer round), `0fa8fd22d` (fixer: own message for a refused delete, reload on delete 409, delete 404 counts as success). `git diff f27bdf89f..0fa8fd22d -- web/`, plus the uncommitted test-file change of this second round listed below. This is the second round; it replaces the first-round report (which described `3447cbb95`).

Requirements:
- `tasks/web-mail-folder-manage.md` acceptance criteria and recorded assumptions (Delete hidden for folders holding a role folder; New subfolder hidden without delimiter; localized error texts by status; delete performed inside the dialog; local tree update plus background reload)
- `reviews/web-mail-folder-manage/fixes.md` (the behavior changed by the fixer)
- `AGENTS.md`, `docs/ARCHITECTURE.md` ("Coverage", web conventions)
- Parent instructions for this round

## Test files changed

Committed in the task (first round and fixer):
- `web/src/app/features/mail/list/mail.service.spec.ts`
- `web/src/app/features/mail/list/mail-folders-store.spec.ts`
- `web/src/app/features/mail/list/mail-folder-tree.spec.ts`
- `web/src/app/features/mail/list/mail-folder-dialogs.spec.ts`

Uncommitted, this round:
- `web/src/app/features/mail/list/mail-folder-tree.spec.ts`

## Tests added or updated

Count: this round 3 new test definitions (6 test cases) in `mail-folder-tree.spec.ts`; no existing test changed, weakened or removed. Earlier in the task: 39 tests by the first test-writer round, plus the fixer's tests (folderErrorMessage on delete, the delete dialog's 409 message, delete 409 reload, delete 404 as success).

This round (all in `mail-folder-tree.spec.ts`, describe "actions"):
- "hides Delete once the reload after a refused delete (409) shows the folder has a role": the delete is refused with 409, the background reload answers with the folder now carrying the ARCHIVE role; the tree then shows it as "Archive" and its menu offers only New subfolder and Rename. Verifies the purpose of the fixer's reload end to end, not just that a reload was started.
- "neither reloads nor removes the folder when the delete fails with {reason}" (4 cases: 502, 0, a non-API `Error`, a non-API `Error` carrying `status: 404`): the error is rethrown unchanged, no reload, the folder stays and no navigation. Guards that only an `ApiError` 404 is treated as success and that 409 is the only extra reload status.
- "does not reload when a rename is refused with 409 (the name is taken)": the extra 409 reload is specific to delete.

Fixer behavior already covered by the fixer's tests (reviewed, kept): `folderErrorMessage(error, 'delete')` returns the delete-refused text for 409 and the save texts for 0/400/412/502; the delete dialog shows the 409 text in an alert and stays open; delete 409/412 reload and keep the folder; delete 404 resolves, removes the folder locally and navigates to `/mail` when the shown folder was below it; delete 404 of another folder does not navigate.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: each account has "New folder"; each folder a menu with New subfolder / Rename / Delete; no Rename/Delete for INBOX; no Delete for role folders (and, per assumption, folders holding one); no New subfolder without delimiter | tree spec: "gives each account a labelled New folder button", "offers New subfolder, Rename and Delete for an ordinary folder", "offers only New subfolder for INBOX", "treats a folder named INBOX without a role as INBOX", "does not offer Delete for a folder with a role, nor for one holding it", "does not offer New subfolder in a flat namespace", "hides Delete once the reload after a refused delete (409) shows the folder has a role"; store spec `subtree` tests | `cd web && npx ng test --watch=false --include='src/app/features/mail/list/*.spec.ts' --include='src/locale/*.spec.ts'` | Pass |
| AC2: New folder / Rename open a name dialog; server errors (409 taken, 400 invalid, 0 unreachable, 502) shown in the dialog which stays open; the tree shows the change at once | dialogs spec: "shows a server error (%s) in the dialog, which stays open", "shows a generic text for an error that is not an API error, and stays open", "clears the previous server error when trying again", "saves the trimmed name and closes with true", validation tests; folderErrorMessage tests incl. "explains a 409 on delete as a folder that cannot be deleted, not a taken name"; tree spec: create/rename/subfolder tests, stale 404/412 reload tests, "does not reload when a rename is refused with 409"; store spec `added` / `renamed`; service spec create/rename | same | Pass |
| AC3: Delete asks for confirmation saying messages and subfolders are deleted on the mail server too; deleting the shown folder navigates to the unified Inbox | dialogs spec: "asks, naming the folder and saying its messages go on the mail server too", "mentions %s subfolder(s)", "deletes on confirm and closes with true", "says why the server refused the delete (409), and stays open", in-flight and retry tests; tree spec: navigation tests (ancestor, folder itself, other folder, unified Inbox shown, prefix sibling), "reloads the folders and keeps the folder when the server refuses the delete (412/409)", "treats a folder already gone on the server (404) as deleted, and leaves it if shown", "stays on the shown folder when another folder is already gone (404)", "neither reloads nor removes the folder when the delete fails with {reason}", "keeps the folder and the page when the delete fails"; store spec `removed`; service spec delete | same | Pass |
| AC4: keyboard usable (menus and dialogs focusable, Escape closes), labels localized | tree spec: "opens the menu from a focusable button and closes it with Escape"; dialogs spec: Escape, cdkFocusInitial and Cancel tests; `src/locale/messages-de.spec.ts` (German catalog checks, includes the new `mail.folder.error.deleteRefused` unit) | same | Pass (unit level). `npm run build:de` not run by the test-writer |
| AC5: unit tests for actions, dialogs, error cases and navigation after delete; `cd web && npm run check` passes | all of the above; whole web suite | `cd web && npm run coverage` (whole suite), `cd web && npm run lint` | Unit tests and lint: Pass. Full `npm run check` (api:check, e2e:typecheck, ng build): Not run by the test-writer, the parent must run it |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/mail/list/*.spec.ts' --include='src/locale/*.spec.ts'`

Result:

Pass: 9 files, 307 tests passed (2026-11-01 08:43:56 +01:00). The new 409-reload test failed during writing for a test reason only (a second menu opened after clearing the overlay found no panel); fixed in the test by waiting for the first menu to close instead of clearing the overlay.

`cd web && npm run lint`: Pass, "All files pass linting."

`cd web && npx prettier --check` on the four task spec files: Pass, "All matched files use Prettier code style!"

## Full test execution

Command:

`cd web && npm run coverage` (the whole web Vitest suite with coverage)

Result:

Pass: 185 files, 3989 tests passed, started 2026-11-01 08:44:18 +01:00, duration 48.3 s.

## .taskcheck

Result:

Not run: `sh scripts/check.sh` also runs the Gradle server and Android checks, which this helper does not run; the parent runs it.

## Bugs found

None. No test exposed a production defect in the final revision.

## Coverage

Status: verified (whole-file, web part).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-mail-folder-manage`; base `f27bdf89f0f3ec14d4faa2077df0a7752dd9381f` (from the parent); HEAD `0fa8fd22d40387b317b138b74d569833f7893058`; file scope from `git diff --stat f27bdf89f..0fa8fd22d` (task commits `3447cbb95`, `63e2ee051`, `0fa8fd22d`; other paths in the range are `tasks/` and `reviews/` only).
- Source/test snapshot identifier (including uncommitted inputs): production files clean at HEAD (sha256 `mail-folder-dialogs.ts` 34f2f0b4...a404, `mail-folder-tree.ts` 532099d4...bac8, `mail-folders-store.ts` 3aaa2426...320f, `mail.service.ts` 7e80cf9e...5abc); only uncommitted test file `mail-folder-tree.spec.ts` sha256 d980306e...d365. (`reviews/web-mail-folder-manage/review.md` was also modified in the worktree, not by this helper; it is not a test input.)
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/features/mail/list/mail-folder-dialogs.ts`, `mail-folder-tree.ts`, `mail-folders-store.ts`, `mail.service.ts`. Excluded: `mail-folder-tree.scss` (styles, no executable behavior), `web/src/locale/messages.xlf` and `messages.de.xlf` (translation data), the `*.spec.ts` files (tests). No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with `@vitest/coverage-v8`; `npm run coverage` in `/home/eric/work/my-cockpit-agents/agent-1/web` (`ng test --watch=false --coverage` with text-summary, json-summary, json, lcov reporters); whole web suite.
- Run start/completion time and exit status: build 2026-11-01T07:44:18Z, tests 08:44:18 +01:00, report written 08:45:06 +01:00; exit status 0, all tests passed.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` (sha256 6c56148a...f5f92f), `web/coverage/web/coverage-final.json` (sha256 b7c5c0f3...8f7732).
- Evidence outputs are fresh and inputs stayed unchanged: report mtime (08:45:06 +01:00) is after the run start; Vitest rewrites `coverage/`; the counters reflect the fixer's code (statement/branch totals and line numbers changed from the first round); no test or production file was changed after the run.
- Final run after latest test additions and production fixes: yes (after the fixer commit `0fa8fd22d` and this round's tests).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/mail/list/mail-folder-dialogs.ts` | 136/137 (99.3%) | 51/54 (94.4%) | 9/9 (100%) | 98/98 (100%) |
| `web/src/app/features/mail/list/mail-folder-tree.ts` | 124/125 (99.2%) | 57/60 (95.0%) | 29/29 (100%) | 102/102 (100%) |
| `web/src/app/features/mail/list/mail-folders-store.ts` | 91/91 (100%) | 60/61 (98.4%) | 38/38 (100%) | 75/75 (100%) |
| `web/src/app/features/mail/list/mail.service.ts` | 22/22 (100%) | 17/18 (94.4%) | 11/11 (100%) | 18/18 (100%) |
| Aggregate (summed counters, the 4 files above; scss and xlf excluded as non-executable, no file missing) | 373/375 (99.5%) | 185/193 (95.9%) | 87/87 (100%) | 293/293 (100%) |

All new fixer code is covered: the 404 swallow in `remove` (both branches of `error instanceof ApiError && error.status === 404`), the `alsoOn` parameter (default and `[409]`), and the `action === 'delete'` branch in `folderErrorMessage`.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `mail-folder-dialogs.ts:253` (`if (this.deleting()) return;` in the delete dialog) | Double delete while one is running | Not reachable from the UI: the Delete button is disabled while deleting. The behavior (deletes once) is asserted by "cannot be closed while deleting, disables both buttons and deletes only once". Justified gap. |
| `mail-folder-dialogs.ts:122:33`, `:245:35` (branches reported on the `export class` lines) | None (compiler-generated class/decorator branches) | Not source behavior. Justified gap. |
| `mail-folder-tree.ts:180` (`if (!account?.id) return;` in `create`) | Defensive: the folder's account is not in the store | Not reachable in the current UI (the menu only exists for folders of loaded accounts). Justified gap. |
| `mail-folder-tree.ts:267:40` (`this.store.trees() ?? []` in `delimiterOf`) | Defensive: no trees loaded | The New folder button only renders when trees are loaded. Justified gap. |
| `mail-folder-tree.ts:136:27` (branch on the `export class` line) | None (generated) | Justified gap. |
| `mail-folders-store.ts:45:35` (`this.trees() ?? []` in `accountById`) | Pre-existing code, not changed by this task | Out of task scope. |
| `mail.service.ts:120:28` (`thread.items ?? []` in `thread`) | Pre-existing code, not changed by this task | Out of task scope. |

## Unable to test

- German build: `src/locale/messages-de.spec.ts` passes, but `npm run build:de` was not run by the test-writer. Follow-up: run it if the parent wants build evidence for the German labels.
- Real keyboard focus movement (Tab order, focus trap landing on the name field) cannot be observed in jsdom; the tests assert `cdkFocusInitial`, button/menuitem roles, `aria-expanded` and Escape handling instead. Browser verification would need an e2e test, which this task's criteria do not name.
- Full `npm run check` and `.taskcheck` were not run by the test-writer; the parent must run them.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient for the final revision `0fa8fd22d`; no production defects found.
