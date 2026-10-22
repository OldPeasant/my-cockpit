---
title: Birthdays calendar
status: in_progress
owner: dell26/agent-2
deps: [contacts-api,cal-local-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/contacts/birthdays,server/src/test/kotlin/ch/sonensei/cockpit/contacts/birthdays]
prio: 2
lease_until: 2026-10-22T02:45:45Z
---
## Goal
Virtual read-only birthdays calendar (SPEC.md §8.3).

## Acceptance criteria
- [ ] Birthdays appear as yearly all-day occurrences in `GET /calendar/events` from a virtual calendar; Feb 29 handled
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.birthdays.*'` passes (Postgres via Testcontainers where persistence is involved)

## Notes
