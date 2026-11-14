---
title: Android: drop a stale load-more answer after a quiet first-page reload replaced the list (New, Mail list)
status: in_progress
owner: dell26/agent-1
deps: [and-mail-live-refresh]
touches: [android/app/src/main/java/ch/sonensei/cockpit/newitems,android/app/src/main/java/ch/sonensei/cockpit/mail/list,android/app/src/test/java/ch/sonensei/cockpit/newitems,android/app/src/test/java/ch/sonensei/cockpit/mail/list]
prio: 2
lease_until: 2026-11-14T20:36:58Z
---
## Goal
Follow-up of CR-4 in reviews/and-mail-live-refresh/review.md. Since mail change events reload the first page quietly
without cancelling paging, a `loadMore()` answer can arrive after a reload that replaced the list and its cursor (a burst
of at least one page of new mail). It is then appended anyway, so the mails of the old first page are missing from the
middle of the list until the next manual refresh (`NewViewModel.loadMore`, `MailListViewModel.loadMore`).

## Acceptance criteria
- [x] A load-more answer is applied only while the list's cursor is still the one the request used; otherwise it only
      ends the loading-more state (New view and Mail list).
  - verified (2026-11-14 21:18+0100 by dell26/agent-1): Guard in NewViewModel.loadMore / MailListViewModel.loadMore (cursor+mailbox compare); ./gradlew testDebugUnitTest --tests '*NewMailLiveRefreshTest' --tests '*MailListLiveRefreshTest' -> pass, incl. tests that a kept cursor still appends and a stale answer only ends loadingMore; code review 0 P0-P2
- [x] Unit tests reproduce the race (full page of new mail during a pending load more) for both view models.
  - verified (2026-11-14 21:18+0100 by dell26/agent-1): New race tests (full page of new mail during pending load more) in NewMailLiveRefreshTest + MailListLiveRefreshTest: pass with fix; with production change stashed the same run gives 3 AssertionErrors
- [x] `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass
  - verified (2026-11-14 21:18+0100 by dell26/agent-1): cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug -> BUILD OK; sh scripts/check.sh -> all checks passed

## Notes
Rare (needs a full page of new mail at the wrong moment); nothing is lost on the server.
- ASSUMPTION (2026-11-14 21:08+0100 by dell26/agent-1): A stale load-more answer that failed or was offline is dropped silently (no banner/message): its error concerns a list that no longer exists, and the replaced list's cursor stays for the next attempt.
