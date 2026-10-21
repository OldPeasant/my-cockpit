---
title: Settings: mail accounts
status: in_progress
owner: dell26/agent-3
deps: [p0-web-settings,mail-imap]
touches: [web/src/app/features/settings/mail]
prio: 1
lease_until: 2026-10-21T21:49:27Z
---
## Goal
Manage mail accounts (SPEC.md §7.1).

## Acceptance criteria
- [ ] Add IMAP/SMTP (with test connection), Google, Microsoft; identities, signature (Markdown), folder role mapping, notification toggle
- [ ] Vitest component/service tests cover account forms and test-connection states; `cd web && npm run check` passes

## Notes
- ASSUMPTION (2026-10-21 23:12+0200 by dell26/agent-3): Identities: the mail account API has no identity endpoints (mail-imap left them out), so the settings edit only the primary identity (display name + email address). Extra identities are new task mail-identities.
- ASSUMPTION (2026-10-21 23:12+0200 by dell26/agent-3): Google/Microsoft: the add form picks an existing OAuth connection of that provider; 'Connect another account' starts OAuth with purpose=MAIL, the server returns to Settings -> Integrations (fixed callback), and the user comes back to Settings -> Mail to add the account with that connection.
- ASSUMPTION (2026-10-21 23:12+0200 by dell26/agent-3): Test connection tests the stored account only (server API); the IMAP form therefore saves first, then runs the test and folder detection at once and shows the per-protocol result; a failed test keeps the account so it can be fixed with Edit.
