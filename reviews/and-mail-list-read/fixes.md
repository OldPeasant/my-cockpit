# Fix Report

Date: 2026-10-21

Task: and-mail-list-read - Android mail list and reading

Scope: findings CR-1 to CR-6 of `reviews/and-mail-list-read/review.md`, all approved as in scope by the parent worker. Changes stay in `android/app/src/{main,test}/java/ch/sonensei/cockpit/mail/{list,read}`. Fix commit: 7b14fdfd.

## Fixed findings

### CR-1 - A failed swipe puts the mail back into a different mailbox, or a reload brings back an archived mail

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailListViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailListViewModelEdgeCasesTest.kt`

Problem:
`act()` re-inserted a refused mail into whatever list was shown when the answer came back, even after another mailbox had been selected. A reload that overtook a successful call could also bring the mail back.

Fix:
`act()` records the mailbox it acted in. A refused mail is re-inserted only if that mailbox is still shown and the mail is not already in the list. The failure message is still shown. On success the id is filtered out of the current list again. The committed test "a refused swipe does not bring the mail into a mailbox selected meanwhile" now passes. New test: "an archived mail stays gone even when a reload that started before brought it back".

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*'` - Pass (MailListViewModelEdgeCasesTest 15/15)

### CR-2 - Every opened mail leaves a view model (with its full HTML body) on the Mail tab's back-stack entry

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadScreen.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailRouteInAppTest.kt`

Problem:
`hiltViewModel(key = "mail-read:$id")` kept one view model per opened mail on the Mail back-stack entry. Reopening a mail showed the first, possibly stale, content.

Fix:
`MailReadRoute` now uses one unkeyed `hiltViewModel()`. `open(id)` resets the state for a new id. A new `close()` drops the mail. Every close path (toolbar back, system back, and the close after archive/delete) calls `close()`, so reopening a mail loads it again. Changing the configuration does not close the mail, so it is not reloaded then. A session counter drops answers that arrive after the mail was closed or another mail was opened, so a late archive cannot close the next mail. The system-back `BackHandler` moved from `MailRoute` into `MailReadRoute` so that it also goes through `close()`. New tests: three view-model tests (reload after close, another mail replaces the shown one, late answers dropped) and the in-app test "a mail opened again after closing it is loaded again, another mail shows its own content".

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*'` - Pass (MailReadViewModelTest 14/14, MailRouteInAppTest 4/4, MailReadScreenTest 17/17)

### CR-4 - Infinite scroll can stop: the load-more trigger fires only when "near end" changes

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailListScreenStatesTest.kt`

Problem:
`snapshotFlow { nearEnd }` fired only on a false-to-true change, so a `loadMore()` that did nothing because a load was running, or that failed, was never retried.

Fix:
The snapshot now emits `nearEnd && nextCursor != null && !loading && !loadingMore`, so the trigger fires again whenever a page can be loaded while the list is near its end. New test: "near the end the next page is asked for again once a load ends".

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*'` - Pass (MailListScreenStatesTest 10/10)

### CR-5 - Every resume reloads only the first page and drops the pages already loaded

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailListViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailListViewModelEdgeCasesTest.kt`

Problem:
`load()` replaced the list with page 1 on every resume, which lost the later pages and the scroll position.

Fix:
I chose to merge the first page. Skipping the refresh while a mail is open would only cover the browser case, not an app switch from the list. When the same loaded mailbox is reloaded and the new first page ends with a mail that is already in the list, the result is the new first page plus the loaded mails after that mail, with the existing cursor. In every other case the list is replaced as before: a different mailbox, a first page that is the last page, or a first page whose last mail is not in the list (for example, a lot of new mail), which avoids leaving a gap. New tests: merge keeps later pages and the cursor, a first page that does not reach the loaded mails replaces them, and a refresh that reaches the end replaces the list.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*'` - Pass

### CR-6 - Links from mail are opened in other apps without a user gesture check

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailBodyView.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailWebViewTest.kt`

Problem:
`MailWebViewClient` passed every http/https/mailto navigation to `onLink`, including navigations the page started by itself and sub-frame navigations.

Fix:
`onLink` is called only when `request.hasGesture() && request.isForMainFrame`. The client still returns true for every navigation, so nothing ever loads inside the view. New test: "links the page follows by itself or in a frame are dropped".

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*'` - Pass (MailWebViewTest 4/4, MailBodyViewTest 2/2)

### CR-3 - Read view model, repository mapping and read screen have no tests

Files changed:
- None. The tests had already been added in f01eb4b9.

Problem:
When the review was written, there were no tests for the read view model, `mailCall` mapping, DTO mappers, `folderOrder`/`depth` or `MailReadContent`.

