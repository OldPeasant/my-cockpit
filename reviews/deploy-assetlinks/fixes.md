# Fix Report

Date: 2026-10-26

Task: deploy-assetlinks - Serve /.well-known/assetlinks.json for the Android app (passkeys)

Scope: current task. Finding CR-2 from `reviews/deploy-assetlinks/review.md`, approved by the parent worker. Base 97c0d47eb, task commits 7a7511dfb (implementation) and b4e39ad8d (tests).

## Fixed findings

### CR-2 — Fingerprint config is parsed in two places; an empty package makes assetlinks and accepted origins disagree

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/passkey/api/AndroidAssetLinks.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/passkey/api/PasskeyRelyingParties.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/passkey/AndroidAssetLinksTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/passkey/PasskeyRelyingPartiesTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/passkey/PasskeyConfiguredRelyingPartyTests.kt`
- `docs/ARCHITECTURE.md` (one sentence in the env table row)

Problem:
`PasskeyRelyingParties` read `cockpit.auth.passkey.android-fingerprints` itself and built a private `AndroidAssetLinks("", ...)`. When the package was blank (for example `COCKPIT_AUTH_PASSKEY_ANDROID_PACKAGE=` outside the compose file), `assetlinks.json` served `[]` but the app origins were still accepted, and nothing reported the mismatch.

Fix:
- `PasskeyRelyingParties` now takes the `AndroidAssetLinks` bean as a constructor parameter, with no default, so a missing bean fails loudly. It uses `assetLinks.origins()`. Its own `@Value` for the fingerprints is gone, so `AndroidAssetLinks` is now the only class that reads the config.
- `AndroidAssetLinks` has an `init` check: when fingerprints are configured but the trimmed package is blank, startup fails with an `IllegalArgumentException` that names `cockpit.auth.passkey.android-package`. `statements()` no longer needs its blank-package branch. The KDoc was updated, and so was the ARCHITECTURE.md env row ("An invalid fingerprint, or fingerprints with a blank package, fail startup").
- Tests:
  - `PasskeyRelyingPartiesTests` builds through a private `relyingParties(...)` helper that passes `AndroidAssetLinks("ch.sonensei.cockpit", fingerprints)`. Both `ApplicationContextRunner`s also register `AndroidAssetLinks`. All 12 cases are kept, assertions unchanged.
  - `PasskeyConfiguredRelyingPartyTests` passes a `NO_APP` instance (no fingerprints), so its behavior is unchanged.
  - `AndroidAssetLinksTests` has two new tests: a blank or whitespace package with a fingerprint throws, with a message naming the property; and an empty `COCKPIT_AUTH_PASSKEY_ANDROID_PACKAGE` env value with fingerprints fails context startup.
  - One assertion changed: `AndroidAssetLinks("  ", listOf(COLONS)).statements().shouldBeEmpty()` described the old, silent behavior that CR-2 removes, and it now throws. The new throwing test covers it. The test that held it was renamed and checks that a blank package without fingerprints is still accepted and yields no statement and no origin.

Validation:
- `cd server && ./gradlew ktlintFormat` — Pass (no remaining changes)
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.passkey.*' ktlintCheck` — Pass (10 classes, 89 tests, 0 failures/errors/skipped; AndroidAssetLinksTests 11, PasskeyRelyingPartiesTests 12, PasskeyConfiguredRelyingPartyTests 4, AssetLinksWebTests 2, AssetLinksDefaultWebTests 4)

## Not fixed

### CR-1 — Assumed behaviors (empty config, invalid fingerprint, explicit origins plus fingerprints) are untested

Status:
Invalid (already resolved)

Reason:
The test-writer commit b4e39ad8d already covers it (AndroidAssetLinksTests, AssetLinksDefaultWebTests, and the explicit-origins case in PasskeyRelyingPartiesTests). The parent worker said not to redo it, so no change was made.

## Validation summary

Focused tests:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.passkey.*'` — Pass (89 tests)

Full test suite:
- `sh scripts/check.sh` (server check, web check, android ktlint + unit tests + lint) — Pass ("all checks passed")

Lint/type-check/build:
- `cd server && ./gradlew ktlintCheck` — Pass. detekt is not configured in this project (server/build.gradle.kts applies only ktlint).

`.taskcheck`:
- Pass (`sh scripts/check.sh`)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
