---
title: Android handles forced TOTP re-enrollment
status: in_progress
owner: dell26/agent-1
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/auth,android/app/src/test/java/ch/sonensei/cockpit/auth,android/app/src/main/res/values/strings_auth.xml,android/app/src/main/res/values-de/strings_auth.xml]
prio: 2
lease_until: 2026-11-08T22:24:02Z
---
## Goal
After a passphrase-less restore the server forces TOTP re-enrollment; web has a flow for it
(`features/auth/totp-enrollment`), Android has none, so an Android user would be stuck at login.

## Acceptance criteria
- [ ] When login reports that re-enrollment is required, Android shows the enrollment (QR/secret to copy, code confirmation, new recovery codes) or, if that is not feasible, a clear message with a button opening the web enrollment page; record the choice with `tasks.py assume`
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
