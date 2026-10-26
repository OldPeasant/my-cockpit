---
title: Serve /.well-known/assetlinks.json for the Android app (passkeys)
status: in_progress
owner: dell26/agent-2
deps: [and-passkeys,deploy-passkey-env]
touches: [deploy,server/src/main/kotlin/ch/sonensei/cockpit/auth]
prio: 2
lease_until: 2026-10-26T06:02:03Z
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
- ASSUMPTION (2026-10-26 06:14+0100 by dell26/agent-2): Config: cockpit.auth.passkey.android-fingerprints / android-package (env COCKPIT_AUTH_PASSKEY_ANDROID_FINGERPRINTS / _PACKAGE, default package ch.sonensei.cockpit). Without fingerprints assetlinks.json is [] (valid, links nothing) rather than 404; an invalid fingerprint fails startup.
- ASSUMPTION (2026-10-26 06:14+0100 by dell26/agent-2): PasskeyRelyingParties now always accepts android:apk-key-hash:<b64url> for each configured fingerprint in addition to origins/defaults, so the app origin needs no manual COCKPIT_AUTH_PASSKEY_ORIGINS entry (explicit origins still work).
- ASSUMPTION (2026-10-26 06:14+0100 by dell26/agent-2): Endpoint is hidden from OpenAPI (not client API), Cache-Control public max-age=1h. Code lives in core/auth/passkey (task touches named server/.../auth which doesn't exist). If RP ID != COCKPIT_DOMAIN the operator must serve the file on the RP ID host (documented).
