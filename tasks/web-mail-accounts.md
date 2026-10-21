---
title: Settings: mail accounts
status: in_progress
owner: dell26/agent-3
deps: [p0-web-settings,mail-imap]
touches: [web/src/app/features/settings/mail]
prio: 1
lease_until: 2026-10-21T22:24:52Z
---
## Goal
Manage mail accounts (SPEC.md §7.1).

## Acceptance criteria
- [x] Add IMAP/SMTP (with test connection), Google, Microsoft; identities, signature (Markdown), folder role mapping, notification toggle
  - verified (2026-10-22 00:05+0200 by dell26/agent-3): Vitest component specs (cd web && npx ng test --watch=false --include 'src/app/features/settings/mail/*.spec.ts' --include settings.routes.spec.ts -> 6 files, 120 passed) exercise IMAP/SMTP add with test connection + folder detection, Google/Microsoft add via OAuth connection (purpose=MAIL), primary identity edit, Markdown signature, folder role mapping, notification toggle against HttpTestingController; real IMAP/Google/Microsoft servers not reachable here (h-mail-real)
- [x] Vitest component/service tests cover account forms and test-connection states; `cd web && npm run check` passes
  - verified (2026-10-22 00:05+0200 by dell26/agent-3): cd web && npm run check -> exit 0, 131 test files passed (incl. mail-settings, mail-account-editor, folder-roles specs: account forms and test-connection running/ok/failed states); task-scoped coverage 99.53% statements / 93.24% branches (reviews/web-mail-accounts/tests.md)

## Notes
- ASSUMPTION (2026-10-21 23:12+0200 by dell26/agent-3): Identities: the mail account API has no identity endpoints (mail-imap left them out), so the settings edit only the primary identity (display name + email address). Extra identities are new task mail-identities.
- ASSUMPTION (2026-10-21 23:12+0200 by dell26/agent-3): Google/Microsoft: the add form picks an existing OAuth connection of that provider; 'Connect another account' starts OAuth with purpose=MAIL, the server returns to Settings -> Integrations (fixed callback), and the user comes back to Settings -> Mail to add the account with that connection.
- ASSUMPTION (2026-10-21 23:12+0200 by dell26/agent-3): Test connection tests the stored account only (server API); the IMAP form therefore saves first, then runs the test and folder detection at once and shows the per-protocol result; a failed test keeps the account so it can be fixed with Edit.
