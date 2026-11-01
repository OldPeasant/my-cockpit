# Fix Report

Date: 2026-11-01

Task: web-mail-folder-manage - Mail folder tree: create, rename and delete folders

Scope: current task; findings CR-1, CR-2 and CR-3 from reviews/web-mail-folder-manage/review.md (reviewed revision 3447cbb95).

## Fixed findings

### CR-1 — A 409 on Delete shows "A folder with this name already exists here." (P2)

Files changed:
- `web/src/app/features/mail/list/mail-folder-dialogs.ts`
- `web/src/app/features/mail/list/mail-folder-tree.ts`
- `web/src/app/features/mail/list/mail-folder-dialogs.spec.ts`
- `web/src/app/features/mail/list/mail-folder-tree.spec.ts`
- `web/src/locale/messages.xlf`
- `web/src/locale/messages.de.xlf`

Problem:
`folderErrorMessage` mapped every 409 to the name-conflict text, and the delete dialog used it too. For DELETE the API's 409 means INBOX or a folder with a role (openapi.yaml, deleteMailFolder). The folders were not reloaded on that 409, so the menu kept offering Delete.

Fix:
`folderErrorMessage(error, action)` takes an optional `'save' | 'delete'` (default `'save'`). On delete, a 409 returns the new localized text `@@mail.folder.error.deleteRefused`: "This folder cannot be deleted: it is the Inbox or has a role." (German: "Dieser Ordner kann nicht gelöscht werden: Er ist der Posteingang oder hat eine Rolle."). The delete dialog passes `'delete'`. `reloadingOnStale` takes an optional list of extra statuses, and the tree's delete passes `[409]`, so a refused delete reloads the folders. The reload brings in the role and hides Delete. The new unit was added by hand to both xlf files. The German target has `state="translated"`. Position and line number match a comparison `ng extract-i18n` written to `.tmp/`, which was deleted afterwards. The real locale files were not regenerated.

Tests added: `folderErrorMessage` delete 409 vs save 409; other statuses read the same on delete and on save; the delete dialog shows the 409 text and stays open; the tree reloads the folders on a delete 409 and keeps the folder and the page (`it.each([412, 409])`).

Validation:
- `npx ng test --watch=false --include='src/app/features/mail/list/*.spec.ts' --include='src/locale/*.spec.ts'` — Pass (9 files, 301 tests)

### CR-2 — A folder deleted elsewhere leaves the delete dialog in a retry loop (P3)

Files changed:
- `web/src/app/features/mail/list/mail-folder-tree.ts`
- `web/src/app/features/mail/list/mail-folder-tree.spec.ts`

Problem:
A DELETE answered with 404 (the folder is already gone on the server) rejected. The dialog then asked for a retry that could never succeed, and the page stayed on the removed folder.

Fix:
The tree's `remove` callback now catches a 404 from `deleteFolder` and goes on as if the delete had worked. It drops the folder (and its subtree) locally with `store.removed`, navigates to `/mail` if the shown folder was in it, and resolves. A resolved callback makes the delete dialog close with `true`, as covered by the existing dialog test "deletes on confirm and closes with true". All other errors still reject.

Test change: the existing `it.each([404, 412])` test "reloads the folders when a delete finds the folder stale" expected the delete to reject on 404. CR-2 changes that required behavior, so 404 was taken out of it. It now covers 412 and 409. Two new tests: a 404 removes the folder and leaves it if it was shown (navigates to `/mail`, resolves), and a 404 on a folder that is not shown removes it without navigating.

Validation:
- `npx ng test --watch=false --include='src/app/features/mail/list/*.spec.ts' --include='src/locale/*.spec.ts'` — Pass

### CR-3 — Malformed import block in mail-folder-dialogs.ts (P3)

Files changed:
- `web/src/app/features/mail/list/mail-folder-dialogs.ts`

Problem:
The `@angular/forms` import was not formatted the way Prettier formats it.

Fix:
Ran `npx prettier --write` on the file. Only the import block changed.

Validation:
- `npx prettier --check` on the four changed TypeScript files — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include='src/app/features/mail/list/*.spec.ts' --include='src/locale/*.spec.ts'` — Pass, 9 files, 301 tests (includes the German locale spec)

Full test suite:
- Not run by the fixer (`npm run check` / `sh scripts/check.sh` is left to the parent worker)

Lint/type-check/build:
- `cd web && npm run lint` — Pass (All files pass linting); the test build compiled the changed files
- `npx prettier --check` on mail-folder-tree.ts, mail-folder-dialogs.ts and their specs — Pass

`.taskcheck`:
- Not run (left to the parent worker)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
