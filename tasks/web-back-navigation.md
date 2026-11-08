---
title: Web back buttons return to where the user came from
status: in_progress
owner: dell26/agent-1
deps: [fix-mail-images-firefox]
touches: [web/src/app/shared/navigation,web/src/app/features/mail/read,web/src/app/features/gtd/task-detail,web/src/app/features/notes/editor,web/src/app/features/contacts,web/src/app/features/gtd/projects]
prio: 1
lease_until: 2026-11-08T11:35:00Z
extended: dell26/agent-1
---
## Goal
Detail pages have hardcoded back links, e.g. the mail read view's "Mail" button is `routerLink="/mail"`
(`mail-read-page.html:3-13`): opening a mail from New, the dashboard, search or a project and pressing it lands in the
mail list instead of where the user was. The owner wants a real "back".

## Acceptance criteria
- [x] A shared helper (e.g. `BackNavigation` service + `app-back-button` component in `web/src/app/shared/navigation`) goes back in browser history when the previous entry is inside the app, and to the page's parent route otherwise (deep link, new tab)
  - verified (2026-11-08 12:15+0100 by dell26/agent-1): cd web && npx ng test --watch=false --include src/app/shared/navigation/back-navigation.spec.ts -> 57 passed (BackNavigation goes back in history when previous entry is an app page, to the parent route on deep link/new tab/unknown entry; back-button spec in npm run check)
- [x] The back button's label names the destination where known (e.g. "New", "Mail", "Today", the project name) or reads just "Back"
  - verified (2026-11-08 12:15+0100 by dell26/agent-1): back-button + back-navigation specs (label from route title or setLabel, e.g. New/Mail/Today/project title, else 'Back') in cd web && npm run check -> 187 files, 4139 tests passed
- [x] Used by the mail read view, task detail, project detail, note editor and contact detail (and any other detail page with a hardcoded back link found by grep)
  - verified (2026-11-08 12:15+0100 by dell26/agent-1): grep -rln 'BackNavigation|back-button' web/src/app/features -> mail read, task detail, project detail, note editor, contact detail, bookmark reader; grep for remaining hardcoded back routerLinks -> none; their page specs pass in npm run check
- [x] Mail read actions that leave the message (archive, delete, spam) return to the origin the same way instead of `/mail`
  - verified (2026-11-08 12:15+0100 by dell26/agent-1): cd web && npx ng test --include mail-read-page.spec.ts (archive/delete/spam call BackNavigation.back with fallback, replaceUrl) -> passed (also within npm run check 4139 passed)
- [ ] Unit tests: opened from New → back returns to New with its filter; deep link → parent route
- [ ] New UI strings are in `web/src/locale` (English + German)
- [ ] Unit tests cover the new behavior; `cd web && npm run check` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
Android already returns to the origin for New → mail (`NewScreen.kt:118`); Android checks are in and-mail-search-nav.
- ASSUMPTION (2026-11-08 11:15+0100 by dell26/agent-1): The app mirrors the browser history itself (BackNavigation tracks router navigations and the navigationId restored on popstate); an entry it never saw (deep link, reload, new tab) counts as 'nothing of the app behind', so back goes to the parent route. Labels come from the route titles (New, Mail, Today, ...) or names pages set for themselves (project, note, contact, bookmark title).
- ASSUMPTION (2026-11-08 11:16+0100 by dell26/agent-1): Mail read Archive/Spam/Delete now go back in history (Forward may reopen the moved message) and only replace the message with /mail when opened by a deep link; moving from message A to B and deleting B returns to A (real back), no longer to the list A was opened from.
- ASSUMPTION (2026-11-08 11:16+0100 by dell26/agent-1): Mail compose keeps its fixed 'Mail' back link: it is an editor with draft/send navigation, not a detail page. The bookmark reader (detail page found by grep, outside touches) uses the shared back button too. Parent labels read 'Projects'/'Notes'/'Contacts'/'Bookmarks' (nav titles) instead of 'All projects'/'Back to contacts'; e2e locators updated to 'Back to Projects'.
- ASSUMPTION (2026-11-08 11:43+0100 by dell26/agent-1): Known limitation (review CR-1, P3): after a Back that a page refused (unsaved changes), pressing the browser's Forward is ignored by the Angular router (same URL), so the back-navigation mirror stays one step behind and the next back-button click seems to do nothing; the browser's own Back behaves the same. Rare sequence, accepted rather than adding more router special-casing.
- extended by dell26/agent-1: Impl complete for all 7 criteria (service, back button, labels, used by mail/task/project/note/contact/bookmark, mail actions, i18n). Left: (1) fix or consciously descope skipped test 'goes to the parent route after a Back to an entry it never saw that another navigation took over' in back-navigation.spec.ts (previous() still returns left page); stop chasing further exotic history races, record as assumption; (2) re-run test-writer + code-reviewer on final code (reports are stale since 42661c14c); (3) verify criteria 1-7, write quality.json, sh scripts/check.sh, done.
- ASSUMPTION (2026-11-08 12:12+0100 by dell26/agent-1): Known limitation (review CR-1, P3): a browser Forward right after a Back that was still loading leaves the back button pointing one page off for one step (router emits only NavigationSkipped); rare race, accepted rather than adding deferred mirror moves
