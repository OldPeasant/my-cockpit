---
title: Login with password + TOTP, sessions, CSRF, lockout
status: in_progress
owner: dell26/agent-4
deps: [p0-auth-setup]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/auth,server/src/test/kotlin/ch/sonensei/cockpit/core/auth]
prio: 1
lease_until: 2026-10-20T10:44:20Z
---
## Goal
Web authentication (SPEC.md §4.1). All `/api/**` endpoints are authenticated by default after this task.

## Acceptance criteria
- [ ] `POST /api/v1/auth/login` (username, password, totp | recoveryCode, rememberDevice) creates a session cookie (`HttpOnly`, `Secure` outside dev, `SameSite=Lax`); 30-day sliding lifetime with remember, browser session otherwise; `POST /api/v1/auth/logout`; `GET /api/v1/auth/me`
- [ ] CSRF double-submit cookie `XSRF-TOKEN` / header `X-XSRF-TOKEN` enforced for state-changing requests (test without token → 403)
- [ ] Recovery codes are single-use (second use rejected, test); wrong password/TOTP → 401 with no hint which one was wrong
- [ ] 5 consecutive failures → exponential backoff (429 with `Retry-After`), reset on success (test with the test clock)
- [ ] Test support: `@WithCockpitUser` (or equivalent helper) lets other modules' tests call authenticated endpoints; unauthenticated `/api/**` → 401 (test); `/actuator/health` and the SPA (static resources, index fallback for non-`/api` paths) stay public
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
- ASSUMPTION (2026-10-20 12:24+0200 by dell26/agent-4): Sessions are rows in a new auth_session table (SHA-256 of a random 256-bit cookie token), not servlet HttpSession/Spring Session: they survive restarts for the 30-day sliding lifetime and need no new library; cookie name COCKPIT_SESSION, Path=/
- ASSUMPTION (2026-10-20 12:24+0200 by dell26/agent-4): Without 'remember device' the cookie is a browser-session cookie AND the server ends the session after 12 h without use (SPEC only says 'browser session'); remembered sessions slide 30 days, expiry written at most once per minute
- ASSUMPTION (2026-10-20 12:24+0200 by dell26/agent-4): Login backoff is one global in-memory counter (single account; per-IP would be bypassable behind rotating addresses): failures 1-4 free, from the 5th each failure blocks 30 s doubled per further failure, max 1 h; blocked attempts get 429 + Retry-After without checking credentials and do not count; a success resets. Restart resets it. Trade-off: an attacker can keep the owner from logging in (existing sessions keep working)
- ASSUMPTION (2026-10-20 12:24+0200 by dell26/agent-4): Login and logout require the CSRF header too (login-CSRF protection); the SPA gets XSRF-TOKEN from any first GET (cookie issued on every response that lacks one, also on 401). Setup stays CSRF-exempt as before. XSRF-TOKEN gets SameSite=Lax and the same Secure flag as the session cookie
- ASSUMPTION (2026-10-20 12:24+0200 by dell26/agent-4): Secure cookie flag is controlled by cockpit.auth.secure-cookie (default true, false only in the dev profile); e2e profile keeps Secure (browsers accept Secure cookies on http://localhost)
