# Code Review

Date: 2026-10-26

Task: deploy-assetlinks - Serve /.well-known/assetlinks.json for the Android app (passkeys)

Scope: task commit 39494e3de on top of base 9d6cb91ac (`git diff 9d6cb91..HEAD -- . ':!tasks' ':!reviews'`)

Requirements:
- tasks/deploy-assetlinks.md (acceptance criteria and recorded assumptions)
- SPEC.md §4.1 (authentication, passkeys via Credential Manager)
- docs/ARCHITECTURE.md (env table, conventions), AGENTS.md project notes
- Digital Asset Links format for Credential Manager (`delegate_permission/common.get_login_creds`, `android_app` target, upper-case colon-separated `sha256_cert_fingerprints`)

Git range:
- Branch: main
- HEAD: 39494e3dec8866f764c1119dfe46494858119532
- Base/start: 9d6cb91acb8d40e6e97c0f18682a7d1e973714e5
- Commits reviewed: 1 (no uncommitted changes)

Summary: 0 P0, 0 P1, 1 P2, 1 P3

Checked and found correct:
- Security: only `GET /.well-known/assetlinks.json` gets a new `permitAll` (SecurityConfiguration.kt). `/api/**`, `/actuator/**`, `/download/**` and the OpenAPI docs keep their rules, CSRF is unchanged, and Caddy proxies the path unchanged. The response holds only the package name and the public certificate fingerprints. No secrets are in `.env.example`, compose or the tests (the test fingerprints are made up).
- Format: the JSON is `[{relation:["delegate_permission/common.get_login_creds"], target:{namespace:"android_app", package_name, sha256_cert_fingerprints}}]`. Fingerprints are normalized to upper-case hex with colons and must have 64 hex digits, so `apksigner`'s lower-case output without colons also works. The response is `application/json` with `Cache-Control: max-age=3600, public`.
- Origin derivation: `android:apk-key-hash:` + base64url without padding of the 32 raw bytes. Checked against a hand-computed value in the test (`ASNFZ4mrze8...`). The derived origins are added to both the explicit `origins` and the domain defaults.
- Env binding: `COCKPIT_AUTH_PASSKEY_ANDROID_FINGERPRINTS` / `_PACKAGE` map to `cockpit.auth.passkey.android-*` through relaxed binding. Compose passes both, defaulting the package to `ch.sonensei.cockpit`, which matches `applicationId` in `android/app/build.gradle.kts` (no debug suffix, so the documented debug-key fingerprint works with the same package).
- Docs: deploy/README.md, deploy/.env.example, deploy/docker-compose.yml and the ARCHITECTURE.md env table agree with the code. They cover the RP ID != COCKPIT_DOMAIN case and the `apksigner`/`keytool` commands.
- Acceptance criteria 1-3 are implemented. No `tasks.py verify` evidence is recorded yet. That is expected at this stage, and the parent worker still has to record it.

Not done by this reviewer: running the tests (running Gradle was not permitted in this session). The findings below come from reading the code.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Assumed behaviors (empty config, invalid fingerprint, explicit origins plus fingerprints) are untested**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/passkey/AssetLinksWebTests.kt:24`
    - Category: Tests
    - Problem: The only new test class starts one context with two valid fingerprints. Nothing tests: (a) the recorded assumption that without fingerprints the endpoint returns `[]` with 200 (not 404 and not 401). This is the default for every deployment, and the public `permitAll` in the default context depends on it. (b) That `AndroidAssetLinks.normalize` rejects malformed input (wrong length, non-hex), so startup fails as the assumption and ARCHITECTURE.md say. (c) That with explicit `COCKPIT_AUTH_PASSKEY_ORIGINS`, the fingerprint origins are added and do not replace them (`configuredOrigins.ifEmpty {...} + androidOrigins`, PasskeyRelyingParties.kt:55-63). The existing `PasskeyRelyingPartiesTests` were not extended.
    - Why it matters: These are documented, operator-visible behaviors. If one of them regresses, either passkeys in the app turn off without notice (a typo that is accepted) or the endpoint becomes unavailable in default deployments. No test would catch it.
    - Suggested fix: Add plain unit tests for `AndroidAssetLinks` (normalize accepts lower case and input without colons, rejects 63/65 digits and non-hex, `statements()` is empty without fingerprints). Add a `PasskeyRelyingPartiesTests` case with explicit origins plus a fingerprint. Add a MockMvc check in a default-config context (an existing web test class) that `GET /.well-known/assetlinks.json` returns 200 `[]` without login.

## P3 Low

- [ ] **CR-2 | Fingerprint config is parsed in two places; an empty package makes assetlinks and accepted origins disagree**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/passkey/api/PasskeyRelyingParties.kt:41`
    - Category: Quality
    - Problem: `PasskeyRelyingParties` reads `cockpit.auth.passkey.android-fingerprints` itself and builds a private `AndroidAssetLinks("", ...)`, instead of injecting the `AndroidAssetLinks` bean. `AndroidAssetLinks.statements()` returns no statement when the package is blank (AndroidAssetLinks.kt:35), but the relying party still accepts the app origins. So if `COCKPIT_AUTH_PASSKEY_ANDROID_PACKAGE` is set to an empty value outside the compose file (the property then binds to the empty string, not the default), `assetlinks.json` is `[]` while the origins are still accepted, and nothing reports it. This contradicts the KDoc at PasskeyRelyingParties.kt:22 ("the fingerprints that `assetlinks.json` names are exactly the ones whose app origin is accepted").
    - Why it matters: The same configuration has two owners, so they can drift apart, and a blank package turns app passkeys off silently. The design goal for fingerprints was to fail loudly on bad config.
    - Suggested fix: Inject `AndroidAssetLinks` into `PasskeyRelyingParties` and use `assetLinks.origins()`. In `AndroidAssetLinks`, either fail startup when fingerprints are set but the package is blank, or fall back to the default package.

## Review conclusion

No material issues found. CR-1 (a test gap) and CR-2 (a small cleanup) are worth fixing but do not block the acceptance criteria.
