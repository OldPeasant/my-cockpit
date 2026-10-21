# Code Review

Date: 2026-10-21

Task: web-mail-list - Mail list and triage

Scope: `git diff d231ab38..05d214a5`, excluding `tasks/` (commits c8f946e8, 496cd01a, 05d214a5)

Requirements:
- tasks/web-mail-list.md (acceptance criteria 1-4 and the recorded assumptions)
- SPEC.md §7.3, §7.4
- docs/ARCHITECTURE.md (web conventions, i18n, test commands, e2e)
- AGENTS.md project notes
- Parent instructions: focus on the optimistic update / rollback / refresh logic in mail-list-store.ts, accessibility, i18n, conventions and that the e2e hook exists only in profile `e2e`

Git range:
- Branch: main
- HEAD: 05d214a5d763fd027f2e7fc1c39689f714054bca
- Base/start: d231ab389613432fdb1dffbfaeda62ef5656249c
- Commits reviewed: 3

Summary: 0 P0, 0 P1, 3 P2, 4 P3

Verified without findings: the e2e hook `POST /api/test/mail/sync` is `@Profile("e2e")` and `@Hidden`, is covered by the existing e2e-only permit-all filter chain of `core.testsupport` (outside `e2e` the path falls into the authenticated main chain and no bean exists, which a Spring test asserts), and logs only counts. GreenMail is published on 127.0.0.1 only. The store guards loads with a load sequence, defers refetches while actions run, and drops and redoes a refetch that started before an optimistic change (tested). Failure paths of load, load more and actions end in a visible, retryable state. i18n ids are unique to this feature and every user-facing string is externalized. The e2e spec covers mark read, archive and move against GreenMail plus a live update; `--no-deps` only skips the phase-demo project, which `web/e2e/README.md` documents. I did not re-run the suites myself.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Move acts on the targets as they are when the dialog closes, not on the ones it was opened for**
    - File: `web/src/app/features/mail/list/mail-list-page.ts:142`
    - Category: Correctness
    - Problem: `move()` reads `moveAccountId()` and the source folder from `store.targets()` before opening the dialog, then calls `store.apply('MOVE', target)`. `apply` reads `this.targets()` again (`mail-list-store.ts:216`). While the dialog is open, keys are blocked but live refetches still run: `refresh()` prunes the selection and moves the cursor to the next item when the cursor item disappeared remotely (`mail-list-store.ts:169-171`). The move then goes to a different message than the one the user picked the folder for. With an empty selection after pruning, it can even move the cursor item instead of the selection, possibly of another folder or account than the one the targets list was built for.
    - Why it matters: the user moves mail they did not choose (an out-of-date async result changes what a user action applies to).
    - Suggested fix: snapshot the target items (or ids) before opening the dialog, and give `apply` an optional explicit target list. Skip ids that are no longer loaded. Add a unit test where a refresh changes the cursor while the dialog is open.

