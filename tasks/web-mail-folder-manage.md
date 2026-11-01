---
title: Mail folder tree: create, rename and delete folders
status: in_progress
owner: dell26/agent-1
deps: [mail-folder-manage-api,web-mail-list,mail-account-label-email,web-mail-single-click-open]
touches: [web/src/app/features/mail/list]
prio: 2
lease_until: 2026-11-01T07:55:22Z
---
## Goal
The mail folder tree (`web/src/app/features/mail/list/mail-folder-tree.ts`) lets the owner create, rename and delete
folders through the API from mail-folder-manage-api. Requested by the owner (2026-10-30).

## Acceptance criteria
- [ ] Each account in the folder tree has a "New folder" action, and each folder a menu with "New subfolder", "Rename"
      and "Delete". Delete and Rename are not shown for INBOX, and Delete is not shown for folders with a role
- [ ] New folder and Rename open a small dialog with the name; errors from the server (name taken, invalid name,
      server unreachable) are shown in the dialog, which stays open. After success the tree shows the change at once
- [ ] Delete asks for confirmation, saying that the folder's messages and subfolders are deleted on the mail server
      too. Deleting the folder that is currently shown navigates to the unified Inbox
- [ ] Usable with the keyboard (menus and dialogs focusable, Escape closes), labels localized like the rest of the
      mail feature
- [ ] Unit tests for the actions, the dialogs, the error cases and the navigation after delete;
      `cd web && npm run check` passes

## Notes
Use the frontend-design skill for the menu and dialogs, matching the existing mail list and move dialog.
- ASSUMPTION (2026-11-01 08:34+0100 by dell26/agent-1): Delete is also hidden for folders that hold a folder with a role (e.g. [Gmail] holding Trash), because the server refuses those (409); New subfolder is hidden for flat namespaces (no delimiter)
- ASSUMPTION (2026-11-01 08:34+0100 by dell26/agent-1): Server errors in the folder dialogs are shown as localized texts by HTTP status (409 taken, 400 invalid, 0 unreachable, 502 mail server, 404/412 changed elsewhere + tree reload) instead of the English server detail; other statuses fall back to the server text
- ASSUMPTION (2026-11-01 08:34+0100 by dell26/agent-1): Delete confirmation performs the delete inside the dialog and keeps the dialog open with the error on failure (same pattern as the name dialog); after any change the tree updates locally at once and reloads the folders in the background for fresh versions
