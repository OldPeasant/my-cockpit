---
title: Android mail: search box with 'search on server' option
status: in_progress
owner: dell26/agent-1
deps: [and-mail-list-read,mail-search]
touches: [android/app/src/main/java/ch/sonensei/cockpit/mail/list,android/app/src/test/java/ch/sonensei/cockpit/mail,android/app/src/main/res/values/strings_mail.xml,android/app/src/main/res/values-de/strings_mail.xml]
prio: 2
lease_until: 2026-11-01T14:08:11Z
---
## Goal
Android counterpart of web-mail-search. Use `GET /api/v1/mail/search` (folderId/accountId scope, `server=true`
for IMAP SEARCH, `server.failures`, `hasMore`); server unchanged.

## Acceptance criteria
- [ ] The mail list has a search action; submitting a query searches the current folder (or all folders from the unified inbox) and shows results in the usual message rows; tapping one opens the read view; closing search returns to the folder list
- [ ] A "Search on server" option repeats the search with `server=true`; accounts in `server.failures` are named in a non-blocking message; `hasMore` shows a hint that only the newest results are listed
- [ ] Loading, empty and error states follow the mail list's existing patterns
- [ ] All new UI strings exist in English and German (`res/values`, `res/values-de`)
- [ ] Unit tests (ViewModel, plus Compose UI tests where the project has them for similar screens) cover the criteria above; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes

## Notes
Requested by the owner (2026-11-01) after trying the Android app on the phone.
