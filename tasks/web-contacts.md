---
title: Contacts UI
status: in_progress
owner: dell26/agent-3
deps: [p0-web-shell,contacts-aggregate,web-links-panel,p0-e2e-harness]
touches: [web/src/app/features/contacts]
prio: 2
lease_until: 2026-10-22T04:19:02Z
---
## Goal
Contact list and detail (SPEC.md §8.3).

## Acceptance criteria
- [ ] List with search, detail with related mails/events/waiting-for, edit, vCard import/export, Google/MS sync status
- [ ] Vitest component/service tests cover detail sections; `cd web && npm run check` passes
- [ ] Playwright `e2e/contacts/contacts.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/contacts/contacts.spec.ts`, see p0-e2e-harness): import a vCard; birthday appears in calendar

## Notes
- ASSUMPTION (2026-10-22 05:36+0200 by dell26/agent-3): Google/MS sync status = connected OAuth accounts whose granted scopes include contacts (GET /oauth/connections), shown on the list (syncs both ways / paused: sign in again) and per contact (source + its provider's account; with several accounts of a provider the first is shown, since sync links are not exposed by the API). No per-contact last-sync time: the API has none
- ASSUMPTION (2026-10-22 05:36+0200 by dell26/agent-3): Routes: /contacts (list, ?q= search), /contacts/new (create), /contacts/:id (detail + inline edit). Edits of GOOGLE/MICROSOFT contacts are allowed (two-way sync pushes them). Delete moves to Trash without a confirm dialog (Trash allows restore), like bookmarks
- ASSUMPTION (2026-10-22 05:36+0200 by dell26/agent-3): E2E birthday step verified on a scratch worktree of this branch merged with origin/wip/contacts-birthdays (not yet on main); the spec fails on main until contacts-birthdays is done. messages.xlf not re-extracted (left to a dedicated extract, as other web tasks do)
