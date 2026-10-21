---
title: Combined New view (GTD inbox + mail inbox)
status: todo
owner:
deps: [web-inbox,web-mail-list,mail-gtd]
touches: [web/src/app/features/new,web/src/app/app.routes.ts,web/src/app/shell/nav-sections.ts,web/e2e/new]
prio: 2
---
## Goal
Web "New" view: one list of everything new, the GTD Inbox items plus the unified mail inbox, with a
source filter (SPEC.md §5.3 "New view", §9.1). An additional aggregated view only: the Inbox and Mail
views and their behavior stay unchanged, mails never become tasks automatically.

## Acceptance criteria
- [ ] Route `/new` and a "New" entry in the left navigation (between Dashboard and Inbox); the page merges GTD inbox tasks and unified-inbox mails newest first (task creation date / mail date), each row shows its source (icon + label), mail rows load more by cursor (infinite scroll) without breaking the order
- [ ] Filter All / GTD Inbox / Mail (keyboard shortcut and URL query param, so a filter survives reload); opening a row navigates to the task detail or the mail read view; a mail row offers "→ Inbox task" (mail-gtd API); live updates via SSE (new capture or new mail appears, processed/archived items disappear)
- [ ] Uses only the existing APIs (no new server endpoint); Vitest component/service tests cover merge order across paging, each filter, SSE add/remove, "→ Inbox task"; `cd web && npm run check` passes
- [ ] Playwright `e2e/new/new.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/new/new.spec.ts`, see p0-e2e-harness): a capture and a GreenMail message both appear under All, only one under each filter; "→ Inbox task" adds the task without removing the mail

## Notes
