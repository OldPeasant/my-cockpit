---
title: Passkeys (WebAuthn) backend
status: in_progress
owner: dell26/agent-3
deps: [p0-auth-tokens]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/auth/passkey,server/src/test/kotlin/ch/sonensei/cockpit/core/auth/passkey]
prio: 2
lease_until: 2026-10-22T00:31:00Z
releases: 0
extended: 
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
- ASSUMPTION (2026-10-20 15:41+0200 by R1963MACOS/agent-1): Passkey RP defaults to COCKPIT_DOMAIN (rp-id = domain; origins = https://domain plus the request's own origin, http or https with its port, only when the request host equals the domain) so the shipped Caddy stack works without extra config; explicit COCKPIT_AUTH_PASSKEY_* still wins (review CR-1)
- ASSUMPTION (2026-10-20 15:41+0200 by R1963MACOS/agent-1): Registration and authentication ceremonies use separate in-memory stores (256 each) so anonymous authentication-options requests cannot evict pending registrations; authentication churn can still evict other pending logins (review CR-3)
- release by R1963MACOS/agent-1: handed off by R1963MACOS/agent-1: You've hit your session limit · resets 7:50pm (Europe/Zurich)
- ASSUMPTION (2026-10-22 01:29+0200 by dell26/agent-3): TOTP re-enrollment (restored account): passkey registration is refused (403 via fullAccess, and the service ignores the unconfirmed TOTP secret); passkey web login gives a re-enroll-only session; passkey device token returns 403 TotpEnrollmentRequired like the password token. Migration renamed to core/V202610221000 because oauth-connections took V202610201300.
- ASSUMPTION (2026-10-22 02:14+0200 by dell26/agent-3): server/build.gradle.kts test maxHeapSize raised 1g -> 2g (outside touches): ./gradlew check ran out of heap in FilesWebTests/IcsFeedClientTests after ~2230 tests once the passkey tests were added; with 2g it passes (6m22s locally, above the 2 min target, ci-split-check covers check duration)
