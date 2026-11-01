---
title: Mail list: a single click opens the message
status: in_progress
owner: dell26/agent-1
deps: [web-mail-list,mail-account-label-email]
touches: [web/src/app/features/mail/list]
prio: 2
lease_until: 2026-11-01T06:44:35Z
---
## Goal
In the web mail list a single click on a message opens it in the read view, as in most webmail. Today a click only
moves the keyboard cursor, and opening needs a double-click or `o` (an assumption of web-mail-list, made before the
read view existed). Requested by the owner (2026-10-30). Android already opens on a single tap.

## Acceptance criteria
- [x] A single click on a list row opens `/mail/messages/:id`. The row is a real link (`routerLink`), so Ctrl/Cmd-click
      and middle-click open the message in a new tab and the browser shows the target on hover
  - verified (2026-11-01 07:28+0100 by dell26/agent-1): mail-list-page.spec.ts (vitest, 59 passed): rows are a[href=/mail/messages/{id}] via routerLink, plain click navigates via router, Ctrl/Cmd/middle click not prevented (left to browser); npm run e2e -- e2e/mail-read e2e/mail-compose e2e/mail-list e2e/phase5/demo.spec.ts against docker-compose.e2e stack (chromium) -> 38 passed, read/compose/phase5 open the message with a single click on a.open
- [ ] The row's checkbox still only toggles the selection and does not open the message; the toolbar actions and
      keyboard shortcuts (j/k, x, e, #, ...) work as before, and `o` and Enter on the focused row open it
- [ ] The double-click handler is removed (the first click already opens), and the keys hint under the list says
      "o or click open" instead of "o or double-click open" (localized string updated)
- [ ] Unit tests: a click navigates to the message, a checkbox click does not, `o`/Enter open; the existing
      web-mail-list tests that expected a click to only move the cursor are updated; `cd web && npm run check` passes

## Notes
Keeping the scroll position and cursor when coming back from the read view is welcome but not required here.
- ASSUMPTION (2026-11-01 07:15+0100 by dell26/agent-1): Enter on a focused mail row is handled by the row (keydown.enter -> preventDefault, move cursor, navigate) instead of relying on native link activation, so it behaves like o and is unit-testable; Ctrl/Cmd+Enter stays native (new tab). Locale files (web/src/locale) edited although outside touches, as the criterion requires the localized hint.
- ASSUMPTION (2026-11-01 07:25+0100 by dell26/agent-1): Fixed two stale e2e locators outside touches (web/e2e/phase5/demo.spec.ts accountTree, web/e2e/mail-list/list.spec.ts): mail-account-label-email labels folder-tree regions by email address, so the phase 5 demo failed before reaching the row click this task changed; needed to run the e2e evidence.
