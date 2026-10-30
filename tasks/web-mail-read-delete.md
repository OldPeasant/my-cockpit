---
title: Mail read view: Delete button
status: todo
owner:
deps: [web-mail-read]
touches: [web/src/app/features/mail/read]
prio: 2
---
## Goal
The mail read view (`/mail/messages/:id`) has a Delete button, so a message can be deleted right after
reading it, without going back to the list first. Today Delete only exists in the list toolbar
(web-mail-list), and the read view has no way to delete.

## Acceptance criteria
- [ ] The read view's action bar shows a Delete button once the message has loaded. It calls the existing
      mail actions API (`MailService.act('DELETE', [id])`, as the list does, so the server stays unchanged)
      and is disabled while the request runs
- [ ] On success the app returns to the mail list the message was opened from (the unified Inbox if that
      is unknown), and the message is no longer shown there. On failure the read view stays open and shows
      an error, using the view's existing error pattern
- [ ] The `#` key in the read view also deletes, as in the list (not while typing in an input or editor)
- [ ] Unit tests cover the button, the shortcut, success navigation and the error case; `cd web && npm run check` passes

## Notes
Requested by the owner (2026-10-30). Only Delete is in scope; Archive/Spam/Move in the read view and
"open the next message after deleting" are not.
