---
title: Tickler and scheduled tasks job
status: in_progress
owner: dell26/agent-4
deps: [gtd-recurrence]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/gtd,server/src/test/kotlin/ch/sonensei/cockpit/gtd,server/src/main/resources/db/migration/gtd]
prio: 1
lease_until: 2026-10-20T19:03:38Z
---
## Goal
Ticklers return to the inbox, scheduled tasks become next actions (SPEC.md §5.1).

## Acceptance criteria
- [ ] Tasks with `ticklerDate` are excluded from all list queries until that date
- [ ] Job at 00:05 user-local time (and an on-read check) moves due ticklers to INBOX and due SCHEDULED tasks to NEXT, publishing ChangeEvents
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.*'` passes (Postgres via Testcontainers where persistence is involved) using the test clock (advance 2 days → task back in inbox)

## Notes
