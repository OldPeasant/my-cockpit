---
title: Mail compose: contacts autocomplete
status: in_progress
owner: dell26/agent-1
deps: [web-mail-compose,contacts-aggregate]
touches: [web/src/app/features/mail/compose]
prio: 3
lease_until: 2026-10-25T22:03:01Z
---
## Goal
Recipients from contacts.

## Acceptance criteria
- [x] Autocomplete uses the contacts endpoint
  - verified (2026-10-25 22:48+0100 by dell26/agent-1): MailComposeService.suggestions calls generated autocompleteContacts; cd web && npx ng test --watch=false --include='src/app/features/mail/compose/**/*.spec.ts' (HttpTestingController expects GET /api/v1/contacts/autocomplete with q, limit; asserts no /api/v1/mail/correspondents call) -> 126 passed
- [x] Vitest component/service tests cover merged suggestions; `cd web && npm run check` passes
  - verified (2026-10-25 22:48+0100 by dell26/agent-1): service specs (merged contacts-then-correspondents order, dedupe, no-address, empty, error) + recipient-input specs (contact marker, choose contact, hide taken): focused ng test -> 126 passed; cd web && npm run check -> exit 0, 166 files / 3463 tests passed, build ok; sh scripts/check.sh -> all checks passed

## Notes
