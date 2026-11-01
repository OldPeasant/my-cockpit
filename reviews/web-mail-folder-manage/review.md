# Code Review

Date: 2026-11-01

Task: web-mail-folder-manage - Mail folder tree: create, rename and delete folders

Scope: implementation commit 9c4f96d52 (`git diff f27bdf89f..9c4f96d52`), rebased unchanged as 3447cbb95 (`git range-diff` reports the patches identical). Files: web/src/app/features/mail/list/ (mail-folder-tree.ts/.scss/.spec.ts, mail-folder-dialogs.ts/.spec.ts, mail-folders-store.ts, mail.service.ts) and web/src/locale/messages.xlf, messages.de.xlf (only this task's 25 units).

Requirements:
- tasks/web-mail-folder-manage.md acceptance criteria and the recorded assumptions
- api/openapi.yaml, /api/v1/mail/accounts/{id}/folders (POST) and /folders/{folderId} (PATCH, DELETE)
- AGENTS.md project notes (i18n, ETag/If-Match, lint conventions)
- Existing conventions in web/src/app/features (quoted-version If-Match, silent API errors shown in place)

Git range:
- Branch: main
- HEAD: 3447cbb95a98432a38bc43ae32aad2c3c0655f81 (same patch as 9c4f96d522a6618c4f67056cfb25c4d97da66d68)
- Base/start: f27bdf89f0f3ec14d4faa2077df0a7752dd9381f
- Commits reviewed: 1

Uncommitted changes: web/src/app/features/mail/list/mail.service.spec.ts is modified in the working tree. It adds HTTP-level tests for createFolder/renameFolder/deleteFolder, probably from the test-writer. This review covers committed code only, so those tests are not counted here.

Summary: 0 P0, 0 P1, 1 P2, 2 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | A 409 on Delete shows "A folder with this name already exists here."**
    - File: `web/src/app/features/mail/list/mail-folder-dialogs.ts:31`
    - Category: Correctness
    - Problem: `folderErrorMessage` maps every 409 to the name-conflict text, and the delete dialog uses the same function (`mail-folder-dialogs.ts:254`). For DELETE, the API's 409 means "INBOX or a folder with a role" (openapi.yaml, deleteMailFolder 409). The tree hides Delete only for roles it knows about. The store does not reload on an UPDATED folder event for a folder it already has (`mail-folders-store.ts:56`), so a role given elsewhere (another tab, or detection during a sync) can stay unknown locally. Delete then returns 409 and the confirmation dialog shows a message about a name being taken. Also, `reloadingOnStale` does not reload on 409, so the menu keeps offering Delete.
    - Why it matters: the delete error is wrong and confusing, and the user has no way to see why the delete was refused. Criterion 2's error handling is meant to explain server errors, and Delete reuses that path.
    - Suggested fix: give the delete dialog its own 409 text (for example "This folder or one inside it has a role and cannot be deleted."), for instance through a context parameter on `folderErrorMessage`. Reload the folders on a delete 409 so the role appears and Delete is hidden. Add a test for the delete 409 text.

## P3 Low

- [ ] **CR-2 | A folder deleted elsewhere leaves the delete dialog in a retry loop and the page on the removed folder**
    - File: `web/src/app/features/mail/list/mail-folder-tree.ts:220`
    - Category: Correctness
    - Problem: if DELETE returns 404 (the folder is already gone on the server), `remove` throws before `store.removed` and the navigation run. The dialog shows "The folder was changed or removed elsewhere. The folder list was reloaded; try again." Retrying sends the same request and gets 404 again. If that folder was the one on screen, the page does not go to the unified Inbox, even though the folder no longer exists.
    - Why it matters: this is a small edge case, but it asks for a retry that can never succeed, and the user stays on a removed folder.
    - Suggested fix: treat 404 on delete as success. Drop the folder locally, navigate if it was shown, and close the dialog. Or at least navigate and close.

- [ ] **CR-3 | Malformed import block in mail-folder-dialogs.ts**
    - File: `web/src/app/features/mail/list/mail-folder-dialogs.ts:2-5`
    - Category: Quality
    - Problem: `ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';` is crammed onto one line inside an otherwise multi-line import. Prettier would not produce this. `npm run check` does not run Prettier, so nothing caught it.
    - Why it matters: it does not match the formatting of the rest of the codebase and will cause unrelated diff noise the next time someone formats the file.
    - Suggested fix: run `npx prettier --write` on the file.

## Acceptance criteria check

1. Account "New folder" button (`button.add`) and a folder menu with New subfolder / Rename / Delete: met. INBOX (by role or by the name INBOX in any case) gets no Rename or Delete. Folders with a role, or holding one, get no Delete (assumption recorded). Tests in mail-folder-tree.spec.ts.
2. Name dialog shows the localized server errors (409/400/0/502, plus 404/412 with a reload) and stays open. After success the tree updates locally at once via `store.added`/`store.renamed`, then reloads in the background. A newer load supersedes an older one through `seq`, so an older load cannot overwrite the local change. Met, apart from CR-1, which affects the delete dialog.
3. The delete confirmation says the folder's messages and subfolders are deleted on the mail server too, with an ICU plural for the subfolder count. Deleting the shown folder, or a folder above it, navigates to `/mail` (the unified Inbox; its href is checked in the spec). Met.
4. Keyboard: the menu triggers are real buttons and stay focusable while visually hidden (opacity, shown on focus-visible). The mat-menu has menuitem roles and Escape closes it. The dialogs use cdkFocusInitial (Cancel in the delete dialog) and close on Escape. Labels use i18n/$localize with ids under `mail.folder.*`, and all 25 units have German targets with no duplicate ids. Met.
5. Unit tests cover the actions, the dialogs, the error cases and the navigation after delete. Met for the committed code; the gap is the delete 409 case (CR-1). The parent reports `npm run check` passed at 9c4f96d52 (3936 tests); this review did not run it again.

## Review conclusion

Material issues found; fix before completing the task. CR-1 (wrong delete error message, no reload on 409) should be fixed. CR-2 and CR-3 are minor.
