# Test Report

Date: 2026-11-01

Task: web-mail-folder-manage - Mail folder tree: create, rename and delete folders

Scope: task base `f27bdf89f0f3ec14d4faa2077df0a7752dd9381f`, task commit `3447cbb95a98432a38bc43ae32aad2c3c0655f81` (HEAD at measurement; same change as the implementation commit `9c4f96d52` named by the parent, rebased). `git diff f27bdf89f..HEAD -- web/`. Plus the uncommitted test-file changes listed below.

Requirements:
- `tasks/web-mail-folder-manage.md` acceptance criteria and recorded assumptions (Delete hidden for folders holding a role folder; New subfolder hidden without delimiter; localized error texts by status; delete performed inside the dialog; local tree update plus background reload)
- `AGENTS.md`, `docs/ARCHITECTURE.md` ("Coverage", web conventions)
- Parent instructions (HTTP-level MailService tests, MailFoldersStore unit tests incl. prefix siblings)

## Test files changed

- `web/src/app/features/mail/list/mail.service.spec.ts`
- `web/src/app/features/mail/list/mail-folders-store.spec.ts`
- `web/src/app/features/mail/list/mail-folder-tree.spec.ts`
- `web/src/app/features/mail/list/mail-folder-dialogs.spec.ts`

The last three were also run through `npx prettier --write` (formatting only, no assertion changes), as the web code is formatted with Prettier.

## Tests added or updated

Count: 39 new tests (no existing test weakened or removed)

