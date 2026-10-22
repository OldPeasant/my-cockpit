---
title: Android passkeys
status: done
owner: dell26/agent-2
deps: [passkeys-server,and-login,app-logo]
touches: [android/app/src/main/java/ch/sonensei/cockpit/auth,android/app/src/test/java/ch/sonensei/cockpit/auth]
prio: 3
lease_until: 
releases: 0
extended: 
---
## Goal
Passkey login via Credential Manager.

## Acceptance criteria
- [~] Login with passkey; registration from settings
  - skipped (2026-10-22 15:16+0200 by dell26/agent-2): no Android device/emulator with a passkey provider here, and the server does not serve assetlinks.json yet (task deploy-assetlinks); flow covered only by Robolectric in-app tests up to the system passkey sheet
- [x] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*'` passes, covering credential manager result handling (Robolectric/Compose UI tests for screens)
  - verified (2026-10-22 15:16+0200 by dell26/agent-2): cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*' lintDebug -> BUILD SUCCESSFUL, 185 tests 0 failures (Credential Manager result/exception mapping in PasskeyCredentials tests, MockWebServer repository tests, Robolectric Compose tests for login passkey button and settings passkey section, in-app Hilt tests)

## Notes
- ASSUMPTION (2026-10-22 14:09+0200 by dell26/agent-2): Passkey settings section lists and adds passkeys only; deleting passkeys stays in the web app (criterion only asks for registration).
- release by dell26/agent-2: handed back by dell26/agent-2: pushes had failed (remote: error: GH006: Protected branch update failed for refs/heads/main. | remote: error: | To https://github.com/OldP…); continue from wip/and-passkeys
- ASSUMPTION (2026-10-22 14:44+0200 by dell26/agent-2): Digital Asset Links (/.well-known/assetlinks.json naming the app's package and signing cert) is not served yet; passkeys on a real install need it. Left to new task deploy-assetlinks (deploy/server), outside this task's touches.
- ASSUMPTION (2026-10-22 15:16+0200 by dell26/agent-2): Criterion 1 (real passkey login/registration) verified only at unit/Robolectric level: needs a device plus deploy-assetlinks; to be checked on the owner's phone (h-android-device).
- ASSUMPTION (2026-10-22 15:16+0200 by dell26/agent-2): Review finding CR-6 (P3) accepted: if the passkey list failed to load and the user then adds a passkey, the list shows the new passkey with Retry still offered; Retry reloads the full list.
- done by dell26/agent-2: `sh scripts/check.sh` passed in 54s on e9fcd30b0; criteria verified 1/2, skipped #1
