---
title: Passkeys (WebAuthn) backend
status: in_progress
owner: R1963MACOS/agent-1
deps: [p0-auth-tokens]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/auth/passkey,server/src/test/kotlin/ch/sonensei/cockpit/core/auth/passkey]
prio: 2
lease_until: 2026-10-20T13:21:13Z
---
## Goal
Passkeys as an alternative to password + TOTP (SPEC.md §4.1).

## Acceptance criteria
- [ ] Registration (requires an authenticated session + TOTP) and authentication ceremonies with webauthn4j; credential list/delete; works for web sessions and Android token login
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.passkey.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
