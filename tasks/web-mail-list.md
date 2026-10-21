---
title: Mail list and triage
status: in_progress
owner: dell26/agent-2
deps: [p0-web-shell,mail-actions-api,p0-web-sse,p0-e2e-harness]
touches: [web/src/app/features/mail/list]
prio: 1
lease_until: 2026-10-21T04:53:37Z
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
