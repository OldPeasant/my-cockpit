---
title: Android passkeys
status: todo
owner:
deps: [passkeys-server,and-login]
touches: [android/app/src/main/java/ch/sonensei/cockpit/auth,android/app/src/test/java/ch/sonensei/cockpit/auth]
prio: 3
---
## Goal
Passkey login via Credential Manager.

## Acceptance criteria
- [ ] Login with passkey; registration from settings
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*'` passes, covering credential manager result handling (Robolectric/Compose UI tests for screens)

## Notes
