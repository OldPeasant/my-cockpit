---
title: Web: mail → Next action & archive (read view, New view, mail list)
status: in_progress
owner: dell26/agent-1
deps: [mail-task-archive-api]
touches: [web/src/app/features/mail/read,web/src/app/features/mail/list,web/src/app/features/new,web/src/locale]
prio: 2
lease_until: 2026-11-14T10:28:20Z
---
## Goal
Web part of "→ Next action & archive" (server call from `mail-task-archive-api`). Single mail: the task detail
opens right away so the owner can fix title, context and project. Several mails: stay in the list.

## Acceptance criteria
- [ ] Mail read view: "→ Next action & archive" button next to "→ Inbox task" (which stays unchanged) and shortcut `Shift+T`; on success the new task's detail opens, and Back returns to where the mail was opened from
- [ ] New view: mail rows offer the same action (and `Shift+T` on the selected row), with the same result as in the read view
- [ ] Mail list: with several mails selected, the action creates one Next action per mail and archives them; the list stays, the mails leave it, and a message "N next actions created" links to Next Actions
- [ ] Archive failure: the task is kept (single mail: its detail still opens) and an error says which mail was not archived; a failed task creation leaves the mail in place with an error
- [ ] The task detail's linked items show the mail, and opening it shows the archived mail
- [ ] New UI strings are in `web/src/locale` (English + German); shortcut listed in the keyboard help where shortcuts are listed
- [ ] Unit tests cover the new behavior; `cd web && npm run check` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-14 with the owner: from a mail, make a Next action and archive the mail in one step (mails already clarified should not pass through the cockpit Inbox again); create a project directly from the task detail.
- ASSUMPTION (2026-11-14 11:08+0100 by dell26/agent-1): Mail list: the action applies to the list items (selection, else cursor); in threaded view one Next action per conversation item (its listed message), not per message of the thread. With exactly one target the list also opens the task detail, like the read view.
