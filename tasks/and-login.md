---
title: Android login and secure token storage
status: in_progress
owner: dell26/agent-6
deps: [and-api-client]
touches: [android/app/src/main/java/ch/sonensei/cockpit/auth,android/app/src/test/java/ch/sonensei/cockpit/auth]
prio: 1
lease_until: 2026-10-20T16:11:18Z
---
## Goal
Login screen and token storage (SPEC.md §9.2 item 1).

## Acceptance criteria
- [ ] Login screen: server URL, username, password, TOTP / recovery code; errors and 429 backoff shown
- [ ] Tokens stored encrypted (Keystore-backed); app start routes to login or home; logout clears tokens and caches
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*'` passes, covering login success/failure states, token persistence, logout (Robolectric/Compose UI tests for screens)

## Notes
