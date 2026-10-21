---
title: Android mail list and reading
status: in_progress
owner: dell26/agent-1
deps: [and-login,mail-actions-api,mail-gtd]
touches: [android/app/src/main/java/ch/sonensei/cockpit/mail/list,android/app/src/test/java/ch/sonensei/cockpit/mail/list,android/app/src/main/java/ch/sonensei/cockpit/mail/read,android/app/src/test/java/ch/sonensei/cockpit/mail/read]
prio: 2
lease_until: 2026-10-21T18:27:23Z
extended: 
releases: 1
---
## Goal
Mail on the phone (SPEC.md §9.2 item 5).

## Acceptance criteria
- [x] Unified inbox, folders, swipe actions (archive/delete), read view with sanitized HTML in a WebView (JS disabled, no file access), to-task/waiting
  - verified (2026-10-21 20:02+0200 by dell26/agent-1): cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.list.*' --tests 'ch.sonensei.cockpit.mail.read.*' (Robolectric) -> BUILD SUCCESSFUL, 13 classes/118 tests 0 failures: MailListScreenTest (unified inbox, folder picker, swipe archive/delete), MailRouteInAppTest, MailReadScreenTest (to-task/waiting), MailWebViewTest/MailBodyViewTest (sanitized HTML, JS off, no file access); on-device rendering not run (no device)
- [x] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.list.*'` passes, covering swipe actions (Robolectric/Compose UI tests for screens); `mail.read` WebView settings
  - verified (2026-10-21 20:02+0200 by dell26/agent-1): cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.list.*' --tests 'ch.sonensei.cockpit.mail.read.*' -> pass, 118 tests 0 failures incl. swipe tests in MailListScreenTest/MailListViewModelEdgeCasesTest and WebView settings in MailWebViewTest (5) + MailBodyViewTest (2)

## Notes
- ASSUMPTION (2026-10-21 18:16+0200 by dell26/agent-2): Android mail uses hand-written Retrofit DTOs (mail/list/MailApi.kt) instead of the generated mail models: api/openapi.yaml marks nullable mail fields (nextCursor on the last page, subject, dates, body.html/text) as non-null, so the generated client would fail to parse real answers. Fixing the server's schema annotations is outside this task.
- ASSUMPTION (2026-10-21 18:16+0200 by dell26/agent-2): Android read view WebView blocks all network loads (blockNetworkLoads + CSP default-src 'none'): remote images stay blocked (no 'load images' toggle on Android), and inline cid: images are not shown because the WebView cannot authenticate attachment downloads. Attachments are listed by name only (no download on Android yet).
- ASSUMPTION (2026-10-21 18:17+0200 by dell26/agent-2): Swipe right = archive, swipe left = delete (Gmail-like); archive swipe is disabled in an Archive folder. A swiped mail disappears at once and is reinserted with an error message if the server refuses; no undo snackbar. Opening a mail marks it READ on the server (like the web read view). Strings live in a new res/values/strings_mail.xml (outside the task's touches, feature-specific file).
- extended by dell26/agent-2: Implementation complete; third code review (71c09b02) found 0 issues; .taskcheck passes at 6d827625. Left: commit 6d827625 added test-only changes (MailListPagingRetryTest, renamed test) after the review, so re-run test-writer + code-reviewer on the final revision, then tasks.py verify criteria 1-2 (run ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.list.*'), write reviews/and-mail-list-read/quality.json, tasks.py done.
- release by dell26/agent-2: Implementation complete; 4th code review (0 P0-P2, 1 P3) and final test report (118 tests, all pass) are committed in reviews/and-mail-list-read/, written for 58f70565, whose android/ code is identical to HEAD 7c8c4a17 (rebased, so the evidence is still fresh). .taskcheck passes at 7c8c4a17. Left: (1) run cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.list.*' (plus mail.read) and tasks.py verify criteria 1-2; (2) write reviews/and-mail-list-read/quality.json (docs/QUALITY.md, reviewed_revision = final hash, record the P3 disposition and fixes.md from the earlier round); (3) commit, then tasks.py done.
