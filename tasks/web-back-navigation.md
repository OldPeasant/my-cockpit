---
title: Web back buttons return to where the user came from
status: in_progress
owner: dell26/agent-1
deps: [fix-mail-images-firefox]
touches: [web/src/app/shared/navigation,web/src/app/features/mail/read,web/src/app/features/gtd/task-detail,web/src/app/features/notes/editor,web/src/app/features/contacts,web/src/app/features/gtd/projects]
prio: 1
lease_until: 2026-11-08T10:59:45Z
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
- ASSUMPTION (2026-11-08 11:15+0100 by dell26/agent-1): The app mirrors the browser history itself (BackNavigation tracks router navigations and the navigationId restored on popstate); an entry it never saw (deep link, reload, new tab) counts as 'nothing of the app behind', so back goes to the parent route. Labels come from the route titles (New, Mail, Today, ...) or names pages set for themselves (project, note, contact, bookmark title).
- ASSUMPTION (2026-11-08 11:16+0100 by dell26/agent-1): Mail read Archive/Spam/Delete now go back in history (Forward may reopen the moved message) and only replace the message with /mail when opened by a deep link; moving from message A to B and deleting B returns to A (real back), no longer to the list A was opened from.
- ASSUMPTION (2026-11-08 11:16+0100 by dell26/agent-1): Mail compose keeps its fixed 'Mail' back link: it is an editor with draft/send navigation, not a detail page. The bookmark reader (detail page found by grep, outside touches) uses the shared back button too. Parent labels read 'Projects'/'Notes'/'Contacts'/'Bookmarks' (nav titles) instead of 'All projects'/'Back to contacts'; e2e locators updated to 'Back to Projects'.
