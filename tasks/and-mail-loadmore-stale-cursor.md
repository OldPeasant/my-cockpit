---
title: Android: drop a stale load-more answer after a quiet first-page reload replaced the list (New, Mail list)
status: in_progress
owner: dell26/agent-1
deps: [and-mail-live-refresh]
touches: [android/app/src/main/java/ch/sonensei/cockpit/newitems,android/app/src/main/java/ch/sonensei/cockpit/mail/list,android/app/src/test/java/ch/sonensei/cockpit/newitems,android/app/src/test/java/ch/sonensei/cockpit/mail/list]
prio: 2
lease_until: 2026-11-14T20:24:45Z
---
## Goal
Follow-up of CR-4 in reviews/and-mail-live-refresh/review.md. Since mail change events reload the first page quietly
without cancelling paging, a `loadMore()` answer can arrive after a reload that replaced the list and its cursor (a burst
of at least one page of new mail). It is then appended anyway, so the mails of the old first page are missing from the
middle of the list until the next manual refresh (`NewViewModel.loadMore`, `MailListViewModel.loadMore`).

## Acceptance criteria
- [ ] A load-more answer is applied only while the list's cursor is still the one the request used; otherwise it only
      ends the loading-more state (New view and Mail list).
- [ ] Unit tests reproduce the race (full page of new mail during a pending load more) for both view models.
- [ ] `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Rare (needs a full page of new mail at the wrong moment); nothing is lost on the server.
