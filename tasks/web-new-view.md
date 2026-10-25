---
title: Combined New view (GTD inbox + mail inbox)
status: in_progress
owner: r1963macos/agent-2
deps: [web-inbox,web-mail-list,mail-gtd]
touches: [web/src/app/features/new,web/src/app/app.routes.ts,web/src/app/shell/nav-sections.ts,web/e2e/new]
prio: 2
lease_until: 2026-10-25T23:03:44Z
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
- ASSUMPTION (2026-10-25 23:40+0100 by r1963macos/agent-2): New view: 'New' nav entry sits in the Home group right after Dashboard (the first Tasks entry is Inbox), so it is between Dashboard and Inbox without a new group.
- ASSUMPTION (2026-10-25 23:40+0100 by r1963macos/agent-2): New view: while older mail is still to load, Inbox tasks older than the oldest loaded mail are held back, so loading the next mail page only appends rows. In a large mailbox, old captures therefore appear only after scrolling far enough. Strict merge order wins over showing all tasks at once.
- ASSUMPTION (2026-10-25 23:40+0100 by r1963macos/agent-2): New view: filter keys are 1/2/3 (All/GTD Inbox/Mail), URL param ?source=inbox|mail (absent = all). Row keys reuse the mail list's: j/k cursor, o/Enter open, t = '→ Inbox task' on mail rows. The mail rows show the unified inbox flat (not threaded).
