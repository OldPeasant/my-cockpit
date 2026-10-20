---
title: Contacts UI
status: todo
owner:
deps: [p0-web-shell,contacts-aggregate,web-links-panel,p0-e2e-harness]
touches: [web/src/app/features/contacts]
prio: 2
---
## Goal
Contact list and detail (SPEC.md §8.3).

## Acceptance criteria
- [ ] List with search, detail with related mails/events/waiting-for, edit, vCard import/export, Google/MS sync status
- [ ] Vitest component/service tests cover detail sections; `cd web && npm run check` passes
- [ ] Playwright `e2e/contacts/contacts.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/contacts/contacts.spec.ts`, see p0-e2e-harness): import a vCard; birthday appears in calendar

## Notes
