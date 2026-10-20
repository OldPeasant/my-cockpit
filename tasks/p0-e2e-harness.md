---
title: Playwright e2e harness against the compose stack
status: in_progress
owner: dell26/agent-2
deps: [p0-deploy,p0-web-auth]
touches: [web/playwright.config.ts,web/e2e/support,deploy/docker-compose.e2e.yml,server/src/main/kotlin/ch/sonensei/cockpit/core/testsupport,server/src/test/kotlin/ch/sonensei/cockpit/core/testsupport]
prio: 1
lease_until: 2026-10-20T13:04:51Z
---
## Goal
Browser tests against the real stack, used by all later web tasks and the phase smoke tests.

## Acceptance criteria
- [x] `npm run e2e` brings up `deploy/docker-compose.e2e.yml` (server with profile `e2e`, fresh postgres), waits for health, runs Playwright (chromium), tears down; `npm run e2e -- <spec>` runs one spec
  - verified (2026-10-20 14:44+0200 by dell26/agent-2): cd web && npm run e2e -- e2e/support/smoke.spec.ts (podman-compose, rev 872129d) -> built localhost/cockpit-e2e-72159f73:latest from deploy/docker-compose.e2e.yml (server profile e2e + fresh postgres), stack healthy after 16 s, 2 passed on chromium, torn down, no cockpit-e2e containers left; full 'npm run e2e' -> 2 passed (test-writer, tests.md)
- [x] Fixture `loggedIn` performs setup on a fresh stack (TOTP via otplib) once per worker and stores the session
  - verified (2026-10-20 14:44+0200 by dell26/agent-2): smoke.spec.ts 'the loggedIn fixture opens the shell without signing in' passed in the run above on a fresh stack; npm run e2e -- --workers=3 --repeat-each=3 -> 6 passed (setup once via shared lock, per-worker login; test-writer, tests.md)
- [x] Profile `e2e` only: `POST /api/test/clock {now}` sets the server clock, `POST /api/test/reset` empties the DB; both absent in other profiles (test)
  - verified (2026-10-20 14:44+0200 by dell26/agent-2): cd server && ./gradlew test --tests '*testsupport*' -> 22 tests, 0 failures (TestSupportWebTests$E2eProfile 5: clock + reset; $DefaultProfile 2 and $DevProfile 1: /api/test/** absent; TestSupportLoginWebTests 9; ShiftableClockTests 5)
- [x] One spec `support/smoke.spec.ts` (login, see shell) passes; the run time is recorded in ARCHITECTURE.md (e2e is not part of `.taskcheck`)
  - verified (2026-10-20 14:44+0200 by dell26/agent-2): smoke.spec.ts 2 passed in 2.3 s (stack healthy after 16 s, cached image); run time row in docs/ARCHITECTURE.md:130 (~20 s); sh scripts/check.sh -> all checks passed, does not run e2e (only e2e:typecheck)

## Notes
- ASSUMPTION (2026-10-20 14:06+0200 by dell26/agent-2): e2e test clock is real time + offset (keeps ticking after POST /api/test/clock), not a frozen MutableClock, so sessions/throttle behave normally; reset also resets the clock offset and the in-memory login throttle
- ASSUMPTION (2026-10-20 14:06+0200 by dell26/agent-2): e2e stack has no Caddy: Playwright talks plain HTTP to the server on 127.0.0.1 (secure cookie off via env); /api/test/** is public and CSRF-free in profile e2e only (own security filter chain in core.testsupport)
- ASSUMPTION (2026-10-20 14:06+0200 by dell26/agent-2): loggedIn fixture: setup runs via API once per stack (file lock shared by workers), each worker logs in via API once and reuses the storage state; a non-fresh stack without known credentials is reset first
