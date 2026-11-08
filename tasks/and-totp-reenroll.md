---
title: Android handles forced TOTP re-enrollment
status: in_progress
owner: dell26/agent-1
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/auth,android/app/src/test/java/ch/sonensei/cockpit/auth,android/app/src/main/res/values/strings_auth.xml,android/app/src/main/res/values-de/strings_auth.xml]
prio: 2
lease_until: 2026-11-08T22:34:06Z
---
## Goal
After a passphrase-less restore the server forces TOTP re-enrollment; web has a flow for it
(`features/auth/totp-enrollment`), Android has none, so an Android user would be stuck at login.

## Acceptance criteria
- [x] When login reports that re-enrollment is required, Android shows the enrollment (QR/secret to copy, code confirmation, new recovery codes) or, if that is not feasible, a clear message with a button opening the web enrollment page; record the choice with `tasks.py assume`
  - verified (2026-11-08 23:17+0100 by dell26/agent-1): Chose web enrollment button (assumption recorded: server refuses device tokens with 403 until re-enrolled). cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*' -> 219 passed, incl. AuthRepositoryTest 403->TotpResetRequired + enroll-totp URL (sub-path kept), LoginViewModelTest/PasskeyViewModelsTest URL set on re-enroll result, LoginScreenTest button opens URL via LocalUriHandler and survives no browser
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 23:05+0100 by dell26/agent-1): Android opens the web enrollment page (<server>/enroll-totp, which asks for the web login first) instead of enrolling natively: the server refuses device tokens with 403 until TOTP is re-enrolled and the enrollment endpoints need a web session cookie, so a native flow would need server changes outside this task's touches
