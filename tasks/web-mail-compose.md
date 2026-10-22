---
title: Mail compose, reply, forward
status: in_progress
owner: dell26/agent-1
deps: [web-mail-read,mail-compose]
touches: [web/src/app/features/mail/compose]
prio: 1
lease_until: 2026-10-22T07:23:41Z
---
## Goal
Write mail (SPEC.md §7.3).

## Acceptance criteria
- [ ] TipTap editor, identity picker, to/cc/bcc with autocomplete (recent correspondents), attachments (upload or from Files), autosave every 10 s, send
- [ ] Vitest component/service tests cover autosave timing (fake timers), recipient chips; `cd web && npm run check` passes
- [ ] Playwright `e2e/mail-compose/compose.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/mail-compose/compose.spec.ts`, see p0-e2e-harness): reply with a Files attachment is received by GreenMail

## Notes
- ASSUMPTION (2026-10-22 09:07+0200 by dell26/agent-1): Identity picker = the mail accounts (display name + address): there is no identity API yet (task mail-identities); identityId stays the server default / what reply chose. Moving a stored draft to another account recreates it there and discards the old copy; disabled for replies (threading headers) and drafts with attachments, which a new draft cannot carry over
- ASSUMPTION (2026-10-22 09:07+0200 by dell26/agent-1): Autocomplete suggests recent correspondents only (GET /mail/correspondents); contacts come with web-mail-contacts-autocomplete. Recipients are chips; invalid addresses stay as red chips, are left out of autosaves and block Send
- ASSUMPTION (2026-10-22 09:07+0200 by dell26/agent-1): Compose is a page /mail/compose (?draft= | ?reply= | ?replyAll= | ?forward=), reached from a Compose button in the list and Reply/Reply all/Forward buttons in the read view (small edits outside touches); reply/forward drafts are created on open and the URL replaced by ?draft=. Keyboard shortcuts c/r/a/f are not added here
