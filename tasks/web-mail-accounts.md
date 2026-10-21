---
title: Settings: mail accounts
status: in_progress
owner: dell26/agent-3
deps: [p0-web-settings,mail-imap]
touches: [web/src/app/features/settings/mail]
prio: 1
lease_until: 2026-10-21T21:31:02Z
---
## Goal
Manage mail accounts (SPEC.md §7.1).

## Acceptance criteria
- [ ] Add IMAP/SMTP (with test connection), Google, Microsoft; identities, signature (Markdown), folder role mapping, notification toggle
- [ ] Vitest component/service tests cover account forms and test-connection states; `cd web && npm run check` passes

## Notes
- ASSUMPTION (2026-10-21 23:12+0200 by dell26/agent-3): Identities: the mail account API has no identity endpoints (mail-imap left them out), so the settings edit only the primary identity (display name + email address). Extra identities are new task mail-identities.
