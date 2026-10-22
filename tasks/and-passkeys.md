---
title: Android passkeys
status: in_progress
owner: dell26/agent-2
deps: [passkeys-server,and-login,app-logo]
touches: [android/app/src/main/java/ch/sonensei/cockpit/auth,android/app/src/test/java/ch/sonensei/cockpit/auth]
prio: 3
lease_until: 2026-10-22T13:06:16Z
releases: 0
extended: 
---
## Goal
Passkey login via Credential Manager.

## Acceptance criteria
- [ ] Login with passkey; registration from settings
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*'` passes, covering credential manager result handling (Robolectric/Compose UI tests for screens)

## Notes
- ASSUMPTION (2026-10-22 14:09+0200 by dell26/agent-2): Passkey settings section lists and adds passkeys only; deleting passkeys stays in the web app (criterion only asks for registration).
- release by dell26/agent-2: handed back by dell26/agent-2: pushes had failed (remote: error: GH006: Protected branch update failed for refs/heads/main. | remote: error: | To https://github.com/OldP…); continue from wip/and-passkeys
- ASSUMPTION (2026-10-22 14:44+0200 by dell26/agent-2): Digital Asset Links (/.well-known/assetlinks.json naming the app's package and signing cert) is not served yet; passkeys on a real install need it. Left to new task deploy-assetlinks (deploy/server), outside this task's touches.
