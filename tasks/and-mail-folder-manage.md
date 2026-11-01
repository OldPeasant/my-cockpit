---
title: Android mail: create, rename and delete folders
status: in_progress
owner: dell26/agent-1
deps: [mail-folder-manage-api,and-mail-list-read,mail-account-label-email,and-mail-read-spam]
touches: [android/app/src/main/java/ch/sonensei/cockpit/mail/list,android/app/src/test/java/ch/sonensei/cockpit/mail/list]
prio: 2
lease_until: 2026-11-01T07:40:14Z
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
- ASSUMPTION (2026-11-01 07:50+0100 by dell26/agent-1): Rename goes through a hand-written MailApi.renameFolder (PATCH with body {name} only): the generated UpdateMailFolderRequest always serializes role:null (encodeDefaults, explicit nulls), which the server reads as 'remove the role'. Create and delete use the generated MailAccountsApi.
- ASSUMPTION (2026-11-01 07:50+0100 by dell26/agent-1): Folder actions are an overflow (MoreVert) button beside each folder in the picker plus a New-folder icon next to each account label; a 412 closes the dialog and shows the list message with the reload; a 409 on delete (folder holds a special folder) is shown in the confirm dialog. New strings live in new strings_mail_folders.xml files (en, de).
