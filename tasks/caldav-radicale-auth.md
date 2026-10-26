---
title: CalDAV Radicale tests run without authentication
status: in_progress
owner: dell26/agent-1
deps: []
touches: [server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/caldav]
prio: 2
lease_until: 2026-10-26T12:17:06Z
---
## Goal
CalDavRadicaleSyncTests copies its Radicale config to /config/config, but the image fills its /config volume with
the default config at start (`[auth] type = none`), so the tests never check authentication. Fix as in
CardDavRadicaleSyncTests (config under /etc/radicale, `withCommand(... --config /etc/radicale/config)`).

## Acceptance criteria
- [ ] CalDAV Radicale tests run with htpasswd auth (log shows auth type htpasswd); `./gradlew integrationTest --tests '*CalDavRadicale*'` passes

## Notes
