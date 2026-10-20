---
title: Settings: mail accounts
status: todo
owner:
deps: [p0-web-settings,mail-imap]
touches: [web/src/app/features/settings/mail]
prio: 1
---
## Goal
Manage mail accounts (SPEC.md §7.1).

## Acceptance criteria
- [ ] Add IMAP/SMTP (with test connection), Google, Microsoft; identities, signature (Markdown), folder role mapping, notification toggle
- [ ] Vitest component/service tests cover account forms and test-connection states; `cd web && npm run check` passes

## Notes
