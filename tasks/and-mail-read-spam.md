---
title: Android mail read view: Spam button
status: todo
owner:
deps: [and-mail-list-read,mail-account-label-email]
touches: [android/app/src/main/java/ch/sonensei/cockpit/mail/read,android/app/src/test/java/ch/sonensei/cockpit/mail/read,android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailModels.kt]
prio: 2
---
## Goal
The Android mail read view gets a Spam button next to Archive and Delete, matching the web read view
(web-mail-read-archive-spam). Requested by the owner (2026-10-30).

## Acceptance criteria
- [ ] `MailListAction` gets `SPAM("SPAM")`, and the read view's toolbar (`MailReadScreen.kt`) shows a Spam button
      after Archive and Delete, with an icon, a TalkBack label and a test tag like the other actions. It is disabled
      while an action runs, like the others
- [ ] On success the view closes as after Archive/Delete. On failure the view stays open and shows the server's error,
      e.g. when the account has no Spam folder (NO_ROLE_FOLDER)
- [ ] The new string is in the app's string resources (all existing locales)
- [ ] Unit test (ViewModel) and Compose UI test for the button, success and error;
      `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes

## Notes
The only change in `mail/list` is the enum value in `MailModels.kt`. Adding the string touches the shared
`strings.xml` files: add lines only, and on a conflict keep both sides.
