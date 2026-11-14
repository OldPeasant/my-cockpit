# Code Review

Date: 2026-11-14

Task: web-mail-task-archive - Web: mail → Next action & archive (read view, New view, mail list)

Scope: `git diff e9a096d186e0ec753f35c79672abcef9a2d66c6a..9a8d7b66e -- web` (commits 8996e4867, 21bace4a3, 9a8d7b66e)

Requirements:
- tasks/web-mail-task-archive.md acceptance criteria
- SPEC.md §7.3, docs/ARCHITECTURE.md, AGENTS.md project notes
- api/openapi.yaml `POST /api/v1/mail/messages/to-tasks` (MailToTasksRequest / MailToTasksResponse / MailToTaskResultResponse)

Git range:
- Branch: main
- HEAD: 9a8d7b66ed5ad71ef4d8334a6aa39ff536f5ac14
- Base/start: e9a096d186e0ec753f35c79672abcef9a2d66c6a
- Commits reviewed: 3

Summary: 0 P0, 2 P1, 3 P2, 0 P3

Validation run by the reviewer: `cd web && npm run check` ran through to the production build (only the existing initial-bundle budget warning).

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | Archive-failure errors are hidden by the "N next actions created" message for several mails**
    - File: `web/src/app/features/mail/list/mail-list-page.ts:321`
    - Category: Correctness
    - Problem: For several mails, `toNextActions()` calls `notifications.error(...)` once per mail whose archive failed, then immediately opens the "N next actions created" snack bar (line 330). `NotificationService.error` and `info` both use `MatSnackBar.open` (`web/src/app/core/http/notification.service.ts:13`), and MatSnackBar shows only one snack bar at a time: each `open` dismisses the previous one. So every archive error is replaced at once, first by the next error and then by the success message. The user never sees which mail was not archived.
    - Why it matters: Acceptance criterion 4 says "an error says which mail was not archived". In the multi-mail path this does not work. Those mails stay in the list with no explanation.
    - Suggested fix: When some archives failed, show one combined message that names the mails that were not archived, either instead of the success message or merged into it (for example "N next actions created; not archived: subject A, subject B"). Do not open several snack bars one after another. Add a unit test for a mixed result.

- [ ] **CR-2 | The new behavior has no unit tests**
    - File: `web/src/app/features/mail/list/mail-list-store.ts:490`, `web/src/app/features/mail/list/mail.service.ts:205`, `web/src/app/features/mail/read/mail-read-page.ts:237`, `web/src/app/features/new/new-page.ts:168`, `web/src/app/features/mail/list/mail-list-page.ts:316`
    - Category: Tests
    - Problem: The only spec changed in scope is `mail-shortcuts.spec.ts`, which covers the key mapping and help entries. Nothing tests `MailListStore.toNextActions` (optimistic removal of archived items only, failed archives staying), `MailService.toNextActions` (request body `list: 'NEXT', archive: true`, splitting into requests of 100 ids), the read-view flow (navigation with `replaceUrl`, session removal, archive failure still opening the task, a failed request keeping the message), the New-view button and `Shift+T`, or the mail-list single and multi paths (navigation, snack bar link to `/next`, error messages).
    - Why it matters: Acceptance criterion 7 ("Unit tests cover the new behavior") is not met yet. CR-1 is the kind of defect such tests would catch.
    - Suggested fix: Add specs for the paths above, including the archive-failure and request-failure paths in all three views.

## P2 Medium

- [ ] **CR-3 | Read view: other mail actions can run while "→ Next action & archive" is in flight**
    - File: `web/src/app/features/mail/read/mail-read-page.ts:332`
    - Category: Correctness
    - Problem: `canAct()` checks only `state` and `triaging`, not `makingNextAction`. While the to-tasks request runs, `e`/`#`/`!`/`v`/`u` (and their buttons) can still start an archive, delete, spam, move or mark-unread on the same message. The two flows then overlap. The server may report the archive as failed (the message has already moved), which shows a misleading error. Both flows also navigate: `leave()` goes to the next message or back, while `toNextAction()` opens the task. The resulting page depends on which request finishes first.
    - Why it matters: These are overlapping writes on the same message, and the screen the user ends up on is unpredictable.
    - Suggested fix: Include `makingNextAction()` in `canAct()` (or set `triaging` while the request runs), and disable the triage buttons the same way.

- [ ] **CR-4 | Threaded mail list: only the conversation's shown message is archived, so the row comes back**
    - File: `web/src/app/features/mail/list/mail-list-store.ts:496`
    - Category: Correctness
    - Problem: `toNextActions()` sends only `item.id` for each target. Other actions on a threaded list expand a conversation row to all its messages in the folder (`messageIds()`, line 566). After the call, the store removes the row optimistically, but the conversation's other messages are still in the folder. The next refresh brings the row back, so "the mails leave it" does not hold in threaded mode (an option saved in `localStorage`).
    - Why it matters: In threaded mode the list behaves differently from Archive and from the acceptance criterion. The user sees a mail they just processed come back.
    - Suggested fix: Decide the threaded behavior explicitly. One option: still create one task for the shown message, but archive the rest of the conversation through the existing `apply('ARCHIVE', ...)` path. Another option: only remove the row when the conversation has a single message. Record the decision with `tasks.py assume`.

- [ ] **CR-5 | A failed later request reports "could not be created" after earlier tasks were created and archived**
    - File: `web/src/app/features/mail/list/mail.service.ts:205` (used by `mail-list-store.ts:496`)
    - Category: Correctness
    - Problem: With more than 100 targets, `MailService.toNextActions` sends the ids in sequential requests of up to 100. If a later request fails, the method throws and drops the results of the earlier requests. The server has already created those tasks and archived those mails. `MailListStore.toNextActions` then does not remove them from the list, and the page shows "The next action could not be created". A retry would create duplicate tasks for the mails already processed.
    - Why it matters: The UI misreports what the server did. A retry creates duplicate Next actions.
    - Suggested fix: Return partial results together with the error (or process each request's results as they arrive): remove the archived mails, report how many were created, and show the error only for the mails of the request that failed.

## P3 Low

None.

## Review conclusion

Material issues found; fix before completing the task.
