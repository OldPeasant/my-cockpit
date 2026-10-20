---
title: Android login and secure token storage
status: in_progress
owner: dell26/agent-5
deps: [and-api-client]
touches: [android/app/src/main/java/ch/sonensei/cockpit/auth,android/app/src/test/java/ch/sonensei/cockpit/auth]
prio: 1
lease_until: 2026-10-20T20:21:11Z
extended: 
releases: 1
---
## Goal
Login screen and token storage (SPEC.md §9.2 item 1).

## Acceptance criteria
- [x] Login screen: server URL, username, password, TOTP / recovery code; errors and 429 backoff shown
  - verified (2026-10-20 22:02+0200 by dell26/agent-5): cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*' (Robolectric Compose LoginScreenTest + LoginViewModelTest: URL/username/password/TOTP+recovery-code fields, invalid-credential/network errors, 429 Retry-After countdown) -> BUILD SUCCESSFUL, 122 tests, 0 failures at 3334c04d
- [~] Tokens stored encrypted (Keystore-backed); app start routes to login or home; logout clears tokens and caches
  - skipped (2026-10-20 22:02+0200 by dell26/agent-5): Encryption runs through AES-256-GCM with a Keystore key; unit tests (122 pass) cover token persistence via a JVM cipher stand-in, app-start routing to login/home and logout clearing tokens+caches, but the real AndroidKeyStore round trip needs an emulator/device, unavailable here (deferred to h-android-device)
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*'` passes, covering login success/failure states, token persistence, logout (Robolectric/Compose UI tests for screens)

## Notes
- ASSUMPTION (2026-10-20 17:55+0200 by dell26/agent-6): Tokens are encrypted with AES-256-GCM using a key generated in the Android Keystore directly (javax.crypto), not Tink/EncryptedSharedPreferences: EncryptedSharedPreferences is deprecated and Tink's AndroidKeysetManager falls back to a cleartext keyset when the Keystore fails. Without a usable Keystore the tokens stay in memory only (fail closed).
- ASSUMPTION (2026-10-20 17:55+0200 by dell26/agent-6): Passkey login (SPEC 9.2 item 1) is left to the and-passkeys task; this task's criteria only name password + TOTP/recovery code.
- ASSUMPTION (2026-10-20 17:55+0200 by dell26/agent-6): Logout revokes this device on the server (DELETE /api/v1/auth/devices/{own id}, best effort, 5 s timeout) before clearing tokens, so a copied refresh token stops working; offline logout still clears locally.
- ASSUMPTION (2026-10-20 17:56+0200 by dell26/agent-6): 'Caches' = a SessionCache Hilt multibinding that feature tasks contribute to (e.g. Room read cache); for now it clears the app cacheDir. It runs on any logout, also a refresh rejection by the network layer.
- ASSUMPTION (2026-10-20 17:56+0200 by dell26/agent-6): No Android settings task exists, so the Log out button sits under the Settings placeholder (settings/SettingsScreen.kt), via LocalLogout so shell-only tests need no Hilt. A 429 without a numeric Retry-After waits 30 s.
- extended by dell26/agent-6: Implementation + tests committed (4acb54a); first code review in reviews/and-login/review.md (0 P0/P1, 2 P2, 2 P3). Left: fix CR-1 (logout cancelled by viewModelScope before tokens cleared; regression test AuthRepositoryTest already added and currently failing) and other review findings via fixer; run test-writer to write tests.md with coverage; re-review final code; tasks.py verify 1-3; .taskcheck; quality.json; done.
- release by dell26/agent-6: Implementation complete (Keystore AES-GCM token store, login screen with TOTP/recovery + 429 backoff, start-up routing, logout clearing tokens+caches). Review findings CR-1/3/4 fixed (fddeb97, fixes.md). Extra repo/VM tests committed (770d48a). sh scripts/check.sh passes at 770d48a. Left only finalization: run test-writer on final code -> reviews/and-login/tests.md with coverage; re-run code-reviewer on final code (review.md is stale after fixes); tasks.py verify and-login 1-3; quality.json; done.
