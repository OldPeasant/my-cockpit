---
title: Device tokens for the Android app
status: in_progress
owner: dell26/agent-5
deps: [p0-auth-login]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/auth,server/src/test/kotlin/ch/sonensei/cockpit/core/auth]
prio: 1
lease_until: 2026-10-20T12:49:43Z
---
## Goal
Bearer-token authentication for the Android client with device management (SPEC.md §4.1).

## Acceptance criteria
- [ ] `POST /api/v1/auth/token` (credentials as login + deviceName) returns access token (15 min) + refresh token (opaque, hashed at rest); `POST /api/v1/auth/token/refresh` rotates the refresh token (reuse of an old one revokes the device, test)
- [ ] Bearer access works on all `/api/**` endpoints alongside the session cookie; CSRF not required for bearer requests (tests)
- [ ] `GET /api/v1/auth/devices`, `DELETE /api/v1/auth/devices/{id}` revoke a device; revoked tokens → 401 (test)
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
- ASSUMPTION (2026-10-20 14:11+0200 by dell26/agent-5): Access tokens are opaque random tokens (SHA-256 hash on the auth_device row, looked up per request), not JWTs: no signing key to manage and revocation is immediate.
- ASSUMPTION (2026-10-20 14:12+0200 by dell26/agent-5): Refresh tokens expire after 90 days without a refresh (SPEC only says long-lived); expired devices are deleted lazily on the next token issue/refresh.
- ASSUMPTION (2026-10-20 14:12+0200 by dell26/agent-5): Revoking a device (DELETE or refresh-token reuse) deletes its row; DELETE /auth/devices/{id} does not require If-Match because the device version changes on every refresh (every 15 min).
- ASSUMPTION (2026-10-20 14:12+0200 by dell26/agent-5): POST /auth/token shares the global web login throttle and needs no CSRF token (nor does /token/refresh); requests with a valid bearer token are CSRF-exempt, a request with any bearer header is never authenticated by its session cookie.
- ASSUMPTION (2026-10-20 14:12+0200 by dell26/agent-5): Token endpoint takes password + TOTP/recovery code only; passkey login for Android is left to the passkey task.
