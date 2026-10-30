---
title: Mail list: a single click opens the message
status: todo
owner:
deps: [web-mail-list,mail-account-label-email]
touches: [web/src/app/features/mail/list]
prio: 2
---
## Goal
In the web mail list a single click on a message opens it in the read view, as in most webmail. Today a click only
moves the keyboard cursor, and opening needs a double-click or `o` (an assumption of web-mail-list, made before the
read view existed). Requested by the owner (2026-10-30). Android already opens on a single tap.

## Acceptance criteria
- [ ] A single click on a list row opens `/mail/messages/:id`. The row is a real link (`routerLink`), so Ctrl/Cmd-click
      and middle-click open the message in a new tab and the browser shows the target on hover
- [ ] The row's checkbox still only toggles the selection and does not open the message; the toolbar actions and
      keyboard shortcuts (j/k, x, e, #, ...) work as before, and `o` and Enter on the focused row open it
- [ ] The double-click handler is removed (the first click already opens), and the keys hint under the list says
      "o or click open" instead of "o or double-click open" (localized string updated)
- [ ] Unit tests: a click navigates to the message, a checkbox click does not, `o`/Enter open; the existing
      web-mail-list tests that expected a click to only move the cursor are updated; `cd web && npm run check` passes

## Notes
Keeping the scroll position and cursor when coming back from the read view is welcome but not required here.