Fix:
I checked commit f01eb4b9 against every case the finding lists:
- `MailReadViewModelTest` covers loading OK and mark-read, 404 as GONE, other failures, offline and retry, GTD success and failure notices, busy preventing a double submit, and act success setting `removed`.
- `MailRepositoryTest` and `MailReadRepositoryTest` cover 4xx as Failed, 5xx or IOException as Offline, a malformed UUID or unreadable answer as Failed, and DTO parsing with null subject, date and body.
- `MailModelsTest` covers `folderOrder` and `depth`.
- `MailReadScreenTest` covers HTML versus text body, error and retry, and notices.

Nothing was missing. The new CR-2 tests extend the read view model coverage.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*'` - Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintFormat` - formatting applied, no remaining issues
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*' lintDebug` - Pass. 12 mail test classes, 102 tests, 0 failures, 0 skipped. Per class: MailListViewModelEdgeCasesTest 15, MailListViewModelTest 6, MailListScreenTest 8, MailListScreenStatesTest 10, MailModelsTest 9, MailRepositoryTest 8, MailRouteInAppTest 4, MailReadViewModelTest 14, MailReadScreenTest 17, MailReadRepositoryTest 5, MailWebViewTest 4, MailBodyViewTest 2.

Full test suite:
- `sh scripts/check.sh` (server, web and android lint plus unit tests) - Pass ("all checks passed")

Lint/type-check/build:
- ktlintCheck and lintDebug - Pass (included in the command above)

`.taskcheck`:
- Pass (`sh scripts/check.sh`)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.

---

# Fix Report - second round

Date: 2026-10-21

Task: and-mail-list-read - Android mail list and reading

Scope: findings CR-1 (P2) and CR-2 (P3) of the second review in `reviews/and-mail-list-read/review.md` (reviewed revision 7b14fdfd), both approved as in scope by the parent worker. Changes stay in `android/app/src/{main,test}/java/ch/sonensei/cockpit/mail/{list,read}`. Code base for this round: 0a392ff4.

## Fixed findings

### CR-1 (second review) - Infinite scroll retries a failed page in a tight loop with no backoff

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailListScreenStatesTest.kt`

Problem:
The load-more trigger emitted `nearEnd && nextCursor != null && !loading && !loadingMore`. A failed `loadMore()` (or a failed `refresh()`) resets `loadingMore`/`loading` to false and keeps the cursor, so the trigger fired again immediately. While near the end of the list, offline or with a failing server, it sent a request about once per frame.

Fix:
The trigger is now also false while the list shows a load failure (`message` is `OFFLINE` or `FAILED`). A failed page or refresh is not requested again automatically. The existing Retry button calls `refresh()`. A successful load clears `message`, and the trigger then fires again if the list is still near its end. `ARCHIVE_FAILED`/`DELETE_FAILED` do not block paging. I left the view model unchanged: it already clears `message` on every successful load, so no new flag was needed. Test changes:
- In "near the end the next page is asked for again once a load ends", the second step used to model a failed page that was asked for again. It now models a page that loaded and has a next cursor. The finding showed the old expectation was wrong.
- New regression test "a failed page is not asked for again until a retry succeeds". After an OFFLINE failure, then a FAILED one, no new request is made, even after 1 s of frames. After a successful refresh, paging resumes.
- New regression test "a failed refresh near the end does not ask for the next page".
- New test "a failed archive does not stop paging".

With the production change temporarily reverted, the two regression tests fail. With it restored, they pass.

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.list.MailListScreenStatesTest' --tests 'ch.sonensei.cockpit.mail.read.MailReadViewModelTest'` with the fix reverted - 3 expected failures (the two new list tests and the new read test)
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*' lintDebug` - Pass (MailListScreenStatesTest 15/15)

### CR-2 (second review) - A mail closed before it loaded is still marked read on the server

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadViewModelTest.kt`

Problem:
`load()` called `mail.markRead(...)` for an unread mail even when the session had changed: the mail had been closed, or another mail opened, before the load finished. The server then marked a mail read that the user never saw, and the list kept its unread dot.

Fix:
`markRead` runs only when `opened == session`, the same check `updateFor` makes. New test "a mail closed or replaced before it loaded is not marked read" covers two cases: a mail closed before its load completes gets no `markRead`, and a mail replaced by another before it loads gets no `markRead` while the mail shown gets exactly one. The test fails with the fix reverted.

Validation:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*' lintDebug` - Pass (MailReadViewModelTest 17/17)

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintFormat` - no changes needed
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*' lintDebug` - BUILD SUCCESSFUL. 12 mail test classes, 116 tests, 0 failures, 0 skipped. Per class: MailListViewModelEdgeCasesTest 20, MailListViewModelTest 6, MailListScreenTest 8, MailListScreenStatesTest 15, MailModelsTest 9, MailRepositoryTest 8, MailRouteInAppTest 4, MailReadViewModelTest 17, MailReadScreenTest 17, MailReadRepositoryTest 5, MailWebViewTest 5, MailBodyViewTest 2.

Full test suite:
- `sh scripts/check.sh` (server, web and android lint plus unit tests) - Pass ("all checks passed")

Lint/type-check/build:
- ktlintCheck and lintDebug - Pass (included in the command above)

`.taskcheck`:
- Pass (`sh scripts/check.sh`)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
