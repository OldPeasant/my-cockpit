---
title: Web: mail → Next action & archive (read view, New view, mail list)
status: in_progress
owner: dell26/agent-1
deps: [mail-task-archive-api]
touches: [web/src/app/features/mail/read,web/src/app/features/mail/list,web/src/app/features/new,web/src/locale]
prio: 2
lease_until: 2026-11-14T10:48:28Z
---
## Goal
Web part of "→ Next action & archive" (server call from `mail-task-archive-api`). Single mail: the task detail
opens right away so the owner can fix title, context and project. Several mails: stay in the list.

## Acceptance criteria
- [x] Mail read view: "→ Next action & archive" button next to "→ Inbox task" (which stays unchanged) and shortcut `Shift+T`; on success the new task's detail opens, and Back returns to where the mail was opened from
  - verified (2026-11-14 11:26+0100 by dell26/agent-1): cd web && npx ng test --watch=false --include mail-read-page.spec.ts (+5 related specs) -> 583 passed; read-view tests: button next to Inbox task, Shift+T, opens /tasks/<id> with replaceUrl, router-harness Back returns to the mail folder
- [x] New view: mail rows offer the same action (and `Shift+T` on the selected row), with the same result as in the read view
  - verified (2026-11-14 11:26+0100 by dell26/agent-1): cd web && npx ng test --watch=false --include new-page.spec.ts (with 5 related specs) -> 583 passed; New view row button and Shift+T on cursor mail open the task detail, Back returns to /new?filter=mail
- [x] Mail list: with several mails selected, the action creates one Next action per mail and archives them; the list stays, the mails leave it, and a message "N next actions created" links to Next Actions
  - verified (2026-11-14 11:26+0100 by dell26/agent-1): cd web && npx ng test --watch=false --include mail-list-page.spec.ts --include mail-list-store.spec.ts --include mail.service.spec.ts -> pass (583 total); several selected: one task per mail via POST to-tasks list NEXT archive true, archived rows leave the list, snackbar 'N next actions created' action navigates to /next
- [x] Archive failure: the task is kept (single mail: its detail still opens) and an error says which mail was not archived; a failed task creation leaves the mail in place with an error
  - verified (2026-11-14 11:26+0100 by dell26/agent-1): same spec run -> 583 passed; archived=false: task detail still opens (single) and error names the mail; several: one combined error naming mails not archived; request failure: error, mail stays, no navigation; partial chunk failure reported
- [~] The task detail's linked items show the mail, and opening it shows the archived mail
  - skipped (2026-11-14 11:27+0100 by dell26/agent-1): No e2e run here for linking: the web code creates the link only through the server's to-tasks endpoint (mail-task-archive-api, whose contract says the link keeps resolving to the moved message); the unchanged task-detail LinkedItems panel shows it. Needs a Playwright run against the real stack with an IMAP account.
- [x] New UI strings are in `web/src/locale` (English + German); shortcut listed in the keyboard help where shortcuts are listed
  - verified (2026-11-14 11:26+0100 by dell26/agent-1): new trans-units mail.nextAction.*, mail.action.nextAction, mailShortcuts.nextAction, mailRead.toNextAction, new.toNextAction in messages.xlf + messages.de.xlf; cd web && npm run build:de -> bundle complete, no missing translation; mail-shortcuts.spec: T listed in MAIL_LIST_HELP and MAIL_READ_HELP (? help), New view keys hint mentions Shift+T
- [ ] Unit tests cover the new behavior; `cd web && npm run check` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-14 with the owner: from a mail, make a Next action and archive the mail in one step (mails already clarified should not pass through the cockpit Inbox again); create a project directly from the task detail.
- ASSUMPTION (2026-11-14 11:08+0100 by dell26/agent-1): Mail list: the action applies to the list items (selection, else cursor); in threaded view one Next action per conversation item (its listed message), not per message of the thread. With exactly one target the list also opens the task detail, like the read view.
- ASSUMPTION (2026-11-14 11:08+0100 by dell26/agent-1): Read view: the task detail replaces the mail in the history (replaceUrl), so Back returns to where the mail was opened from; New view pushes the detail normally (Back returns to New). The 'N next actions created' message is a snackbar whose action opens /next. New view has no ? help registration, so Shift+T is listed in its keys hint line; mail list and read view list it in the ? help.
- ASSUMPTION (2026-11-14 11:16+0100 by dell26/agent-1): Threaded mail list: after the listed message of a conversation is turned into a Next action and archived, the conversation's other messages in the shown folder are archived too (like Archive does), so the row does not come back; one task per row.
- ASSUMPTION (2026-11-14 11:27+0100 by dell26/agent-1): Criterion 5 (task detail linked items show the archived mail) relies on the server's to-tasks link (mail-task-archive-api) and the existing LinkedItems panel; not verified end-to-end in a browser here.
