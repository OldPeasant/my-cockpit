# Code Review

Date: 2026-11-01

Task: web-mail-folder-manage - Mail folder tree: create, rename and delete folders

Scope: final review round, covering the final revision fe5785342193bbe85a7b612cec2f769966b47562. The second round reviewed the code at 0fa8fd22d (`git diff f27bdf89f..0fa8fd22d`): the implementation 3447cbb95, the test-writer commit 63e2ee051 and the fix commit 0fa8fd22d. Files reviewed there: web/src/app/features/mail/list/ (mail-folder-tree.ts/.scss/.spec.ts, mail-folder-dialogs.ts/.spec.ts, mail-folders-store.ts/.spec.ts, mail.service.ts/.spec.ts) and this task's units in web/src/locale/messages.xlf and messages.de.xlf. The fix delta `git diff 3447cbb95..0fa8fd22d -- web/src` was read line by line, and the production files were read in full at that revision. This round added `git diff 0fa8fd22d..fe5785342`, the test-writer's second commit.

Requirements:
- tasks/web-mail-folder-manage.md acceptance criteria and the recorded assumptions
- api/openapi.yaml, deleteMailFolder (404 "No such account or folder", 409 "INBOX or a folder with a role", 412 stale If-Match), plus the create and rename operations
- AGENTS.md project notes (i18n, ETag/If-Match, lint conventions)
- reviews/web-mail-folder-manage/review.md (earlier rounds) and fixes.md

Git range:
- Branch: main
- HEAD: fe5785342193bbe85a7b612cec2f769966b47562
- Base/start: f27bdf89f0f3ec14d4faa2077df0a7752dd9381f
- Commits reviewed: 4 task commits (3447cbb95, 63e2ee051, 0fa8fd22d, fe5785342). Board-only commits in the range were not reviewed.

Uncommitted changes: none in the working tree when this review ran. Only committed code was reviewed.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Final round: 0fa8fd22d..fe5785342

- **Production code unchanged.** `git diff --stat 0fa8fd22d..fe5785342` lists only reviews/web-mail-folder-manage/review.md, reviews/web-mail-folder-manage/tests.md and web/src/app/features/mail/list/mail-folder-tree.spec.ts (53 lines added, none removed). No other file under web/src, server/, android/ or api/ changed. So the second-round findings on production code still apply to the final revision.
- **The new tests are correct and meaningful.** I checked each one against mail-folder-tree.ts:219-263 at the final revision:
  - *Refused delete (409) then reload hides Delete.* The test runs the real `reloadingOnStale(..., [409])` path. The mocked reload returns the folder with role ARCHIVE. The test waits until the store holds that role, then checks that the folder stays in the tree under its role label and that its menu offers only New subfolder and Rename. It also checks that `deleteFolder` ran only once. This covers the full user-visible result of the CR-1 fix, not just the fact that a reload started.
  - *Delete failing with 502, 0, a non-API Error, or a non-API Error carrying `status: 404`.* None of these should reload, remove the folder or navigate. The test expects the same error object to be rethrown (`rejects.toBe(failure)`). The 404-shaped plain Error case pins down the `instanceof ApiError` guard at line 233, so only a real API 404 counts as a completed delete. The `mail.accounts` not-called assertion means something: `show()` fills `store.trees` directly, so a reload is the only thing that would call `accounts`.
  - *Rename refused with 409 does not reload.* Rename calls `reloadingOnStale` without `alsoOn` (line 211), so only 404 and 412 reload. The test pins down that the delete-only 409 reload did not leak into rename.
- **Nothing weakened.** The commit removes no assertions, skips no tests and does not change existing tests. Fictitious test data only.

## Status of first-round findings

- **CR-1 (P2) A 409 on Delete showed the name-conflict text: fixed.**
  - `folderErrorMessage(error, action)` (mail-folder-dialogs.ts:23) returns the new `@@mail.folder.error.deleteRefused` text for a 409 when `action` is `'delete'`. The delete dialog passes `'delete'` (mail-folder-dialogs.ts:261). The text matches the API's 409 meaning.
  - In mail-folder-tree.ts, the tree's delete passes `[409]` to `reloadingOnStale` (lines 238 and 248-263). A refused delete therefore reloads the folders, which brings in the role and hides Delete.
  - The new unit is in both xlf files exactly once, and the German target is filled in.
  - Tests cover it: the 409 text on delete vs save, the other statuses reading the same on delete, the delete dialog showing the 409 text and staying open, the tree reloading on a delete 409 while keeping the folder and the page, and (final round) Delete being hidden after that reload.
- **CR-2 (P3) A 404 on delete started a retry loop: fixed.**
  - mail-folder-tree.ts:229-234 swallows only an `ApiError` with status 404 from `deleteFolder`, then runs the same path as a success. That path calls `store.removed`, which drops the folder locally and starts a background reload. If the shown folder was in the subtree, the page navigates to `/mail`. Because the callback resolves, the dialog closes with `true`.
  - All other errors still reject, and 412/409 still reload.
  - Tests cover a 404 on the shown folder (navigates), a 404 on another folder (no navigation), and (final round) a non-API error carrying status 404 (not treated as deleted).
  - The earlier test expecting a rejection on 404 was changed to cover 412 and 409. That change follows from the changed requirement and does not weaken the suite.
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

1. **Folder actions: met.** Each account has a "New folder" button (`button.add`). Each folder has a menu with New subfolder, Rename and Delete. INBOX (by role, or by its name in any case) gets no Rename or Delete. A folder with a role, or holding one, gets no Delete (assumption recorded). New subfolder is hidden for flat namespaces (assumption recorded). Tests are in mail-folder-tree.spec.ts.
2. **Name dialog: met.** It shows localized server errors (409 taken, 400 invalid, 0 unreachable, 502 mail server, 404/412 stale plus a reload) and stays open. `disableClose` is set while a save runs, and a second submit is blocked. After success, `store.added` or `store.renamed` updates the tree at once, then a background reload follows. The store's `seq` guard stops an older load from overwriting a newer one.
3. **Delete confirmation: met.** It says the folder's messages and subfolders are deleted on the mail server too, and uses an ICU plural for the subfolder count. Deleting the shown folder, or a folder above it, navigates to `/mail`. Deleting a sibling that only shares a path prefix does not. A 404 counts as a completed delete.
4. **Keyboard and i18n: met.** The menu triggers are real buttons that can take focus. The mat-menu items have menuitem roles, and Escape closes the menu. The dialogs set cdkFocusInitial (on Cancel in the delete dialog) and close on Escape, except while a change runs. The labels use i18n/$localize with `mail.folder.*` ids, and every unit has a German target.
5. **Unit tests: met in code; the check run is still the parent's evidence.** Tests cover the actions, both dialogs, the error cases (including the delete 409 and 404, and the non-reloading failures added in the final round), the navigation after delete, the store's local updates, and the service's HTTP contract (method, body, quoted If-Match, silent errors). This review did not run `npm run check` or the spec itself. The parent's final `npm run check` / `.taskcheck` run on fe5785342 is still the evidence for this criterion.

## Review conclusion

No material issues found.
