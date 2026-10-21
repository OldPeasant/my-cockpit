---
title: Android mail list and reading
status: in_progress
owner: dell26/agent-2
deps: [and-login,mail-actions-api,mail-gtd]
touches: [android/app/src/main/java/ch/sonensei/cockpit/mail/list,android/app/src/test/java/ch/sonensei/cockpit/mail/list,android/app/src/main/java/ch/sonensei/cockpit/mail/read,android/app/src/test/java/ch/sonensei/cockpit/mail/read]
prio: 2
lease_until: 2026-10-21T16:36:31Z
---
## Goal
Mail on the phone (SPEC.md §9.2 item 5).

## Acceptance criteria
- [ ] Unified inbox, folders, swipe actions (archive/delete), read view with sanitized HTML in a WebView (JS disabled, no file access), to-task/waiting
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.list.*'` passes, covering swipe actions (Robolectric/Compose UI tests for screens); `mail.read` WebView settings

## Notes
