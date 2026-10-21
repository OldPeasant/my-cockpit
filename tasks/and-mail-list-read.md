---
title: Android mail list and reading
status: in_progress
owner: dell26/agent-2
deps: [and-login,mail-actions-api,mail-gtd]
touches: [android/app/src/main/java/ch/sonensei/cockpit/mail/list,android/app/src/test/java/ch/sonensei/cockpit/mail/list,android/app/src/main/java/ch/sonensei/cockpit/mail/read,android/app/src/test/java/ch/sonensei/cockpit/mail/read]
prio: 2
lease_until: 2026-10-21T16:56:40Z
---
## Goal
Mail on the phone (SPEC.md §9.2 item 5).

## Acceptance criteria
- [ ] Unified inbox, folders, swipe actions (archive/delete), read view with sanitized HTML in a WebView (JS disabled, no file access), to-task/waiting
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.list.*'` passes, covering swipe actions (Robolectric/Compose UI tests for screens); `mail.read` WebView settings

## Notes
- ASSUMPTION (2026-10-21 18:16+0200 by dell26/agent-2): Android mail uses hand-written Retrofit DTOs (mail/list/MailApi.kt) instead of the generated mail models: api/openapi.yaml marks nullable mail fields (nextCursor on the last page, subject, dates, body.html/text) as non-null, so the generated client would fail to parse real answers. Fixing the server's schema annotations is outside this task.
- ASSUMPTION (2026-10-21 18:16+0200 by dell26/agent-2): Android read view WebView blocks all network loads (blockNetworkLoads + CSP default-src 'none'): remote images stay blocked (no 'load images' toggle on Android), and inline cid: images are not shown because the WebView cannot authenticate attachment downloads. Attachments are listed by name only (no download on Android yet).
- ASSUMPTION (2026-10-21 18:17+0200 by dell26/agent-2): Swipe right = archive, swipe left = delete (Gmail-like); archive swipe is disabled in an Archive folder. A swiped mail disappears at once and is reinserted with an error message if the server refuses; no undo snackbar. Opening a mail marks it READ on the server (like the web read view). Strings live in a new res/values/strings_mail.xml (outside the task's touches, feature-specific file).
