---
title: CalDAV adapter
status: in_progress
owner: dell26/agent-2
deps: [cal-sync-engine]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/caldav,server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/caldav]
prio: 2
lease_until: 2026-10-21T13:34:05Z
---
## Goal
CalDAV sources (SPEC.md §6.2).

## Acceptance criteria
- [ ] Discovery (principal, calendar-home-set), sync-collection REPORT with fallback to ctag/etag; PUT/DELETE with If-Match
- [ ] `external`-tagged tests against a Radicale Testcontainer: create in cockpit → on server; change on server → in cockpit after sync; conflict (`./gradlew integrationTest --tests '*CalDav*'`)

## Notes
