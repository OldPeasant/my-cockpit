---
title: Android mail: create, rename and delete folders
status: todo
owner:
deps: [mail-folder-manage-api,and-mail-list-read,mail-account-label-email]
touches: [android/app/src/main/java/ch/sonensei/cockpit/mail/list,android/app/src/test/java/ch/sonensei/cockpit/mail/list]
prio: 2
---
## Goal
The Android mail screen lets the owner create, rename and delete folders of a mail account through the API from
mail-folder-manage-api, like the web folder tree does (web-mail-folder-manage). Requested by the owner (2026-10-30).

## Acceptance criteria
- [ ] The folder picker of the mail screen (`MailScreen.kt`) offers "New folder" per account, and per folder "New
      subfolder", "Rename" and "Delete" (e.g. long press or an overflow menu). Delete and Rename are not offered for
      INBOX, and Delete is not offered for folders with a role
- [ ] New folder and Rename open a dialog with the name; server errors (name taken, invalid name, server unreachable)
      are shown in the dialog, which stays open. After success the folder list shows the change at once
- [ ] Delete asks for confirmation, saying that the folder's messages and subfolders are deleted on the mail server
      too. Deleting the folder that is currently shown switches to the unified Inbox
- [ ] Rename and delete send the folder's ETag as If-Match; a 412 reloads the folders and shows a short message.
      The calls go through `MailApi`/`MailRepository` using the client generated from `api/openapi.yaml`
- [ ] Strings in the app's string resources (all existing locales), TalkBack labels on the new actions
- [ ] Unit tests (ViewModel, repository) and Compose UI tests for the actions, dialogs, errors and the switch after
      delete; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes

## Notes
Adding string resources touches the shared `strings.xml` files: add lines only, and on a conflict keep both sides.
