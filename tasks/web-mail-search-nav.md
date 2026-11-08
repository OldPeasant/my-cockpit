---
title: Web mail search survives opening a match and has previous/next
status: in_progress
owner: dell26/agent-2
deps: [web-back-navigation,mail-images-web-settings]
touches: [web/src/app/features/mail/list,web/src/app/features/mail/read,web/src/locale]
prio: 1
lease_until: 2026-11-08T13:29:08Z
---
## Goal
The mail search lives only in a component signal (`mail-list-page.ts:56`), so opening a match and going back
loses the search term and results. While reading a match the owner wants to step to the previous/next match.

## Acceptance criteria
- [x] The query and the "also search on the server" option are part of the URL (e.g. `/mail?q=…&server=1`, also on folder routes); going back from a message restores the field and the results (served from the store when unchanged, no second server search)
  - verified (2026-11-08 14:03+0100 by dell26/agent-2): cd web && npx ng test --watch=false --include 'src/app/features/mail/**/*.spec.ts' -> 20 files, 732 passed; mail-list-page/mail-list-store specs cover q/server in URL on /mail and /mail/folders/<id>, Back restores field+results from MailListSession without a second server search
- [x] The read view shows previous/next buttons (and `k`/`j` shortcuts in the read view) when it was opened from a list: they step through the search results in list order; at the ends the button is disabled
  - verified (2026-11-08 14:03+0100 by dell26/agent-2): same mail spec run -> 732 passed; mail-read-page.spec 'previous/next through the list it was opened from' covers buttons, j/k, search order, disabled at ends
- [x] The same previous/next also works when opened from a normal folder list (unified inbox or folder) in its order
  - verified (2026-11-08 14:03+0100 by dell26/agent-2): same mail spec run -> 732 passed; mail-list-session/read-page specs step through unified inbox and folder lists incl. loading the next page
- [x] Archiving/deleting a message from the read view moves to the next match (or back to the list when none is left)
  - verified (2026-11-08 14:03+0100 by dell26/agent-2): same mail spec run -> 732 passed; read-page + session specs: archive/spam/delete go to next message (also across a page end), back to list when none left
- [x] The scroll position / cursor row of the list is kept when returning
  - verified (2026-11-08 14:03+0100 by dell26/agent-2): same mail spec run -> 732 passed; mail-list-page 'scroll position' + restore specs keep scrollTop and cursor row, also for restored lists >200 items
- [x] New UI strings are in `web/src/locale` (English + German)
  - verified (2026-11-08 14:03+0100 by dell26/agent-2): cd web && npm run build:de -> only 16 missing ids, all calendar.invite.* (other task web-de-invite-translations); no mail id missing; new strings present in messages.xlf + messages.de.xlf
- [x] Unit tests cover the new behavior; `cd web && npm run check` and `sh scripts/check.sh` pass
  - verified (2026-11-08 14:03+0100 by dell26/agent-2): cd web && npm run check -> lint clean, 188 files/4265 tests passed, build ok (pre-existing budget warning); sh scripts/check.sh at cba84e987+final specs (test-writer) -> all checks passed; done re-runs it

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 13:17+0100 by dell26/agent-2): The saved list (MailListSession, root service) lives for the browser tab session without expiry: going back to /mail?q=... restores the old results without searching again even after a long detour; folder lists are refetched in the background on restore. Reload/new tab loads anew.
- ASSUMPTION (2026-11-08 13:18+0100 by dell26/agent-2): Previous/next is shown only when the page right behind the message in the history is the saved list URL (BackNavigation.previous), so a message opened from New/dashboard never steps through an old list; stepping and archive-to-next replace the history entry so Back still returns to the list. The list cursor follows the message read last.
- ASSUMPTION (2026-11-08 13:18+0100 by dell26/agent-2): Archive/Spam/Delete in the read view go to the next message of the list, else the one before it (the newer one at the end), else back to the list; the server option alone (without a query) is not put into the URL; search pushes a history entry, toggling the server option on a shown search replaces it.
- ASSUMPTION (2026-11-08 13:37+0100 by dell26/agent-2): Review CR-3 accepted: the read view recognises the list by exact URL; hand-typed list URLs in another form (?server=1&q=x, extra params) restore fine but show no previous/next. App-built URLs always match. Restored folder lists with more than 200 items are not refetched in the background (they still update on live events).
