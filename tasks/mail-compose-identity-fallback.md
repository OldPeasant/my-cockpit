---
title: New mail starts From the account address unless an identity is default
status: done
owner: dell26/agent-2
deps: [mail-identities]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/compose]
prio: 2
lease_until: 
extended: dell26/agent-2
---
## Goal
`DraftService.identity` starts a new draft with the default identity, else the FIRST identity of the
account. Since mail-identities lets users add extra identities, adding one non-default identity silently
changes the From of every new mail and the account's own address can no longer be chosen (review
mail-identities CR-2). A new draft without an explicit identity should be From the account address
(display name + emailAddress) unless an identity is marked default.

## Acceptance criteria
- [x] New draft with no identityId and no default identity is From the account's display name + address; with a default identity it uses that one; server tests cover both and pass
  - verified (2026-10-26 08:39+0100 by dell26/agent-2): cd server && ./gradlew integrationTest --tests 'ch.sonensei.cockpit.mail.compose.*' (GreenMail + Postgres Testcontainers, Docker) -> 32 tests 0 failures; test 'a new draft is From the account address unless an identity is default' covers no default (From Jane Doe + account address, also on received mail) and default identity

## Notes
- ASSUMPTION (2026-10-26 07:48+0100 by dell26/agent-2): Fixed the identities settings hint text (web/src/app/features/settings/mail) and API identityId descriptions too, outside touches, so UI/API docs match the new account-address fallback (review CR-1, CR-2)
- extended by dell26/agent-2: Implementation, CR-1/CR-2 fixes and final re-review (review.md, 0 findings) are committed. Left: re-run test-writer on final code (tests.md predates the CR-1/CR-2 fixes), run cd server && ./gradlew integrationTest (DraftComposeGreenMailTests) and record it with tasks.py verify 1, write+commit quality.json (reviewed_revision = HEAD), run .taskcheck, then tasks.py done.
- done by dell26/agent-2: `sh scripts/check.sh` passed in 86s on 2ddcab322; criteria verified 1/1
