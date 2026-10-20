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
- ASSUMPTION (2026-10-20 17:55+0200 by dell26/agent-6): Tokens are encrypted with AES-256-GCM using a key generated in the Android Keystore directly (javax.crypto), not Tink/EncryptedSharedPreferences: EncryptedSharedPreferences is deprecated and Tink's AndroidKeysetManager falls back to a cleartext keyset when the Keystore fails. Without a usable Keystore the tokens stay in memory only (fail closed).