- `mail.service.spec.ts`, describe "folder changes" (8): POST create top-level (body without parentId, no If-Match, silent errors, 201 body returned); null parent treated as top level; POST create subfolder with parentId; PATCH rename with `If-Match: "4"` and `{name}` body; DELETE with `If-Match: "0"` and no body (204 resolves undefined); failed create (409) / rename (412) / delete (404) reject with ApiError carrying status and detail, no toast.
- `mail-folders-store.spec.ts`, describe "local folder changes" (13): `subtree` (root plus descendants, not the prefix sibling `Projects2`; stays within the account; flat namespace returns the folder alone; unknown account empty); `added` (tree order under its parent, background reload, replaces a known id instead of duplicating, goes only into its own account); `renamed` (subfolders get new paths and keep ids, version updated, reload; `Projects2` and `Projects2/Alpha` and the other account's `Projects/Alpha` untouched; nested rename stays under its parent); `removed` (drops folder and descendants, returns their ids, keeps `Projects2` and the other account, reloads; leaf removal); local change before first load keeps `trees` null.
- `mail-folder-tree.spec.ts`, describe "actions" (10): deleting the shown folder itself navigates to `/mail`; no navigation when the unified Inbox is shown; no navigation and `Projects2` kept when `Projects` is deleted while `Projects2` is shown; delete 404/412 reloads the folders and does not navigate; create-subfolder 404 reloads; non-API rename error does not reload; New folder of the second account creates in `acc-2` and shows it only there; flat account passes no delimiter to the dialog; subfolder and rename dialogs get the folder's delimiter.
- `mail-folder-dialogs.spec.ts` (8): name dialog: rename to the same name with surrounding spaces closes with false without saving; name of 201 characters refused; name of exactly 200 accepted; non-API error shows "Something went wrong." and stays open with `disableClose` reset; a new attempt clears the previous error. Delete dialog: while deleting, `disableClose` is set, Delete and Cancel are disabled, Escape does not close, a second click does not delete twice, closes with true afterwards; a failed delete (status 0) followed by a successful retry clears the error and closes; Cancel closes without deleting.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: each account has "New folder"; each folder a menu with New subfolder / Rename / Delete; no Rename/Delete for INBOX; no Delete for role folders (and, per assumption, folders holding one); no New subfolder without delimiter | tree spec: "gives each account a labelled New folder button", "offers New subfolder, Rename and Delete for an ordinary folder", "offers only New subfolder for INBOX", "treats a folder named INBOX without a role as INBOX", "does not offer Delete for a folder with a role, nor for one holding it", "does not offer New subfolder in a flat namespace"; store spec `subtree` tests | `cd web && npx ng test --watch=false --include='src/app/features/mail/list/*.spec.ts'` | Pass |
| AC2: New folder / Rename open a name dialog; server errors (409 taken, 400 invalid, 0 unreachable, 502) shown in the dialog which stays open; the tree shows the change at once | dialogs spec: "shows a server error (%s) in the dialog, which stays open", "shows a generic text for an error that is not an API error, and stays open", "clears the previous server error when trying again", "saves the trimmed name and closes with true", validation tests; folderErrorMessage tests; tree spec: "creates a top-level folder and shows it at once", "creates a subfolder under the folder of the menu", "renames a folder and moves its subfolders along at once", "creates a folder in the account of the clicked button", stale 404/412 reload tests; store spec `added` / `renamed` tests; service spec create/rename tests | same | Pass |
| AC3: Delete asks for confirmation saying messages and subfolders are deleted on the mail server too; deleting the shown folder navigates to the unified Inbox | dialogs spec: "asks, naming the folder and saying its messages go on the mail server too", "mentions %s subfolder(s)", "deletes on confirm and closes with true", in-flight and retry tests; tree spec: "navigates to the unified Inbox when the shown folder is deleted" (ancestor), "navigates to the unified Inbox when the deleted folder itself is shown", "stays on the shown folder when another one is deleted", "does not navigate when the unified Inbox is shown...", "does not navigate when a sibling sharing the path prefix...", "keeps the folder and the page when the delete fails"; store spec `removed` tests; service spec delete test | same | Pass |
| AC4: keyboard usable (menus and dialogs focusable, Escape closes), labels localized | tree spec: "opens the menu from a focusable button and closes it with Escape"; dialogs spec: "closes with Escape without saving", "closes with Escape without deleting", cdkFocusInitial checks, Cancel tests | same | Pass (unit level). German translations in `messages.de.xlf` not exercised by unit tests: `npm run build:de` not run by the test-writer |
| AC5: unit tests for actions, dialogs, error cases and navigation after delete; `cd web && npm run check` passes | all of the above; full web suite | `npm --prefix web run coverage` (full suite), `npm --prefix web run lint` | Unit tests and lint: Pass. Full `npm run check` (api:check, e2e:typecheck, ng build): Not run by the test-writer, the parent must run it |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/mail/list/*.spec.ts'`

Result:

Pass: 8 files, 286 tests passed (verbose run confirmed the new tests executed).

`npm run lint` (web): Pass, "All files pass linting." (run before and after the Prettier formatting).

## Full test execution

Command:

`npm --prefix web run coverage` (the whole web Vitest suite with coverage)

Result:

Pass: 185 files, 3975 tests passed, started 2026-11-01 08:37:54 +01:00, duration 40.6 s.

## .taskcheck

Result:

Not run: `sh scripts/check.sh` also runs the Gradle server and Android checks, which this helper cannot run in its environment; the parent runs it.

## Bugs found

None. No test exposed a production defect.

## Coverage

Status: verified (whole-file, web part).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-mail-folder-manage`; base `f27bdf89f0f3ec14d4faa2077df0a7752dd9381f` (from the parent); HEAD `3447cbb95a98432a38bc43ae32aad2c3c0655f81`, the single task commit touching `web/` since the base (`git log` of `web/` from the parent's implementation commit to HEAD), matching the file list the parent gave.
- Source/test snapshot identifier (including uncommitted inputs): production files clean at HEAD; uncommitted test files (sha256): `mail.service.spec.ts` 156c6863...a231, `mail-folders-store.spec.ts` 2ac53ef5...29db, `mail-folder-tree.spec.ts` cc53b515...48b3, `mail-folder-dialogs.spec.ts` 09b6da21...db09.
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/features/mail/list/mail-folder-dialogs.ts`, `mail-folder-tree.ts`, `mail-folders-store.ts`, `mail.service.ts`. Excluded: `mail-folder-tree.scss` (styles, no executable behavior), `web/src/locale/messages.xlf` and `messages.de.xlf` (translation data), the `*.spec.ts` files (tests). No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with `@vitest/coverage-v8` 5.0.5; `npm --prefix /home/eric/work/my-cockpit-agents/agent-1/web run coverage` (`ng test --watch=false --coverage` with text-summary, json-summary, json, lcov reporters); whole web suite.
- Run start/completion time and exit status: start 2026-11-01T07:37:54Z (build), tests 08:37:54 +01:00, report written 2026-11-01T07:38:35Z; exit status 0, all tests passed.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` (sha256 326849af...bb13), `web/coverage/web/coverage-final.json` (sha256 55b86450...9d06).
- Evidence outputs are fresh and inputs stayed unchanged: the report mtime (07:38:35Z) is after this run started; Vitest rewrites `coverage/`; no test or production file was changed after the run (only lint was re-run).
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/mail/list/mail-folder-dialogs.ts` | 134/135 (99.3%) | 48/51 (94.1%) | 9/9 (100%) | 96/96 (100%) |
| `web/src/app/features/mail/list/mail-folder-tree.ts` | 121/122 (99.2%) | 51/54 (94.4%) | 29/29 (100%) | 100/100 (100%) |
| `web/src/app/features/mail/list/mail-folders-store.ts` | 91/91 (100%) | 60/61 (98.4%) | 38/38 (100%) | 75/75 (100%) |
| `web/src/app/features/mail/list/mail.service.ts` | 22/22 (100%) | 17/18 (94.4%) | 11/11 (100%) | 18/18 (100%) |
| Aggregate (summed counters, the 4 files above; scss and xlf excluded as non-executable) | 368/370 (99.5%) | 176/184 (95.7%) | 87/87 (100%) | 289/289 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `mail-folder-dialogs.ts:246` (`if (this.deleting()) return;` in the delete dialog) | Double delete while one is running | Guard is not reachable from the UI: the Delete button is disabled while deleting, so a click never reaches the handler. The behavior (deletes once) is asserted by "cannot be closed while deleting, disables both buttons and deletes only once". Justified gap. |
| `mail-folder-dialogs.ts:115:33`, `:238:35` (branches reported on the `export class` lines) | None (compiler-generated class/decorator branches) | Not source behavior. Justified gap. |
| `mail-folder-tree.ts:180` (`if (!account?.id) return;` in `create`) | Defensive: the folder's account is not in the store | Not reachable in the current UI (the menu only exists for folders of loaded accounts). Justified gap. |
| `mail-folder-tree.ts:252:40` (`this.store.trees() ?? []` in `delimiterOf`) | Defensive: no trees loaded | The New folder button only renders when trees are loaded. Justified gap. |
| `mail-folder-tree.ts:136:27` (branch on the `export class` line) | None (generated) | Justified gap. |
| `mail-folders-store.ts:45:35` (`this.trees() ?? []` in `accountById`) | Pre-existing code, not changed by this task | Out of task scope. |
| `mail.service.ts:120:28` (`thread.items ?? []` in `thread`) | Pre-existing code, not changed by this task | Out of task scope. |

## Unable to test

- German translations of the new messages are not checked by unit tests; `npm run build:de` (fails on a missing translation) was not run by the test-writer. Follow-up: the parent's `npm run check` / `ng build` covers the default build; run `npm run build:de` if the parent wants evidence for the German labels.
- Real keyboard focus movement (Tab order, focus trap landing on the name field) cannot be observed in jsdom (no layout); the tests assert the `cdkFocusInitial` markers, the button/menuitem roles, `aria-expanded` and Escape handling instead. Browser verification would need an e2e test, which this task's criteria do not name.
- Full `npm run check` and `.taskcheck` were not run by the test-writer (see above); the parent must run them.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production defects found.
