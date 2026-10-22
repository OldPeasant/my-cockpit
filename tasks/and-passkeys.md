---
title: Android passkeys
status: in_progress
owner: dell26/agent-2
deps: [passkeys-server,and-login,app-logo]
touches: [android/app/src/main/java/ch/sonensei/cockpit/auth,android/app/src/test/java/ch/sonensei/cockpit/auth]
prio: 3
lease_until: 2026-10-22T12:28:09Z
---
## Goal
Passkey login via Credential Manager.

## Acceptance criteria
- [ ] Login with passkey; registration from settings
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*'` passes, covering credential manager result handling (Robolectric/Compose UI tests for screens)

## Notes
