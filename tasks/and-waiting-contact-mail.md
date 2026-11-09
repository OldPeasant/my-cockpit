---
title: Android Draft email: To from the linked waiting-for contact
status: in_progress
owner: dell26/agent-1
deps: [gtd-links-everywhere]
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/data,android/app/src/main/java/ch/sonensei/cockpit/gtd/lists,android/app/src/test/java/ch/sonensei/cockpit/gtd/lists]
prio: 2
lease_until: 2026-11-09T10:04:39Z
---
## Goal
SPEC.md §5.3: waiting "Follow up" → "Draft email" prefills To from the linked contact. Web does this
(gtd-links-everywhere); Android only parses an address from the waiting-for text, because the Android
`GtdTask` model/cache does not carry `waitingForContactId` although the API provides it
(code review CR-3 of gtd-links-everywhere).

## Acceptance criteria
- [ ] Android GtdTask (model, mapper, cache) carries `waitingForContactId`
- [ ] Android "Draft email" uses the linked contact's first email address as To, falling back to an address in the waiting-for text
- [ ] Unit tests cover both cases; `sh scripts/check.sh` passes

## Notes
- ASSUMPTION (2026-11-09 10:25+0100 by dell26/agent-1): Android Draft email: if the linked contact cannot be loaded (offline, deleted, no address) the To falls back to an address in the waiting-for text; the contact is fetched live when the button is pressed (contacts have no Android cache). GtdCacheDatabase bumped to v3 with destructive fallback (cache only).
