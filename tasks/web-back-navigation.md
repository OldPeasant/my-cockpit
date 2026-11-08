---
title: Web back buttons return to where the user came from
status: todo
owner:
deps: []
touches: [web/src/app/shared/navigation,web/src/app/features/mail/read,web/src/app/features/gtd/task-detail,web/src/app/features/notes/editor,web/src/app/features/contacts,web/src/app/features/gtd/projects]
prio: 1
---
## Goal
Detail pages have hardcoded back links, e.g. the mail read view's "Mail" button is `routerLink="/mail"`
(`mail-read-page.html:3-13`): opening a mail from New, the dashboard, search or a project and pressing it lands in the
mail list instead of where the user was. The owner wants a real "back".

## Acceptance criteria
- [ ] A shared helper (e.g. `BackNavigation` service + `app-back-button` component in `web/src/app/shared/navigation`) goes back in browser history when the previous entry is inside the app, and to the page's parent route otherwise (deep link, new tab)
- [ ] The back button's label names the destination where known (e.g. "New", "Mail", "Today", the project name) or reads just "Back"
- [ ] Used by the mail read view, task detail, project detail, note editor and contact detail (and any other detail page with a hardcoded back link found by grep)
- [ ] Mail read actions that leave the message (archive, delete, spam) return to the origin the same way instead of `/mail`
- [ ] Unit tests: opened from New → back returns to New with its filter; deep link → parent route
- [ ] New UI strings are in `web/src/locale` (English + German)
- [ ] Unit tests cover the new behavior; `cd web && npm run check` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
Android already returns to the origin for New → mail (`NewScreen.kt:118`); Android checks are in and-mail-search-nav.
