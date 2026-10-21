---
title: Mail sending identities API and settings
status: todo
owner:
deps: [web-mail-accounts]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/imap,web/src/app/features/settings/mail]
prio: 2
---
## Goal
Additional sending identities per mail account (SPEC.md §7.1 "email address(es)/identities"). The
`mail_identity` table exists (mail-model), but the account API (mail-imap) does not manage it, so
Settings → Mail (web-mail-accounts) only edits the account's primary identity (display name + address).

## Acceptance criteria
- [ ] API: list/add/change/remove identities of an account (name, email, reply-to, signature override), ETag/If-Match, Problem Details; `api/openapi.yaml` and web client regenerated; server tests pass
- [ ] Settings → Mail lists an account's identities and adds/edits/removes them; Vitest tests cover it; `cd web && npm run check` passes

## Notes
