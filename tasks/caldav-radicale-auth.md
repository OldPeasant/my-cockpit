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
- [x] CalDAV Radicale tests run with htpasswd auth (log shows auth type htpasswd); `./gradlew integrationTest --tests '*CalDavRadicale*'` passes
  - verified (2026-10-26 12:57+0100 by dell26/agent-1): cd server && ./gradlew integrationTest --tests '*CalDavRadicale*' -> BUILD SUCCESSFUL, CalDavRadicaleSyncTests 11 tests, 0 failures/errors (new test: unauthenticated PROPFIND -> 401); podman run docker.io/tomsquest/docker-radicale:3.5.4.0 /venv/bin/radicale --config /etc/radicale/config with the test's config -> log "auth type is 'radicale.auth.htpasswd'", "auth htpasswd file: '/etc/radicale/users'"

## Notes
