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
- ASSUMPTION (2026-10-20 15:01+0200 by R1963MACOS/agent-1): Registration step-up: the TOTP code is checked when registration starts (options call), not recovery codes; wrong codes count against the shared LoginThrottle and return 403 (not 401, so clients don't treat it as logged out).
- ASSUMPTION (2026-10-20 15:02+0200 by R1963MACOS/agent-1): WebAuthn ceremony challenges are kept in memory (single server instance, 5 min lifetime, single use, max 256 pending, oldest evicted); a restart only makes the user retry.
- ASSUMPTION (2026-10-20 15:02+0200 by R1963MACOS/agent-1): Relying party: cockpit.auth.passkey.rp-id/origins (env COCKPIT_AUTH_PASSKEY_*) configure RP ID and accepted origins incl. android:apk-key-hash; when unset, the request's host/origin is used (dev/e2e work without config). Android's /.well-known/assetlinks.json is left to deployment/and-passkeys.
- ASSUMPTION (2026-10-20 15:02+0200 by R1963MACOS/agent-1): Passkeys are discoverable credentials with userVerification=required and attestation=none (no AAGUID allowlist); algorithms ES256, EdDSA, RS256. Passkey login is a full replacement for password+TOTP and shares the login throttle.
- ASSUMPTION (2026-10-20 15:02+0200 by R1963MACOS/agent-1): Passkeys are auth credentials, not user content: not added to link/search or backup/restore (restoring them would be a security risk; users re-register). Small touch-outside edits: LoginService.startSession, DeviceTokenService.issueFor, SecurityConfiguration permit/CSRF rules, migration core/V202610201300.
