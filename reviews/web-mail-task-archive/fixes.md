# Fix Report

Date: 2026-11-14

Task: web-mail-task-archive - Web: mail → Next action & archive (read view, New view, mail list)

Scope: findings CR-1, CR-3, CR-4 and CR-5 of reviews/web-mail-task-archive/review.md. CR-2 (missing tests) is handled by the test-writer in parallel. No spec files and no mail-testing.ts were edited by the fixer.

## Fixed findings

### CR-1 — Archive-failure errors are hidden by the "N next actions created" message for several mails

Files changed:
- `web/src/app/features/mail/list/mail-list-page.ts`
- `web/src/app/features/mail/list/mail-next-action.ts`
- `web/src/locale/messages.xlf`, `web/src/locale/messages.de.xlf`

Problem:
For several mails the page opened one error snack bar per mail not archived and then the success snack bar. MatSnackBar shows one snack bar at a time, so the errors disappeared at once.

Fix:
For several mails the page now opens exactly one snack bar. If every mail was archived (and every task created), it shows the "N next actions created" snack bar with the Next Actions action, as before. Otherwise it shows a single error from the new `nextActionsIncomplete(...)`, for example `2 next actions created. Not archived: “Lunch”.`. When a later request failed (CR-5), the error also adds `N mails got no next action: reason`. The single-mail path did not change: an archive error, then the task detail opens. New strings: `mail.nextAction.createdCount`, `mail.nextAction.notArchived`, `mail.nextAction.notCreated` (English extracted, German added). `i18n:extract` added only these units and moved line numbers in the changed files. Nothing from other features changed.

Validation:
- `npm run -s lint` (web) — Pass
- `npx ng test --watch=false --include src/app/features/mail --include src/app/features/new` — 968 passed, 1 failed. The failing test is the test-writer's new `mail-list-page.spec.ts` "keeps a mail whose archive failed in the list, with an error naming it" (line 1806). It asserts the pre-fix behavior that CR-1 reports as the defect: a per-mail error followed by the success snack bar. It must now expect a single error, `2 next actions created. Not archived: “Lunch”.`, and no success snack bar. The fixer was told not to edit spec files, so this assertion is left for the test-writer.

### CR-3 — Read view: other mail actions can run while "→ Next action & archive" is in flight

Files changed:
- `web/src/app/features/mail/read/mail-read-page.ts`
- `web/src/app/features/mail/read/mail-read-page.html`

Problem:
`canAct()` ignored `makingNextAction()`, so archive, spam, delete, move, mark unread and star could overlap with the to-tasks request on the same message.

Fix:
`canAct()` now also returns false while `makingNextAction()` is set. That guards every triage keyboard shortcut and method, and `toNextAction()` itself, which already calls `canAct()`. The redundant separate check was removed. The six manage buttons (Mark unread, Star, Move, Archive, Spam, Delete) are disabled during the request too, the same way they already are while `triaging()` is set.

Validation:
- read-view specs within the focused run above — Pass (all `mail-read-page.spec.ts` tests passed)

### CR-4 — Threaded mail list: only the conversation's shown message is archived, so the row comes back

Files changed:
- `web/src/app/features/mail/list/mail-list-store.ts`

Problem:
In threaded mode, the to-tasks call archived only the conversation's listed message. The row was removed optimistically, but the conversation's other messages stayed in the folder, so the next refresh brought the row back.

Fix (option chosen):
The store still sends only the listed message id, so each row gets one task, as the recorded assumption says. When that message was archived, the row is removed, and the new private `archiveRestOfConversations` then archives the conversation's other messages in the shown folder. It uses the same `messageIds()` thread lookup and the same chunked `send('ARCHIVE', ...)` that the Archive action uses. This runs only in threaded scope, only for rows with `threadSize > 1`, and while `running` is still counted, so live refreshes wait for it. If the lookup or that archive fails, `refreshQueued` is set and `settle()` refetches the list. The row then comes back with the messages that are really still there. This option was chosen over "remove the row only for single-message conversations" because it matches what Archive does on a conversation row and what the criterion says ("the mails leave it"). The parent worker should record this decision with `tasks.py assume`.

Validation:
- store specs within the focused run above — Pass. No threaded to-tasks test exists yet; the test-writer may add one: threaded row with threadSize 3, `mail.threads` set, listed message archived, the expected `apply('ARCHIVE', ...)` for the other ids, and a refetch on failure.

### CR-5 — A failed later request reports "could not be created" after earlier tasks were created and archived

Files changed:
- `web/src/app/features/mail/list/mail.service.ts`
- `web/src/app/features/mail/list/mail-list-store.ts`
- `web/src/app/features/mail/list/mail-list-page.ts`

Problem:
With more than 100 ids, a failed later request threw away the results of the earlier requests, although their tasks existed and their mails were archived. The list kept those mails, the user saw "could not be created", and a retry would create duplicate tasks.

Fix:
`MailService.toNextActions` keeps its return type, so existing callers and test doubles still work. A failed first request still rejects with its own error (no task was created). A failed later request rejects with the new `PartialNextActionsError`, which carries the earlier results and the failure. `MailListStore.toNextActions` catches that error, handles the earlier results like a success (archived mails leave the list, the CR-4 conversation archive runs) and then throws `NextActionsPartlyFailedError` with `outcome`, the targets that got a task, and `failed`, the targets that did not and stay in the list. The page reports this in the single CR-1 error: how many tasks were created, which mails were not archived, how many mails got no task, and the reason. The guidance suggested returning `{ results, error }`. A typed error was used instead so that the success contract stays the same, because the test-writer is writing specs against it right now. The read view and the New view send one id only, so they never get a partial result.

Validation:
- service and store specs within the focused run above — Pass. A partial-failure test (201 ids, third request fails) does not exist yet; the test-writer may add one.

## Not fixed

None. CR-2 is outside this fixer's assignment (test-writer).

## Validation summary

Focused tests:
- `npx ng test --watch=false --include src/app/features/mail --include src/app/features/new` — 968 passed, 1 failed (the CR-1 pre-fix assertion in the test-writer's `mail-list-page.spec.ts`, see CR-1)

Full test suite:
- Not run (the test-writer is changing spec files in parallel; the focused run covers every changed production file)

Lint/type-check/build:
- `npm run -s lint` — Pass
- `npx ng build` — Pass (only the existing initial-bundle budget warning)

`.taskcheck`:
- Not run (includes Gradle; the parent worker runs it)

Remaining unresolved findings:
0 (one test-writer assertion must be updated to the fixed CR-1 behavior)

## Final status

Fixes completed, but validation still fails: one test-writer spec asserts the pre-fix CR-1 behavior and needs to be updated.
