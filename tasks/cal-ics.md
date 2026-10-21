---
title: ICS feed adapter
status: in_progress
owner: dell26/agent-3
deps: [cal-sync-engine]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/ics,server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/ics]
prio: 2
lease_until: 2026-10-21T14:19:52Z
---
## Goal
Read-only ICS subscriptions (SPEC.md §6.1).

## Acceptance criteria
- [ ] Fetch with ETag/Last-Modified, parse, upsert/delete; calendars read-only; SSRF protection (no private/loopback addresses unless allowed in config)
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.ics.*'` passes (Postgres via Testcontainers where persistence is involved) with WireMock feeds

## Notes
