---
title: Android mail: search box with 'search on server' option
status: in_progress
owner: dell26/agent-1
deps: [and-mail-list-read,mail-search]
touches: [android/app/src/main/java/ch/sonensei/cockpit/mail/list,android/app/src/test/java/ch/sonensei/cockpit/mail,android/app/src/main/res/values/strings_mail.xml,android/app/src/main/res/values-de/strings_mail.xml]
prio: 2
lease_until: 2026-11-01T14:38:59Z
---
## Goal
Android counterpart of web-mail-search. Use `GET /api/v1/mail/search` (folderId/accountId scope, `server=true`
for IMAP SEARCH, `server.failures`, `hasMore`); server unchanged.

## Acceptance criteria
- [x] The mail list has a search action; submitting a query searches the current folder (or all folders from the unified inbox) and shows results in the usual message rows; tapping one opens the read view; closing search returns to the folder list
  - verified (2026-11-01 15:14+0100 by dell26/agent-1): cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*' (Robolectric, rev 9535590b) -> pass: MailSearchRouteInAppTest (Hilt MainActivity: search action, submit, results rows, tap opens read view, back to results, back/X to mailbox), MailSearchViewModelTest (folder scope vs unified all folders), MailSearchRepositoryTest (MockWebServer: q/accountId/folderId on /api/v1/mail/search)
- [x] A "Search on server" option repeats the search with `server=true`; accounts in `server.failures` are named in a non-blocking message; `hasMore` shows a hint that only the newest results are listed
  - verified (2026-11-01 15:14+0100 by dell26/agent-1): cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.list.MailSearch*' (rev 9535590b) -> 48 pass: server=true sent (MailSearchRepositoryTest), Search on server button repeats with server=true keeping cached results (ViewModel + Screen tests), failed account named by label in non-blocking note with results still shown, hasMore hint (MailSearchScreenTest, MailSearchRouteInAppTest)
- [x] Loading, empty and error states follow the mail list's existing patterns
  - verified (2026-11-01 15:14+0100 by dell26/agent-1): MailSearchScreenTest/MailSearchScreenEdgeCasesTest (Robolectric, rev 9535590b) -> pass: loading = LinearProgressIndicator like the list, empty text 'No mail found.', OFFLINE/FAILED via the list's ListMessage + Retry (retrySearch tested in MailSearchViewModelTest)
- [x] All new UI strings exist in English and German (`res/values`, `res/values-de`)
  - verified (2026-11-01 15:14+0100 by dell26/agent-1): 10 mail_search_* keys in android/app/src/main/res/values/strings_mail.xml and values-de/strings_mail.xml; ./gradlew lintDebug (MissingTranslation is an error) -> pass; MailSearchScreenTest 'the search speaks German' (@Config qualifiers=de) -> pass
- [x] Unit tests (ViewModel, plus Compose UI tests where the project has them for similar screens) cover the criteria above; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes
  - verified (2026-11-01 15:14+0100 by dell26/agent-1): cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport (rev 9535590b) -> exit 0, 2361 tests 0 failed; new: MailSearchViewModelTest+EdgeCases, MailSearchScreenTest+EdgeCases, MailSearchRouteInAppTest, MailSearchRepositoryTest (48 tests); MailSearchContent.kt 98.9% lines, MailListViewModel.kt 100% lines

## Notes
Requested by the owner (2026-11-01) after trying the Android app on the phone.
- ASSUMPTION (2026-11-01 14:53+0100 by dell26/agent-1): Search results are shown in the usual mail rows but without the swipe archive/delete actions (the list's optimistic swipe logic works on the mailbox, not on a one-page search result); opening a result, then archiving/deleting it in the read view removes it from the results.
- ASSUMPTION (2026-11-01 14:53+0100 by dell26/agent-1): The 'only the newest results are listed' hint shows for hasMore and also for server.truncated (a folder had more server matches than the limit), since both mean older matches are missing.
- ASSUMPTION (2026-11-01 14:53+0100 by dell26/agent-1): Android search: one page of up to 50 results (server default limit), no paging; the search box replaces the mailbox picker while open, Back or the X closes it; the scope is the mailbox shown when the search was opened (folder -> folderId+accountId, unified inbox -> all folders of all accounts).
