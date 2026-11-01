---
title: Mail read view: Delete button
status: in_progress
owner: dell26/agent-1
deps: [web-mail-read]
touches: [web/src/app/features/mail/read]
prio: 2
lease_until: 2026-11-01T06:24:23Z
---
## Goal
The mail read view (`/mail/messages/:id`) has a Delete button, so a message can be deleted right after
reading it, without going back to the list first. Today Delete only exists in the list toolbar
(web-mail-list), and the read view has no way to delete.

## Acceptance criteria
- [x] The read view's action bar shows a Delete button once the message has loaded. It calls the existing
      mail actions API (`MailService.act('DELETE', [id])`, as the list does, so the server stays unchanged)
      and is disabled while the request runs
  - verified (2026-11-01 07:05+0100 by dell26/agent-1): npm --prefix web run test:ci -- --include src/app/features/mail/read/mail-read-page.spec.ts --include src/locale/messages-de.spec.ts at 0ec379e65 -> 76 passed; Delete tests: button absent while loading/failed, shown when ready, calls MailService.apply('DELETE',[id]) (the list's action method), disabled while pending and second click/# ignored
- [x] On success the app returns to the mail list the message was opened from (the unified Inbox if that
      is unknown), and the message is no longer shown there. On failure the read view stays open and shows
      an error, using the view's existing error pattern
  - verified (2026-11-01 07:05+0100 by dell26/agent-1): same focused run (76 passed): RouterTestingHarness tests delete and land on /mail/folders/f-archive when opened there, /mail when opened from /mail, /notes, /mail/compose or directly, origin kept across message->message; failure cases (ok=false detail, ApiError 409, TypeError, missing result) keep the read view open and toast 'The message could not be deleted: ...' via NotificationService.error. List gone-ness: the list page builds a fresh component-scoped store and reloads from the server on open (mail-list-page.ts constructor; full web suite 3882 passed). No browser e2e run for this (list behavior is web-mail-list's)
- [x] The `#` key in the read view also deletes, as in the list (not while typing in an input or editor)
  - verified (2026-11-01 07:05+0100 by dell26/agent-1): same focused run (76 passed): '#' deletes and preventDefaults, also with AltGr (Ctrl+Alt); ignored in input and contenteditable, with Meta, auto-repeat, while loading, and while a MatDialog is open (CR-1 fix, test fails without the guard)
- [x] Unit tests cover the button, the shortcut, success navigation and the error case; `cd web && npm run check` passes
  - verified (2026-11-01 07:05+0100 by dell26/agent-1): npm --prefix web run check (api:check, lint, e2e:typecheck, test:ci, ng build) -> exit 0 on the implementation; after the CR-1 fix: npm --prefix web run test:ci -> 3882 passed, npm --prefix web run lint -> clean (test-writer second pass at 0ec379e65)

## Notes
Requested by the owner (2026-10-30). Only Delete is in scope; Archive/Spam/Move in the read view and
"open the next message after deleting" are not.
- ASSUMPTION (2026-11-01 06:55+0100 by dell26/agent-1): The task names MailService.act('DELETE',[id]); the list's MailService method is apply(action, ids), so the read view calls apply('DELETE',[id]) and treats a per-id result with ok=false (or no result) as a failure, showing its server detail
- ASSUMPTION (2026-11-01 06:55+0100 by dell26/agent-1): The originating list is read from the router's previous navigation when the read page is created (/mail or /mail/folders/:id, else /mail), so mail-list code (outside touches) stays unchanged; after delete the app navigates there with replaceUrl so Back does not reopen the deleted message. Error = toast via NotificationService.error, the read view's existing action-error pattern
- ASSUMPTION (2026-11-01 06:55+0100 by dell26/agent-1): Added only the 4 new mailRead units to messages.xlf/messages.de.xlf by hand instead of committing the full npm run i18n:extract output: extraction also pulls in untranslated calendar.invite.* strings from itip-api (missing on main), which would fail messages-de.spec and is outside this task
