# Code Review

Date: 2026-11-08

Task: and-totp-reenroll - Android handles forced TOTP re-enrollment

Scope: whole task diff at the final revision, `git diff 27218eaaf..c5a28927 -- android` (commits bdd0a970f, e3be6baaa, c5a28927), committed changes only. This re-review looks mainly at the tests the test-writer added in c5a28927.

Reviewed revision: c5a28927658248da815f9bad1041d10ba20307b0

Requirements:
- tasks/and-totp-reenroll.md acceptance criteria and the recorded assumption (open the web enrollment page `{server}/enroll-totp` instead of enrolling natively)
- docs/PARITY.md (row "Forced TOTP re-enrollment unsupported (user stuck after restore)")
- AGENTS.md project notes (i18n, lint, conventions, test data is fictitious)
- reviews/and-totp-reenroll/tests.md (test-writer report)
- Server and web context from the earlier review: DeviceTokenController (403 only for TOTP enrollment required), web route `enroll-totp` with totpEnrollmentGuard

Git range:
- Branch: main
- HEAD: c5a28927658248da815f9bad1041d10ba20307b0
- Base/start: 27218eaafecd0a876c80862f7075c6c5da1382de
- Commits reviewed: 3
- Uncommitted changes: none

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

- Production code is unchanged since the earlier review. `git show --stat c5a28927` lists only the three auth test files and the two review reports. The production findings of the earlier review still apply: 403 maps to `LoginResult.TotpResetRequired`, the enrollment URL resolves against the stored server URL, the URL is cleared when a new attempt starts, the button appears only with `TOTP_RESET_REQUIRED` and a URL, `openUri` is wrapped in `runCatching`, and the string exists in English and German.
- AuthRepositoryTest, test `the web enrollment page stays below a server address with a sub-path`: the server is entered as `{mockserver}/cockpit` without a trailing slash. `ServerUrlValidator` (core/network/ServerUrl.kt:83) adds the slash, so `resolve("enroll-totp")` gives `/cockpit/enroll-totp`. The test checks the real normalization path, not a mock, so it would catch a regression to `/enroll-totp`.
- AuthRepositoryTest, test `without a configured server there is no web enrollment page`: this checks the null branch of `totpEnrollmentUrl()` against the real `ServerUrls` store.
- LoginViewModelTest, test `a TOTP re-enrollment without a known enrollment page still shows the error`: the repository mock is strict (`mockk<AuthRepository>()`), and `totpEnrollmentUrl()` is explicitly stubbed to return null. The test checks that the error is shown, no URL is set, and `submitting` ends. Other tests in the file do not call `totpEnrollmentUrl()`, because it is fetched only for `TotpResetRequired`, so the strict mock does not cause hidden failures.
- LoginScreenTest, test `the web enrollment button is labelled in German`: this uses `@Config(qualifiers = "de")`, the same pattern the file already uses at line 269. It confirms that the German resource is the one actually rendered (criterion 2).
- LoginScreenTest, test `the web enrollment button does not crash without a browser`: the injected `UriHandler` throws. Without the `runCatching` in `OpenTotpEnrollmentButton`, `performClick` would throw and the test would fail, so it really guards the no-browser path. It also checks that the error and the button remain on screen.
- The tests assert observable behavior (the text shown, the URI opened, the UI state, nothing stored), not implementation details. No existing test was weakened. The only edit to an existing test moved 403 out of the "server errors" list, and this matches the server contract, as the earlier review explained.
- Test data uses fictitious hosts (`cockpit.example.org`, MockWebServer) and fictitious credentials.
- The passkey path with a null enrollment URL has no dedicated test. It uses the same code as the password path, which is tested, so this gap is not material.
- Scope: every changed file is inside the task's `touches`.

Evidence status (for the parent, not a finding): tests.md records `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*'` with 219 passed. Criterion 3 also names the full `testDebugUnitTest`, `lintDebug` and `sh scripts/check.sh`. These must run on this revision before criterion 3 can be recorded. This reviewer did not run Gradle.

## Review conclusion

No material issues found.
