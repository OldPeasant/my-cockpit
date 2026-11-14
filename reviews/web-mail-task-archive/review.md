# Code Review

Date: 2026-11-14

Task: web-mail-task-archive - Web: mail → Next action & archive (read view, New view, mail list)

Scope: final re-review of the full task, `git diff ddb9b8132693de81e4123adb6936175ecb73456f..e1dbf818c -- web` (commits 2657422cb, c0cad1a35, 4be43e6cc, db10d9b2c, bcadfd88c, e1dbf818c). Focus: the fixes in bcadfd88c and the tests in e1dbf818c. Reviewed revision: e1dbf818c.

Requirements:
- tasks/web-mail-task-archive.md acceptance criteria and recorded assumptions
- SPEC.md §7.3, docs/ARCHITECTURE.md, AGENTS.md project notes
- api/openapi.yaml `POST /api/v1/mail/messages/to-tasks` (MailToTasksRequest, MailToTaskResultResponse)
- First review (CR-1 to CR-5), reviews/web-mail-task-archive/fixes.md, reviews/web-mail-task-archive/tests.md

Git range:
- Branch: main
- HEAD: e1dbf818c
- Base/start: ddb9b8132693de81e4123adb6936175ecb73456f (merge base with origin/main)
- Commits reviewed: 6 (no uncommitted changes)

Summary: 0 P0, 0 P1, 0 P2, 2 P3

Validation run by the reviewer: `cd web && npm run check` at e1dbf818c: api check up to date, lint clean, e2e typecheck clean, 196 test files / 4884 tests passed, production build succeeded (only the existing initial-bundle budget warning).

## Status of the earlier findings

- CR-1 (archive errors hidden by the success message, several mails): resolved. `mail-list-page.ts:345-355` now opens exactly one snack bar: either the single error from `nextActionsIncomplete(...)` naming every mail not archived (and the count and reason of mails that got no task), or the "N next actions created" message with its Next Actions action. Covered by "keeps a mail whose archive failed in the list, with an error naming it" and "names every mail not archived in the one error" in `mail-list-page.spec.ts`.
- CR-2 (no unit tests): resolved. New specs cover `MailService.toNextActions` (body `list: 'NEXT', archive: true`, silent context, chunks of 100, first-request and later-request failure), `MailListStore.toNextActions` (selection and cursor targets, archive failure, partial failure, threaded conversation archive and its refetch on failure, live-update ordering), the mail list page (single and multi paths, snack bar link to `/next`, errors, double run), the read view (replaceUrl, archive failure, request failure, blocked triage, page left while running, help entry) and the New view (button, Shift+T on mail rows only, history, errors, double run).
- CR-3 (read view: other actions during the request): resolved. `canAct()` (`mail-read-page.ts:333-335`) includes `makingNextAction()`, and the six manage buttons are disabled while it runs; tested by "blocks Archive, Spam, Delete, Mark unread, Star and Move while it runs".
- CR-4 (threaded list: row comes back): resolved. `archiveRestOfConversations` (`mail-list-store.ts:561-574`) archives the conversation's other messages in the shown folder through the existing `messageIds`/`send('ARCHIVE', ...)` path while `running` is still counted, and queues a refetch when that fails. The behavior is recorded as an assumption in the task file. Tested in the threaded-view block of `mail-list-store.spec.ts`.
- CR-5 (later request failure discards earlier results): resolved. `MailService.toNextActions` rejects a failed later request with `PartialNextActionsError` carrying the earlier results; the store removes the archived mails and rethrows `NextActionsPartlyFailedError`; the page reports created count, not-archived subjects and the count without a task in one message. Tested at service, store and page level.

## Acceptance criteria check

1. Read view button next to "→ Inbox task" (unchanged), `Shift+T`, task detail opens with `replaceUrl` so Back returns to the origin: met.
2. New view button on mail rows and `Shift+T` on the cursor mail row, task detail opens (pushed, Back returns to New, per recorded assumption): met.
3. Mail list with several mails: one task per mail, archived mails leave, list stays, "N next actions created" snack bar with a Next Actions action: met.
4. Archive failure keeps the task (single mail opens its detail with an error naming the mail; several mails get one error naming them); a failed creation leaves the mail with an error: met.
5. Linked items and the archived mail: relies on the server link created by `mail-task-archive-api` and the existing task-detail link list and read view (which loads a message by id regardless of folder); no web change needed in this scope.
6. Strings in `messages.xlf` and `messages.de.xlf` (only this task's units added, rest are line-number moves); `Shift+T` in the list and read-view `?` help and in the New view keys hint: met.
7. Unit tests and `npm run check`: met (see validation above). `sh scripts/check.sh` was not run by the reviewer (Gradle not allowed here).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-6 | New view navigates to the task even after the user left the page**
    - File: `web/src/app/features/new/new-page.ts:177`
    - Category: Correctness
    - Problem: `toNextAction` awaits `mailService.toNextActions` and then always calls `router.navigate(['/tasks', ...])`. If the user moves to another page while the request runs, they are pulled to the task detail afterwards. The read view guards the same step with `this.destroyed` (`mail-read-page.ts:251`) and has a test for it; the New view has neither.
    - Why it matters: Small but surprising navigation, and the two views of the same action behave differently.
    - Suggested fix: Track destruction (`inject(DestroyRef).onDestroy(...)`) and skip the navigation when the page is gone, as the read view does; add a matching spec.

- [ ] **CR-7 | Mail list: triage actions on the same mails can overlap a running "→ Next action & archive"**
    - File: `web/src/app/features/mail/list/mail-list-page.ts:304` (and the toolbar buttons in `mail-list-page.html:52-115`)
    - Category: Correctness
    - Problem: While `makingNextActions()` is set, only the new button is disabled. `act()` and the Archive, Delete, Spam, Read/Unread, Flag and Move buttons and keys still run on the same, not yet removed, targets (the store removes them only after the to-tasks response). If the user archives them meanwhile, the server may report the to-tasks archive as failed and the page shows "Not archived: ..." for mails that are in fact archived, or the triage action reports a failure for mails already moved. The list itself recovers through the store's refetch, so no state is lost.
    - Why it matters: Misleading error messages in a reachable sequence; this is the list counterpart of the read-view issue fixed under CR-3.
    - Suggested fix: Either skip `act()`/`move()` while `makingNextActions()` is set and disable the triage buttons the same way, or remove the targets optimistically when the request starts and restore those whose creation failed.

## Review conclusion

No material issues found. All five earlier findings (CR-1 to CR-5) are resolved at e1dbf818c; the two remaining findings are P3 and optional.
