---
title: Serve /.well-known/assetlinks.json for the Android app (passkeys)
status: in_progress
owner: dell26/agent-2
deps: [and-passkeys,deploy-passkey-env]
touches: [deploy,server/src/main/kotlin/ch/sonensei/cockpit/auth]
prio: 2
lease_until: 2026-10-26T05:31:45Z
---
## Goal
Android Credential Manager only uses passkeys for an RP ID whose domain serves Digital Asset Links naming the app.
Serve `https://<rp-id>/.well-known/assetlinks.json` with `delegate_permission/common.get_login_creds` for package
`ch.sonensei.cockpit` and the signing certificate SHA-256 fingerprint(s), configurable per deployment (env).

## Acceptance criteria
- [ ] `GET /.well-known/assetlinks.json` returns the configured statement as `application/json` without login (test)
- [ ] Package and fingerprint(s) configurable via env in the deploy compose; documented in the deploy README
- [ ] The Android apk-key-hash origin matching the fingerprint is accepted by the passkey RP config (documented)

## Notes
Found while reviewing and-passkeys (see passkeys-server assumption: assetlinks left to deployment).
