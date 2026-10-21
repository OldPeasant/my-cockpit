---
title: Mail list and triage
status: in_progress
owner: dell26/agent-2
deps: [p0-web-shell,mail-actions-api,p0-web-sse,p0-e2e-harness]
touches: [web/src/app/features/mail/list]
prio: 1
lease_until: 2026-10-21T05:29:01Z
---
## Goal
Unified inbox and folders (SPEC.md §7.3).

## Acceptance criteria
- [ ] Unified inbox + per-account folder tree, threaded/flat toggle, infinite scroll, multi-select, actions and Gmail-like shortcuts (j/k, e, #, u, v)
- [ ] Live updates via SSE
- [ ] Vitest component/service tests cover selection, shortcuts, optimistic actions with rollback; `cd web && npm run check` passes
- [ ] Playwright `e2e/mail-list/list.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/mail-list/list.spec.ts`, see p0-e2e-harness): mark read, archive, move against GreenMail

## Notes
- ASSUMPTION (2026-10-21 06:35+0200 by dell26/agent-2): No scheduled mail sync exists yet (mail-idle is todo), so the e2e stack gets an e2e-profile-only hook POST /api/test/mail/sync (mail.testsupport.MailTestSupportController, @Hidden, not in the API contract) that syncs folders + headers of every account
- ASSUMPTION (2026-10-21 06:35+0200 by dell26/agent-2): GreenMail 2.1.5 added to deploy/docker-compose.e2e.yml (auth disabled, no TLS); its IMAP is published on a free host port E2E_IMAP_PORT chosen by run-e2e.mjs; specs deliver mail via IMAP APPEND (web/e2e/support/imap.ts) instead of SMTP
- ASSUMPTION (2026-10-21 06:35+0200 by dell26/agent-2): Clicking a list row only moves the keyboard cursor; opening a message is left to web-mail-read (no read route exists yet). r/a/f/c shortcuts belong to the read/compose tasks
- ASSUMPTION (2026-10-21 06:35+0200 by dell26/agent-2): Extra Gmail keys beyond the spec list: x select, Shift+I mark read, s star toggle, ! spam, Esc clear selection. In the conversations view an action applies to all messages of the conversation in the shown folder (via GET /messages/{id}/thread)
- ASSUMPTION (2026-10-21 06:35+0200 by dell26/agent-2): Live update refetches the loaded part of the list (up to 200 items) on MAIL_FOLDER events for the shown folder (any folder for the unified inbox), MAIL_ACCOUNT events and SSE reconnects; refetches wait while an optimistic action runs. Routes: /mail (unified) and /mail/folders/:folderId
