---
title: Mail read view: Archive and Spam buttons
status: todo
owner:
deps: [web-mail-read-delete]
touches: [web/src/app/features/mail/read]
prio: 2
---
## Goal
The mail read view (`/mail/messages/:id`) gets Archive and Spam buttons next to the Delete button from
web-mail-read-delete, so a message can be triaged right after reading it. Requested by the owner (2026-10-30).
The Android read view already has Archive and Delete.

## Acceptance criteria
- [ ] Once the message has loaded, the read view's action bar shows Archive and Spam buttons. They call the existing
      mail actions API (`ARCHIVE`, `SPAM`) the same way the Delete button does, and all three are disabled while one
      of them runs
- [ ] On success the app returns to the mail list the message was opened from (as after Delete). On failure the view
      stays open and shows the server's error, e.g. when the account has no Archive or Spam folder (NO_ROLE_FOLDER)
- [ ] Keyboard shortcuts as in the list: `e` archives, `!` marks as spam (not while typing in an input or editor)
- [ ] Unit tests for both buttons, both shortcuts, success navigation and the error case; `cd web && npm run check` passes

## Notes
Reuse the Delete button's action code from web-mail-read-delete instead of duplicating it. Move is not in scope.