- [ ] **CR-2 | Rollback of a failed action can undo a concurrent action's successful change on the same item**
    - File: `web/src/app/features/mail/list/mail-list-store.ts:278`
    - Category: Correctness
    - Problem: `rollback` puts back the snapshot version of every failed item, whether or not it is still in the list. Example 1: `s` (FLAG) on item A, then `e` (ARCHIVE) on A while FLAG is still running. ARCHIVE removes A. If FLAG then fails, A comes back into the list (from FLAG's snapshot), although it is archived on the server. Example 2: FLAG and then READ on A. FLAG fails: A goes back to its snapshot (unread, unflagged), and the successful READ is lost from the view. The view only heals when a later refetch arrives through SSE. With SSE down, the wrong state stays until a reload.
    - Why it matters: overlapping writes on the same item are a normal keyboard-triage pattern (several keys pressed in a row). The list then shows mail that is gone, or the wrong flags.
    - Suggested fix: for non-removing actions, restore only the fields this action changed (re-apply the inverse of `withAction`) on the current item, and only if the item is still present. For removing actions, restore only items that are still absent. Alternatively, queue a refetch after every rollback. Add a store test for two overlapping actions on the same item where the first fails.

- [ ] **CR-3 | Folder tree, folders store and move dialog have no tests; overlapping-action paths are untested**
    - File: `web/src/app/features/mail/list/mail-folders-store.ts:196`
    - Category: Tests
    - Problem: `folderTree` (nesting by delimiter, role order, orphaned parents), `MailFoldersStore` (reload on `MAIL_ACCOUNT` events, unknown or created/deleted folder events, load error, stale-load guard) and `MailMoveDialog` (filter by label/path, Enter picks the first match, empty result) have no specs. The page spec only checks the move-target order for one flat account. The store spec has no test for two overlapping actions, or for a failed action while a refetch is queued (the rollback, then the deferred refresh).
    - Why it matters: the folder tree is part of criterion 1, and the live reload of folders is part of criterion 2. The concurrency branches are where CR-2 lives and are prone to regressions.
    - Suggested fix: add `mail-folders-store.spec.ts` (`folderTree` cases plus event-driven reloads), a small `mail-move-dialog.spec.ts`, and store tests for overlapping actions and failure with a queued refresh.

## P3 Low

- [ ] **CR-4 | A refetch that overlaps a "load more" shrinks the list back**
    - File: `web/src/app/features/mail/list/mail-list-store.ts:157`
    - Category: Correctness
    - Problem: `refresh()` computes `limit` from the item count when it starts. If `loadMore()` appends a page while the refetch runs, the refetch result replaces the longer list with the shorter one (and resets `nextCursor`). The cursor may jump back if it was on an appended item. Unlike optimistic actions, `loadMore` is not part of the `mutationSeq` check.
    - Why it matters: while scrolling during incoming mail, the loaded mail disappears and the scroll position or cursor jumps.
    - Suggested fix: bump a sequence in `loadMore` as well (drop and redo the refetch, as for mutations), or have `refresh` keep the items beyond its returned window.

- [ ] **CR-5 | Bulk actions ignore the server's 1000-id limit**
    - File: `web/src/app/features/mail/list/mail-list-store.ts:228`
    - Category: Correctness
    - Problem: `MailActionRequest.ids` allows at most 1000 ids (generated model). "Select all" covers every loaded item (infinite scroll has no cap), and in the conversations view each item expands to all its messages in the folder. Past 1000 ids the whole request is rejected and everything is rolled back.
    - Why it matters: a legitimate bulk triage fails completely, with a generic error.
    - Suggested fix: send the ids in chunks of at most 1000 and merge the results, or cap the selection and say so in the UI.

- [ ] **CR-6 | Holding j/k does not move the cursor repeatedly**
    - File: `web/src/app/features/mail/list/mail-shortcuts.ts:265`
    - Category: Quality
    - Problem: `mailShortcutFor` returns null for `event.repeat`. This is correct for the actions (no repeated archive), but it also blocks auto-repeat of `j`/`k`, which Gmail-like navigation supports.
    - Why it matters: moving through a long list with the keyboard needs one press per row.
    - Suggested fix: allow `repeat` for `next`/`previous` only.

- [ ] **CR-7 | Keyboard cursor moves are not perceivable to assistive technology**
    - File: `web/src/app/features/mail/list/mail-list-page.ts:113`
    - Category: Quality
    - Problem: `j`/`k` only change `aria-current` on the `<li>` and scroll the row into view. Focus stays where it was, and nothing is announced, so screen-reader users cannot tell which message the next `e`/`#`/`v` will act on. The toolbar also acts on the cursor item when nothing is selected.
    - Why it matters: keyboard triage is the main interaction of this view. For non-sighted users, the target of a destructive action is invisible.
    - Suggested fix: when the cursor moves by keyboard, move focus to the row's `.open` button (roving focus). Alternatively, announce the current message in a polite live region.

## Review conclusion

Material issues found; fix before completing the task.
