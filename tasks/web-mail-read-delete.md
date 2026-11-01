---
title: Mail read view: Delete button
status: in_progress
owner: dell26/agent-1
deps: [web-mail-read]
touches: [web/src/app/features/mail/read]
prio: 2
lease_until: 2026-11-01T06:14:20Z
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
- ASSUMPTION (2026-11-01 06:55+0100 by dell26/agent-1): The task names MailService.act('DELETE',[id]); the list's MailService method is apply(action, ids), so the read view calls apply('DELETE',[id]) and treats a per-id result with ok=false (or no result) as a failure, showing its server detail
- ASSUMPTION (2026-11-01 06:55+0100 by dell26/agent-1): The originating list is read from the router's previous navigation when the read page is created (/mail or /mail/folders/:id, else /mail), so mail-list code (outside touches) stays unchanged; after delete the app navigates there with replaceUrl so Back does not reopen the deleted message. Error = toast via NotificationService.error, the read view's existing action-error pattern
- ASSUMPTION (2026-11-01 06:55+0100 by dell26/agent-1): Added only the 4 new mailRead units to messages.xlf/messages.de.xlf by hand instead of committing the full npm run i18n:extract output: extraction also pulls in untranslated calendar.invite.* strings from itip-api (missing on main), which would fail messages-de.spec and is outside this task
