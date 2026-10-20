---
title: Device tokens for the Android app
status: in_progress
owner: dell26/agent-5
deps: [p0-auth-login]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/auth,server/src/test/kotlin/ch/sonensei/cockpit/core/auth]
prio: 1
lease_until: 2026-10-20T13:09:54Z
---
## Goal
Bearer-token authentication for the Android client with device management (SPEC.md §4.1).

## Acceptance criteria
- [x] `POST /api/v1/auth/token` (credentials as login + deviceName) returns access token (15 min) + refresh token (opaque, hashed at rest); `POST /api/v1/auth/token/refresh` rotates the refresh token (reuse of an old one revokes the device, test)
  - verified (2026-10-20 14:45+0200 by dell26/agent-5): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*' (Testcontainers Postgres) -> DeviceTokenWebTests/EdgeCaseTests pass: token issue returns 15-min access + opaque refresh token, only SHA-256 hashes stored; refresh rotates; reuse of retired refresh token deletes device (incl. concurrent refresh test)
- [x] Bearer access works on all `/api/**` endpoints alongside the session cookie; CSRF not required for bearer requests (tests)
  - verified (2026-10-20 14:45+0200 by dell26/agent-5): same run -> bearer GET/PATCH on /api/** without CSRF pass, cookie still needs CSRF (403), invalid/empty/expired bearer next to cookie -> 401, other schemes keep cookie+CSRF (DeviceTokenWebTests, DeviceTokenEdgeCaseTests)
- [x] `GET /api/v1/auth/devices`, `DELETE /api/v1/auth/devices/{id}` revoke a device; revoked tokens → 401 (test)
  - verified (2026-10-20 14:45+0200 by dell26/agent-5): same run -> GET /api/v1/auth/devices lists devices, DELETE /api/v1/auth/devices/{id} 204/404, revoked device's access and refresh tokens -> 401 (DeviceTokenWebTests)
- [x] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` passes (Postgres via Testcontainers where persistence is involved)
  - verified (2026-10-20 14:45+0200 by dell26/agent-5): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*' --tests '*OpenApiContractTest' at 679bcaf (Postgres via Testcontainers) -> 166 tests, 0 failures, 0 errors
- [x] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag
  - verified (2026-10-20 14:45+0200 by dell26/agent-5): cd server && ./gradlew updateOpenApi (UPDATE_OPENAPI path) after rebase -> api/openapi.yaml regenerated with token/refresh/devices ops; then OpenApiContractTest without flag -> pass, file unchanged; cd web && npm run api:check -> generated client up to date

## Notes
- ASSUMPTION (2026-10-20 14:11+0200 by dell26/agent-5): Access tokens are opaque random tokens (SHA-256 hash on the auth_device row, looked up per request), not JWTs: no signing key to manage and revocation is immediate.
- ASSUMPTION (2026-10-20 14:12+0200 by dell26/agent-5): Refresh tokens expire after 90 days without a refresh (SPEC only says long-lived); expired devices are deleted lazily on the next token issue/refresh.
- ASSUMPTION (2026-10-20 14:12+0200 by dell26/agent-5): Revoking a device (DELETE or refresh-token reuse) deletes its row; DELETE /auth/devices/{id} does not require If-Match because the device version changes on every refresh (every 15 min).
- ASSUMPTION (2026-10-20 14:12+0200 by dell26/agent-5): POST /auth/token shares the global web login throttle and needs no CSRF token (nor does /token/refresh); requests with a valid bearer token are CSRF-exempt, a request with any bearer header is never authenticated by its session cookie.
- ASSUMPTION (2026-10-20 14:12+0200 by dell26/agent-5): Token endpoint takes password + TOTP/recovery code only; passkey login for Android is left to the passkey task.
