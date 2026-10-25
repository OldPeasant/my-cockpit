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
- [ ] Autocomplete uses the contacts endpoint
- [ ] Vitest component/service tests cover merged suggestions; `cd web && npm run check` passes

## Notes
