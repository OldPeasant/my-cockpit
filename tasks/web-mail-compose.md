---
title: Mail compose, reply, forward
status: in_progress
owner: dell26/agent-1
deps: [web-mail-read,mail-compose]
touches: [web/src/app/features/mail/compose]
prio: 1
lease_until: 2026-10-22T07:48:49Z
---
## Goal
Write mail (SPEC.md §7.3).

## Acceptance criteria
- [x] TipTap editor, identity picker, to/cc/bcc with autocomplete (recent correspondents), attachments (upload or from Files), autosave every 10 s, send
  - verified (2026-10-22 09:32+0200 by dell26/agent-1): npm --prefix web run e2e -- e2e/mail-compose/compose.spec.ts (podman e2e stack, real server + GreenMail, at 3d7d740d5) -> reply with Files attachment received; plus mail-compose-page/recipient-input/rich-text-editor/service specs (TipTap editor, sender/identity, to/cc/bcc suggestions from recent correspondents, upload + Files attach, 10 s autosave, send) pass in npm run check -> 3165 passed
- [ ] Vitest component/service tests cover autosave timing (fake timers), recipient chips; `cd web && npm run check` passes
- [x] Playwright `e2e/mail-compose/compose.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/mail-compose/compose.spec.ts`, see p0-e2e-harness): reply with a Files attachment is received by GreenMail
  - verified (2026-10-22 09:26+0200 by dell26/agent-1): cd web && npm run e2e -- e2e/mail-compose/compose.spec.ts (podman-compose e2e stack with GreenMail, chromium) at dd03a60d7 -> 25 passed incl. 'reply with a Files attachment is received by GreenMail': Reply from read view, body typed, file attached via From Files dialog, draft saved, Send; peer INBOX has 'Re: <subject>' with text, filename and In-Reply-To; Sent has a copy; Drafts empty

## Notes
- ASSUMPTION (2026-10-22 09:07+0200 by dell26/agent-1): Identity picker = the mail accounts (display name + address): there is no identity API yet (task mail-identities); identityId stays the server default / what reply chose. Moving a stored draft to another account recreates it there and discards the old copy; disabled for replies (threading headers) and drafts with attachments, which a new draft cannot carry over
- ASSUMPTION (2026-10-22 09:07+0200 by dell26/agent-1): Autocomplete suggests recent correspondents only (GET /mail/correspondents); contacts come with web-mail-contacts-autocomplete. Recipients are chips; invalid addresses stay as red chips, are left out of autosaves and block Send
- ASSUMPTION (2026-10-22 09:07+0200 by dell26/agent-1): Compose is a page /mail/compose (?draft= | ?reply= | ?replyAll= | ?forward=), reached from a Compose button in the list and Reply/Reply all/Forward buttons in the read view (small edits outside touches); reply/forward drafts are created on open and the URL replaced by ?draft=. Keyboard shortcuts c/r/a/f are not added here
- ASSUMPTION (2026-10-22 09:07+0200 by dell26/agent-1): Autosave: every 10 s (fixed interval, not debounce) the page saves when something changed; all writes go through one queue so each uses the ETag of the previous; a 412 stops autosave and asks to reload; leaving the page saves pending changes. The account signature is not inserted (the server leaves it to the client; not in this task's criteria)
- ASSUMPTION (2026-10-22 09:08+0200 by dell26/agent-1): Editor images (e.g. in quoted replies) are kept in the HTML but shown as [alt] placeholders so the editor never loads remote images; TipTap's StarterKit schema may simplify complex quoted HTML (tables, styles) when the draft is saved
