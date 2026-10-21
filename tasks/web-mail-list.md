---
title: Mail list and triage
status: in_progress
owner: dell26/agent-2
deps: [p0-web-shell,mail-actions-api,p0-web-sse,p0-e2e-harness]
touches: [web/src/app/features/mail/list]
prio: 1
lease_until: 2026-10-21T06:04:16Z
extended: dell26/agent-2
---
## Goal
Unified inbox and folders (SPEC.md §7.3).

## Acceptance criteria
- [ ] Unified inbox + per-account folder tree, threaded/flat toggle, infinite scroll, multi-select, actions and Gmail-like shortcuts (j/k, e, #, u, v)
- [x] Live updates via SSE
  - verified (2026-10-21 07:40+0200 by dell26/agent-2): npm --prefix web run e2e -- e2e/mail-list/list.spec.ts -> live-update step passes (message appended via IMAP + sync hook shows up without reload via SSE MAIL_FOLDER event); Vitest 'live updates' specs in mail-list-store.spec.ts / mail-folders-store.spec.ts pass in npm run check
- [ ] Vitest component/service tests cover selection, shortcuts, optimistic actions with rollback; `cd web && npm run check` passes
- [x] Playwright `e2e/mail-list/list.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/mail-list/list.spec.ts`, see p0-e2e-harness): mark read, archive, move against GreenMail
  - verified (2026-10-21 07:39+0200 by dell26/agent-2): npm --prefix web run e2e -- e2e/mail-list/list.spec.ts (run-e2e.mjs: podman compose e2e stack server+postgres+GreenMail, chromium) at 11d5d088 -> 6 passed incl. 'Mail list > mark read, archive and move against GreenMail; live updates' (checked over IMAP)

## Notes
- ASSUMPTION (2026-10-21 06:35+0200 by dell26/agent-2): No scheduled mail sync exists yet (mail-idle is todo), so the e2e stack gets an e2e-profile-only hook POST /api/test/mail/sync (mail.testsupport.MailTestSupportController, @Hidden, not in the API contract) that syncs folders + headers of every account
- ASSUMPTION (2026-10-21 06:35+0200 by dell26/agent-2): GreenMail 2.1.5 added to deploy/docker-compose.e2e.yml (auth disabled, no TLS); its IMAP is published on a free host port E2E_IMAP_PORT chosen by run-e2e.mjs; specs deliver mail via IMAP APPEND (web/e2e/support/imap.ts) instead of SMTP
- ASSUMPTION (2026-10-21 06:35+0200 by dell26/agent-2): Clicking a list row only moves the keyboard cursor; opening a message is left to web-mail-read (no read route exists yet). r/a/f/c shortcuts belong to the read/compose tasks
- ASSUMPTION (2026-10-21 06:35+0200 by dell26/agent-2): Extra Gmail keys beyond the spec list: x select, Shift+I mark read, s star toggle, ! spam, Esc clear selection. In the conversations view an action applies to all messages of the conversation in the shown folder (via GET /messages/{id}/thread)
- ASSUMPTION (2026-10-21 06:35+0200 by dell26/agent-2): Live update refetches the loaded part of the list (up to 200 items) on MAIL_FOLDER events for the shown folder (any folder for the unified inbox), MAIL_ACCOUNT events and SSE reconnects; refetches wait while an optimistic action runs. Routes: /mail (unified) and /mail/folders/:folderId
- extended by dell26/agent-2: Implementation, Vitest specs, e2e spec + GreenMail stack are committed; 2nd code review: 0 P0-P2, 3 P3 (fix or disposition them). Left: run test-writer on final code (reviews/web-mail-list/tests.md is missing, incl. coverage), re-review if code changes, run npm run check and the e2e spec npm run e2e -- e2e/mail-list/list.spec.ts, record verify 1-4, write quality.json, done. Note: scripts/check.sh hit a 600s timeout in server tests this session (machine load?), needs a re-run.
