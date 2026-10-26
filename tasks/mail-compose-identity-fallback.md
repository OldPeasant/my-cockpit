---
title: New mail starts From the account address unless an identity is default
status: in_progress
owner: dell26/agent-2
deps: [mail-identities]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/compose]
prio: 2
lease_until: 2026-10-26T06:52:34Z
---
## Goal
`DraftService.identity` starts a new draft with the default identity, else the FIRST identity of the
account. Since mail-identities lets users add extra identities, adding one non-default identity silently
changes the From of every new mail and the account's own address can no longer be chosen (review
mail-identities CR-2). A new draft without an explicit identity should be From the account address
(display name + emailAddress) unless an identity is marked default.

## Acceptance criteria
- [ ] New draft with no identityId and no default identity is From the account's display name + address; with a default identity it uses that one; server tests cover both and pass

## Notes
