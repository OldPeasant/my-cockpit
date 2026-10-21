# Code Review

Date: 2026-10-21

Task: and-mail-list-read - Android mail list and reading

Scope: all committed changes of the task under `android/` at the final revision (`git diff dc3200563744d28fa732c050b24ca4637748fa5c..58f705653f136cb1837c0fbe9aa36ea2cbb26119 -- android`), commits e0fca56f, 9e314026, b4461438, 7c007355, 0e9bf534, ae0c8518, 636fae7a, 8d16eb56, 58f70565. This is the fourth review. The production code is the same as in the third review (reviewed there under its pre-rebase hash 71c09b02, now 8d16eb56). Commit 58f70565 changes only tests: it adds `MailListPagingRetryTest.kt` and renames one test in `MailListViewModelEdgeCasesTest.kt`. I reviewed the whole task diff again, not only that commit. Uncommitted changes: none.

Requirements:
- tasks/and-mail-list-read.md: acceptance criteria and the recorded assumptions (hand-written DTOs; the WebView blocks all network loads; swipe right archives, swipe left deletes; opening a mail marks it read; strings in `strings_mail.xml`)
- SPEC.md §3 "HTML email is untrusted", §9.2 item 5
- docs/ARCHITECTURE.md, AGENTS.md project notes
- api/openapi.yaml (mail endpoints and schemas, used to check the client against the server's contract)

Git range:
- Branch: main
- HEAD: 58f705653f136cb1837c0fbe9aa36ea2cbb26119
- Base/start: dc3200563744d28fa732c050b24ca4637748fa5c (merge-base with origin/main)
- Commits reviewed: 9

Summary: 0 P0, 0 P1, 0 P2, 1 P3

What I checked:
- Criterion 1. The unified Inbox (`Mailbox.Unified` sends neither `accountId` nor `folderId`, and the server treats that as the unified Inbox, see openapi `listMailMessages`). Folders: the picker lists each account's selectable folders, special folders first, nested by depth. Swipe right archives and swipe left deletes. Both are also offered as accessibility actions, and archive is disabled in an Archive folder. The read view loads sanitized HTML into a locked-down WebView (`MailBodyView.kt:23-39`): JavaScript off, file and content access off, network loads and images blocked, no DOM storage, no multiple windows, a CSP of `default-src 'none'`. Links open only in another app, only after a user gesture in the main frame, and only for http, https or mailto. "→ Inbox task" and "→ Waiting For" are in the toolbar.
- Integration. Paths, methods, query parameters and field names in `MailApi.kt` match api/openapi.yaml (`/messages`, `/messages/actions` with `READ`/`ARCHIVE`/`DELETE`, `/messages/{id}`, `/to-task`, `/to-waiting`, the folder and account lists). Retrofit uses the generated `Serializer.kotlinxSerializationJson`, and `MailRepositoryTest` reads server-shaped JSON that has extra fields through it, so unknown fields do not break parsing.
- Races. An older load cannot overwrite a newer one: `loadJob` is cancelled and `loadMore` is guarded. A swipe that fails is reinserted only into the same mailbox, and only when a reload has not already brought the mail back. The read view's session counter drops late answers. A failed page is not requested again in a loop (`MailScreen.kt:204-205`).
- Security. No mail content or secrets are logged. `MailHttpFailure` carries only the HTTP status. Hilt detection through `GeneratedComponentManagerHolder` is the convention the other feature screens use.
- New tests in 58f70565. `MailListPagingRetryTest` uses the real `MailListViewModel` with a mocked repository and the real `MailListContent`. I traced both tests against the code. In test 1, the short list triggers exactly one request for the `c1` page, and the failure leaves `OFFLINE` with the cursor kept. Retry then reloads the first page: `mergeFirstPage` returns the page's own items and `c1`, because nothing is loaded after the page's last mail. The failure flag clears, and paging requests `c1` a second time, which ends with mails 1-4 and no cursor. That matches the assertions. Test 2 checks that a Retry that fails again (`OFFLINE`) does not request the page again (exactly one `c1` call). The test is meaningful: if `!failed` in `canLoadMore` were removed, the page would be requested again. One small point: the stub `MailResult.Failed(503)` cannot come from the real repository, because `mailCall` maps 5xx to `Offline`. The test still exercises the `FAILED` branch correctly, so I did not report it. The rename in `MailListViewModelEdgeCasesTest` makes the test name match the behavior (no automatic retry).

Validation (run in this review on revision 58f70565, clean working tree):
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*'` -> BUILD SUCCESSFUL. The result files written at 19:11 show 13 mail test classes, 118 tests, 0 failures, 0 errors, 0 skipped. That includes `MailListPagingRetryTest` (2), `MailListScreenTest` (8, with the swipe tests), `MailListScreenStatesTest` (15), `MailListViewModelEdgeCasesTest` (20), `MailWebViewTest` (5), `MailBodyViewTest` (2), `MailReadViewModelTest` (17) and `MailReadScreenTest` (17).
- `python tasks.py verify and-mail-list-read` shows both criteria still unticked. The parent worker must record evidence for them; that is expected at this stage and is not a finding.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | An archive or delete that succeeds after the read view closed leaves the mail in the list**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadViewModel.kt:113`, `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadScreen.kt:73-78`
    - Category: Correctness
    - Problem: `act()` reports success only through `updateFor(opened) { it.copy(removed = true) }`, and `MailReadRoute` calls `onRemoved(id)` only when `state.removed` becomes true. Suppose the user taps Archive or Delete and then presses back before the answer arrives. `close()` increments `session`, so the successful answer is dropped and `onRemoved` never runs. The server has moved the mail, but `MailListViewModel` still shows it until the next refresh (the next resume or a Retry). The test `answers for a mail already closed are dropped` (`MailReadViewModelTest.kt:358-391`) confirms that the success is discarded, but it checks only that the next mail is not closed. It does not check what happens to the list.
    - Why it matters: this is a write the server committed but the UI cancelled. The list shows a mail that is no longer in the mailbox. A swipe on it then sends a second archive or delete for a mail that has already moved, and the user may get a misleading "could not archive/delete" message. Impact is low: it needs a back press during the request, and the next refresh fixes it.
    - Suggested fix: report the result to the list owner whatever the session. For example, keep dropping the read view's state update for an old session, but tell the list about a successful removal anyway (a callback or a shared event that does not go through the session check). Or have `MailRoute` remove the mail when an action it started succeeds. Then add a test for "archive succeeds after close -> list no longer shows the mail".

## Review conclusion

No material issues found.
