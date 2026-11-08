# Fix Report

Date: 2026-11-08

Task: and-mail-images (Android: mail images in the read view)

Scope: current task; base 76a46b9ff9de8a22a4210eadb3abb00e76f80af9, final code a441e181d. Commits after a441e181d touch only `tasks/` and `reviews/` (checked with `git diff a441e181d HEAD --stat -- android server web`: no changes).

The fixer verified both findings against the current code. Both were already resolved in a441e181d, so the fixer made no code changes in this pass.

## Fixed findings

### CR-1 — No tests for the new image behaviour (code-reviewer, P1)

Files changed (in a441e181d, by the test-writer):
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailImagesTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadImagesViewModelTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadImagesGermanTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailWebViewTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadRepositoryTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadScreenTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadTestSupport.kt`

Problem:
Nothing tested request interception, the images banner or the logic that loads images based on settings.

Fix:
The test-writer added tests for server-image matching and refusal, the fetcher run against MockWebServer, CSP and blocking of other hosts, the view model's image policy and allowed-sender flows, the banner UI in English and German, and the repository's proxied-image query. The fixer checked that these files exist in a441e181d. The code-reviewer's updated `reviews/and-mail-images/review.md` ("Status of the earlier finding") confirms that CR-1 is resolved.

Validation (run by the parent worker after a441e181d):
- `./gradlew ktlintFormat ktlintCheck lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.read.*'` — Pass (110 tests)

### TW-1 — Image answers returned with status 0 (test-writer, Low)

Files changed (in a441e181d, by the parent worker):
- `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailImages.kt`

Problem:
`MailImageFetcher` built image answers with the 3-argument `WebResourceResponse`, so `statusCode` was 0.

Fix:
`MailImages.kt:63` now builds them with `WebResourceResponse("${type.type}/${type.subtype}", null, OK, "OK", emptyMap(), response.body.byteStream())`, where `private const val OK = 200` (line 88). This matches the 6-argument `blockedResponse()`. The tests that exposed the defect now assert status 200 (`MailImagesTest` lines 130, 198 and 233).

Validation (run by the parent worker after a441e181d):
- `./gradlew ktlintFormat ktlintCheck lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.read.*'` — Pass (110 tests)

## Not fixed

None.

## Validation summary

The fixer could not run Gradle in this environment. All results below were reported by the parent worker on the code in a441e181d.

Focused tests:
- `./gradlew ktlintFormat ktlintCheck lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.read.*'` — Pass, 110 tests

Full test suite:
- `./gradlew createDebugUnitTestCoverageReport` (full Android unit suite) — Pass

Lint/type-check/build:
- `ktlintCheck lintDebug` (part of the focused command above) — Pass

`.taskcheck`:
- `sh scripts/check.sh` — Pass

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed. Both findings were already resolved in a441e181d. The fixer made no further code changes, so no extra re-review is needed because of this report.
