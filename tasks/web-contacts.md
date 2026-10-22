---
title: Contacts UI
status: in_progress
owner: dell26/agent-1
deps: [p0-web-shell,contacts-aggregate,web-links-panel,p0-e2e-harness,contacts-birthdays]
touches: [web/src/app/features/contacts]
prio: 2
lease_until: 2026-10-22T08:19:04Z
releases: 1
extended: 
---
## Goal
Contact list and detail (SPEC.md §8.3).

## Acceptance criteria
- [x] List with search, detail with related mails/events/waiting-for, edit, vCard import/export, Google/MS sync status
  - verified (2026-10-22 09:59+0200 by dell26/agent-1): ng test --include='src/app/features/contacts/**/*.spec.ts' at 2c0a11bec -> 103 passed (list+search, detail related mails/events/waiting-for, inline edit with If-Match/conflict, vCard import/export, sync status); e2e contacts.spec.ts exercises import/list/search/detail/export in the real UI -> passed; code-reviewer: no P0-P2
- [ ] Vitest component/service tests cover detail sections; `cd web && npm run check` passes
- [ ] Playwright `e2e/contacts/contacts.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/contacts/contacts.spec.ts`, see p0-e2e-harness): import a vCard; birthday appears in calendar

## Notes
- ASSUMPTION (2026-10-22 05:36+0200 by dell26/agent-3): Google/MS sync status = connected OAuth accounts whose granted scopes include contacts (GET /oauth/connections), shown on the list (syncs both ways / paused: sign in again) and per contact (source + its provider's account; with several accounts of a provider the first is shown, since sync links are not exposed by the API). No per-contact last-sync time: the API has none
- ASSUMPTION (2026-10-22 05:36+0200 by dell26/agent-3): Routes: /contacts (list, ?q= search), /contacts/new (create), /contacts/:id (detail + inline edit). Edits of GOOGLE/MICROSOFT contacts are allowed (two-way sync pushes them). Delete moves to Trash without a confirm dialog (Trash allows restore), like bookmarks
- ASSUMPTION (2026-10-22 05:36+0200 by dell26/agent-3): E2E birthday step verified on a scratch worktree of this branch merged with origin/wip/contacts-birthdays (not yet on main); the spec fails on main until contacts-birthdays is done. messages.xlf not re-extracted (left to a dedicated extract, as other web tasks do)
- ASSUMPTION (2026-10-22 05:59+0200 by dell26/agent-3): CR-1: criterion 3 (birthday appears in calendar) needs contacts-birthdays on main, so web-contacts now depends on contacts-birthdays; criterion 3 is verified only on main after that task is done, not with the earlier wip-merge evidence
- release by dell26/agent-3: Implementation done and committed (list+search, detail with related mails/events/waiting-for, inline edit with If-Match/conflict handling, vCard import/export, Google/MS sync status; unit tests; e2e spec web/e2e/contacts/contacts.spec.ts). Re-review (reviews/web-contacts/review.md) has only CR-1 open: criterion 3's birthday step needs contacts-birthdays on main (dep added, still in progress by agent-1). .taskcheck passes at 6aa99547. Left: once contacts-birthdays is done, merge main, run 'npm run e2e -- e2e/contacts/contacts.spec.ts' on it; rerun test-writer (no tests.md yet; last spec edits in 6aa99547 are unreviewed tests) and code-reviewer on final code; write quality.json (fixer ran: fixes.md, CR-2..CR-5 resolved); tasks.py verify 1-3; done.
