---
title: Device tokens for the Android app
status: in_progress
owner: dell26/agent-5
deps: [p0-auth-login]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/auth,server/src/test/kotlin/ch/sonensei/cockpit/core/auth]
prio: 1
lease_until: 2026-10-20T12:25:06Z
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
