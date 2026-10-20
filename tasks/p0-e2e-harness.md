---
title: Playwright e2e harness against the compose stack
status: in_progress
owner: dell26/agent-2
deps: [p0-deploy,p0-web-auth]
touches: [web/playwright.config.ts,web/e2e/support,deploy/docker-compose.e2e.yml,server/src/main/kotlin/ch/sonensei/cockpit/core/testsupport,server/src/test/kotlin/ch/sonensei/cockpit/core/testsupport]
prio: 1
lease_until: 2026-10-20T12:19:24Z
---
## Goal
Browser tests against the real stack, used by all later web tasks and the phase smoke tests.

## Acceptance criteria
- [ ] `npm run e2e` brings up `deploy/docker-compose.e2e.yml` (server with profile `e2e`, fresh postgres), waits for health, runs Playwright (chromium), tears down; `npm run e2e -- <spec>` runs one spec
- [ ] Fixture `loggedIn` performs setup on a fresh stack (TOTP via otplib) once per worker and stores the session
- [ ] Profile `e2e` only: `POST /api/test/clock {now}` sets the server clock, `POST /api/test/reset` empties the DB; both absent in other profiles (test)
- [ ] One spec `support/smoke.spec.ts` (login, see shell) passes; the run time is recorded in ARCHITECTURE.md (e2e is not part of `.taskcheck`)

## Notes
