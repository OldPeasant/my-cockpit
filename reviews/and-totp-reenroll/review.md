# Code Review

Date: 2026-11-08

Task: and-totp-reenroll - Android handles forced TOTP re-enrollment

Scope: task commits bdd0a970f and e3be6baaa on top of base 27218eaaf (`git diff 27218eaaf..HEAD -- android`), committed changes only

Requirements:
- tasks/and-totp-reenroll.md acceptance criteria and recorded assumption (open the web enrollment page instead of a native flow)
- docs/PARITY.md (row "Forced TOTP re-enrollment unsupported (user stuck after restore)")
- AGENTS.md project notes (i18n, lint, conventions)
- Server context: server/src/main/kotlin/ch/sonensei/cockpit/core/auth/api/DeviceTokenController.kt, TotpEnrollmentController.kt, core/spa/SpaWebConfiguration.kt
- Web context: web/src/app/app.routes.ts (`enroll-totp`), web/src/app/core/auth/auth.guards.ts (totpEnrollmentGuard)

Git range:
- Branch: main
- HEAD: e3be6baaa
- Base/start: 27218eaafecd0a876c80862f7075c6c5da1382de
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Verification notes

What was checked against the current code:

- Server contract: `DeviceTokenController.rejected` returns 403 only for `LoginResult.TotpEnrollmentRequired`; every other rejection is 401 or 429. Mapping 403 to `LoginResult.TotpResetRequired` in `AuthRepository.login` (AuthRepository.kt:215) therefore matches the server, and the passkey path already maps 403 the same way (PasskeyRepository.kt:149). The test that used to expect 403 to be a server error was changed to match. That change is correct, not a weakened test.
- Enrollment URL: `AuthRepository.totpEnrollmentUrl()` resolves `enroll-totp` against the stored server URL. `ServerUrlValidator.validate` always normalizes the path to end with `/`, so a path prefix such as `https://host/cockpit/` resolves to `/cockpit/enroll-totp`. Both `AuthRepository.login` and `PasskeyRepository.login` store the server URL (`setServerUrl`) before they send the request, so the URL exists even on the first login after a fresh install. The server's SPA configuration answers client routes with `index.html`. The web `totpEnrollmentGuard` sends a visitor without a session to the login and then back to the enrollment, which matches the recorded assumption.
- State handling: `submit` and `submitPasskey` clear `totpEnrollmentUrl` when an attempt starts and set it only for `TotpResetRequired`. The screen shows the button only when the error is `TOTP_RESET_REQUIRED` and a URL is present. Editing a field clears that error, which also hides the button. A leftover URL has no visible effect. `toString` still masks credentials and includes only the public URL.
- If no browser can handle the link, `openUri` is wrapped in `runCatching`, so the app does not crash. The existing error message still tells the user what to do.
- i18n: the new `login_open_totp_enrollment` string exists in both `values/strings_auth.xml` and `values-de/strings_auth.xml`. The message itself reuses the existing `login_error_totp_reset_required` string, which is already in English and German.
- Tests: there are repository tests (403 gives TotpResetRequired, nothing is stored, the URL is correct), ViewModel tests for the password path (the URL is set and then cleared on the next attempt, and is fetched once) and the passkey path, and Compose tests (a click opens the URL through an injected `UriHandler`; the button is hidden without the error or without a URL). Together they cover the new behavior.
- Scope: every change is inside the task's `touches`. No server, web or build files changed.

Evidence status (for the parent, not a finding): `tasks.py verify and-totp-reenroll` shows all three criteria still unverified. Criterion 3 names `./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh`. The run the parent reported (`ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*'`) covers only part of that. lintDebug, the full unit suite and check.sh still need to run before criterion 3 can be recorded. This reviewer did not run Gradle.

## Review conclusion

No material issues found.
