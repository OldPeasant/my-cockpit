---
title: First-run setup: owner account, password hashing, TOTP enrollment
status: todo
owner:
deps: [p0-db-conventions,p0-api-conventions,p0-crypto]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/auth,server/src/test/kotlin/ch/sonensei/cockpit/core/auth,server/src/main/resources/db/migration/core-auth]
prio: 1
---
## Goal
Single-user setup flow (SPEC.md §4.1): available only while no user exists.

## Acceptance criteria
- [ ] `GET /api/v1/setup/status` tells whether setup is needed; `POST /api/v1/setup` creates the user (username, password ≥ 12 chars, Argon2id hash) and returns a TOTP secret + otpauth URI + 10 recovery codes (stored hashed); `POST /api/v1/setup/confirm-totp` activates it with a valid code
- [ ] After a user exists every setup endpoint returns 409/404 (test); the TOTP secret is stored encrypted (p0-crypto)
- [ ] Tests compute TOTP codes from the secret (RFC 6238 test vectors pass); `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
