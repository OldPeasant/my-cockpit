---
title: Mail compose, reply, forward
status: todo
owner:
deps: [web-mail-read,mail-compose]
touches: [web/src/app/features/mail/compose]
prio: 1
---
## Goal
Write mail (SPEC.md §7.3).

## Acceptance criteria
- [ ] TipTap editor, identity picker, to/cc/bcc with autocomplete (recent correspondents), attachments (upload or from Files), autosave every 10 s, send
- [ ] Vitest component/service tests cover autosave timing (fake timers), recipient chips; `cd web && npm run check` passes
- [ ] Playwright `e2e/mail-compose/compose.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/mail-compose/compose.spec.ts`, see p0-e2e-harness): reply with a Files attachment is received by GreenMail

## Notes
