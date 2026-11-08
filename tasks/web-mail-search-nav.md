---
title: Web mail search survives opening a match and has previous/next
status: todo
owner:
deps: [web-back-navigation,mail-images-web-settings]
touches: [web/src/app/features/mail/list,web/src/app/features/mail/read,web/src/locale]
prio: 1
---
## Goal
The mail search lives only in a component signal (`mail-list-page.ts:56`), so opening a match and going back
loses the search term and results. While reading a match the owner wants to step to the previous/next match.

## Acceptance criteria
- [ ] The query and the "also search on the server" option are part of the URL (e.g. `/mail?q=…&server=1`, also on folder routes); going back from a message restores the field and the results (served from the store when unchanged, no second server search)
- [ ] The read view shows previous/next buttons (and `k`/`j` shortcuts in the read view) when it was opened from a list: they step through the search results in list order; at the ends the button is disabled
- [ ] The same previous/next also works when opened from a normal folder list (unified inbox or folder) in its order
- [ ] Archiving/deleting a message from the read view moves to the next match (or back to the list when none is left)
- [ ] The scroll position / cursor row of the list is kept when returning
- [ ] New UI strings are in `web/src/locale` (English + German)
- [ ] Unit tests cover the new behavior; `cd web && npm run check` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
