# Code Review

Date: 2026-11-01

Task: web-mail-folder-manage - Mail folder tree: create, rename and delete folders

Scope: second review round, final code at 0fa8fd22d (`git diff f27bdf89f..0fa8fd22d`). This is the implementation 3447cbb95, the test-writer commit 63e2ee051 and the fix commit 0fa8fd22d. Files reviewed: web/src/app/features/mail/list/ (mail-folder-tree.ts/.scss/.spec.ts, mail-folder-dialogs.ts/.spec.ts, mail-folders-store.ts/.spec.ts, mail.service.ts/.spec.ts) and this task's units in web/src/locale/messages.xlf and messages.de.xlf. The fix delta `git diff 3447cbb95..0fa8fd22d -- web/src` was read line by line. The production files were read in full at the final revision.

Requirements:
- tasks/web-mail-folder-manage.md acceptance criteria and the recorded assumptions
- api/openapi.yaml, deleteMailFolder (404 "No such account or folder", 409 "INBOX or a folder with a role", 412 stale If-Match), plus the create and rename operations
- AGENTS.md project notes (i18n, ETag/If-Match, lint conventions)
- reviews/web-mail-folder-manage/review.md (first round) and fixes.md

Git range:
- Branch: main
- HEAD: 0fa8fd22d40387b317b138b74d569833f7893058
- Base/start: f27bdf89f0f3ec14d4faa2077df0a7752dd9381f
- Commits reviewed: 3 task commits (3447cbb95, 63e2ee051, 0fa8fd22d). Board-only commits in the range were not reviewed.

Uncommitted changes: none in the working tree when this review ran. Only committed code was reviewed.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Status of first-round findings

- **CR-1 (P2) A 409 on Delete showed the name-conflict text: fixed.** `folderErrorMessage(error, action)` (mail-folder-dialogs.ts:23) returns the new `@@mail.folder.error.deleteRefused` text for a 409 when `action` is `'delete'`. The delete dialog passes `'delete'` (mail-folder-dialogs.ts:261). The text matches the API's 409 meaning. In mail-folder-tree.ts, the tree's delete passes `[409]` to `reloadingOnStale` (lines 238 and 248-263), so a refused delete reloads the folders. That brings in the role and hides Delete. The new unit is in both xlf files exactly once, and the German target is filled in. Tests cover it: the 409 text on delete vs save, the other statuses reading the same on delete, the delete dialog showing the 409 text and staying open, and the tree reloading on a delete 409 while keeping the folder and the page.
- **CR-2 (P3) A 404 on delete started a retry loop: fixed.** mail-folder-tree.ts:229-234 swallows only an `ApiError` with status 404 from `deleteFolder` and then runs the same path as a success. That path calls `store.removed`, which drops the folder locally and starts a background reload. If the shown folder was in the subtree, the page navigates to `/mail`. Because the callback resolves, the dialog closes with `true`. All other errors still reject, and 412/409 still reload. Tests cover a 404 on the shown folder (navigates) and on another folder (no navigation). The earlier test expecting a rejection on 404 was changed to cover 412 and 409. That change follows from the changed requirement and does not weaken the suite.
- **CR-3 (P3) Malformed import block: fixed.** The `@angular/forms` import at mail-folder-dialogs.ts:2-8 is now formatted the way Prettier formats it.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Acceptance criteria check

1. Each account has a "New folder" button (`button.add`). Each folder has a menu with New subfolder, Rename and Delete. INBOX (by role, or by its name in any case) gets no Rename or Delete. A folder with a role, or holding one, gets no Delete (assumption recorded). New subfolder is hidden for flat namespaces (assumption recorded). Met. Tests are in mail-folder-tree.spec.ts.
2. The name dialog shows localized server errors (409 taken, 400 invalid, 0 unreachable, 502 mail server, 404/412 stale plus a reload) and stays open. `disableClose` is set while a save runs, and a second submit is blocked. After success, `store.added` or `store.renamed` updates the tree at once, then a background reload follows. The store's `seq` guard stops an older load from overwriting a newer one. Met.
3. The delete confirmation says the folder's messages and subfolders are deleted on the mail server too, and uses an ICU plural for the subfolder count. Deleting the shown folder, or a folder above it, navigates to `/mail`. Deleting a sibling that only shares a path prefix does not. A 404 now counts as a completed delete. Met.
4. Keyboard: the menu triggers are real buttons that can take focus. The mat-menu items have menuitem roles, and Escape closes the menu. The dialogs set cdkFocusInitial (on Cancel in the delete dialog) and close on Escape, except while a change runs. The labels use i18n/$localize with `mail.folder.*` ids, and every unit has a German target. Met.
5. Unit tests cover the actions, both dialogs, the error cases (including the delete 409 and 404), the navigation after delete, the store's local updates, and the service's HTTP contract (method, body, quoted If-Match, silent errors). fixes.md records a passing focused run (9 files, 301 tests) and `npm run lint`. This review did not run `npm run check` itself, so the parent's final `npm run check` / `.taskcheck` run is still the evidence for that part of the criterion.

## Review conclusion

No material issues found.
