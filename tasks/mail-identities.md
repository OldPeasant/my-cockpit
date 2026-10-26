---
title: Mail sending identities API and settings
status: in_progress
owner: felix/agent-1
deps: [web-mail-accounts]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/imap,web/src/app/features/settings/mail]
prio: 2
lease_until: 2026-10-26T05:52:06Z
extended: felix/agent-1
---
## Goal
Additional sending identities per mail account (SPEC.md §7.1 "email address(es)/identities"). The
`mail_identity` table exists (mail-model), but the account API (mail-imap) does not manage it, so
Settings → Mail (web-mail-accounts) only edits the account's primary identity (display name + address).

## Acceptance criteria
- [ ] API: list/add/change/remove identities of an account (name, email, reply-to, signature override), ETag/If-Match, Problem Details; `api/openapi.yaml` and web client regenerated; server tests pass
- [ ] Settings → Mail lists an account's identities and adds/edits/removes them; Vitest tests cover it; `cd web && npm run check` passes

## Notes
- ASSUMPTION (2026-10-26 04:50+0100 by felix/agent-1): Identities are additional From addresses besides the account's own emailAddress (which stays the primary one, edited via the account); an identity may repeat the account address. Duplicate address within an account -> 409. Exposed isDefault (moves flag) since compose already starts with the default identity; new identities are appended (position = max+1), no reorder endpoint. Change events type MAIL_IDENTITY.
- ASSUMPTION (2026-10-26 04:56+0100 by felix/agent-1): DraftService (not in this task's touches) already starts compose with the default identity, else the FIRST identity, so adding a non-default identity changes the default From away from the account address. Left as is; UI offers 'Start new mail with this identity'. A follow-up may want compose to fall back to the account address when no identity is default.
- extended by felix/agent-1: Done: server identity API + openapi + regenerated client (dddf7d19), web Settings->Mail identities UI (b9193705), extra tests + code review report (9e503433). Left: (1) check.sh timed out >10 min in 'server: ktlint + tests' after adding MailIdentityWebTests cases - find whether a new test hangs; (2) run fixer for review CR-1 (PATCH accepts blank email -> 400), CR-2 (reword hint: identities change default From; follow-up task mail-compose-identity-fallback exists), CR-3 (refresh after 412/404), CR-4 (disable Edit/Add while saving); (3) test-writer -> tests.md with coverage, code-reviewer again (review.md reviewed pre-rebase hashes 20fb5d9a/2371f113), quality.json; (4) tasks.py verify #1 #2, done.
