---
title: Web mail read view: unread, flag, move, shortcuts r/a/f/c
status: in_progress
owner: dell26/agent-1
deps: [web-mail-search-nav]
touches: [web/src/app/features/mail/read,web/src/app/features/mail/list,web/src/locale]
prio: 2
lease_until: 2026-11-08T13:46:11Z
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
