---
title: Web mail read view: unread, flag, move, shortcuts r/a/f/c
status: in_progress
owner: dell26/agent-1
deps: [web-mail-search-nav]
touches: [web/src/app/features/mail/read,web/src/app/features/mail/list,web/src/locale]
prio: 2
lease_until: 2026-11-08T14:06:26Z
---
## Goal
In the web read view mark unread, flag and move are missing (only reachable from list selection), and the SPEC
shortcuts `r`, `a`, `f`, `c` (SPEC.md §7.3) are not implemented.

## Acceptance criteria
- [ ] Read view: mark unread (returns to origin), flag/unflag, move to folder
- [ ] Shortcuts: `r` reply, `a` reply all, `f` forward in the read view; `c` compose everywhere in mail; listed in the shortcut help
- [ ] New UI strings are in `web/src/locale` (English + German)
- [ ] Unit tests cover the new behavior; `cd web && npm run check` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 14:28+0100 by dell26/agent-1): Shortcut help = the global ? overview: added a small root PageShortcuts registry in web/src/app/shell (outside the task's touches) so the mail list and read view show their keys there while open; no other mail shortcut help existed
- ASSUMPTION (2026-11-08 14:28+0100 by dell26/agent-1): r/a/f only act in the read view (SPEC lists them generically); in the list they do nothing. c opens /mail/compose from the list and the read view; on the compose page itself it is not bound (it would only reopen the same page)
- ASSUMPTION (2026-11-08 14:28+0100 by dell26/agent-1): Read view Move picks folders of the message's account via GET folders on demand (no folder store in the read page) and then behaves like Archive (next message of the list, else back); Star stays on the message; Mark unread waits for the pending mark-read request so they cannot cross
