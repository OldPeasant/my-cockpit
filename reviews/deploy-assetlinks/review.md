# Code Review

Date: 2026-10-26

Task: deploy-assetlinks - Serve /.well-known/assetlinks.json for the Android app (passkeys)

Scope: task commits 3e2f9b84b (implementation), 362116997 (tests), d38ea3748 (fix) on top of base d026fa584 (`git diff d026fa584..HEAD -- . ':!tasks' ':!reviews'`)

Requirements:
- tasks/deploy-assetlinks.md (acceptance criteria and recorded assumptions)
- SPEC.md §4.1 (passkeys via Credential Manager on Android), §3 auth stack
- docs/ARCHITECTURE.md (env table, conventions), AGENTS.md project notes
- Digital Asset Links format for Credential Manager (`delegate_permission/common.get_login_creds`, `android_app` target, upper-case colon-separated `sha256_cert_fingerprints`)
- Previous review of this task and reviews/deploy-assetlinks/fixes.md (CR-1, CR-2 of the earlier revision)

Git range:
- Branch: main
- HEAD: d38ea3748b56af33ec605deea86eacc4ab287ac8
- Base/start: d026fa584
- Commits reviewed: 3 (no uncommitted changes)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Checked and found correct:
- Criterion 1: `AssetLinksController` serves `GET /.well-known/assetlinks.json` with `produces = application/json` and `Cache-Control: max-age=3600, public`. The body is `[{relation:["delegate_permission/common.get_login_creds"], target:{namespace:"android_app", package_name, sha256_cert_fingerprints}}]`, with Jackson names set through `@get:JsonProperty`. `AssetLinksWebTests` covers the configured case without login, and `AssetLinksDefaultWebTests` covers the default `[]` case, HEAD, the fact that POST/PUT/DELETE still need a login, that `/api/v1/.well-known/...` is not opened, and that the endpoint is absent from the OpenAPI contract.
- Security: the only new `permitAll` is `HttpMethod.GET` on the exact path (SecurityConfiguration.kt). CSRF and the `/api/**`, `/actuator/**`, `/download/**` and OpenAPI rules are unchanged. Caddy proxies the path as is (the Caddyfile only blocks non-health actuator paths). The response holds only the package name and public certificate fingerprints. No secrets are in `.env.example`, compose, docs or tests, and the test fingerprints are fictitious.
- Criterion 3: `AndroidAssetLinks.apkKeyHashOrigin` is `android:apk-key-hash:` + unpadded base64url of the 32 raw bytes. I recomputed the three expected test values independently with Python (`qxLNNO9W...`, `ASNFZ4mr...`, `___...8`), and all match. `PasskeyRelyingParties.resolve` adds `androidOrigins` to the whole `configuredOrigins.ifEmpty { ... }` result (the `+` applies to the `ifEmpty` call, not just to the inner `else` branch). So app origins are added to explicit origins, to the domain defaults and to the request-derived dev default. Tests cover all three cases.
- Single owner (earlier CR-2 is resolved): `PasskeyRelyingParties` takes the `AndroidAssetLinks` bean and no longer reads the fingerprint property. Fingerprints with a blank package fail at construction, so `assetlinks.json` and the accepted origins cannot disagree. Invalid fingerprints (wrong length, non-hex, other separators, labels) fail startup, and tests cover this through `ApplicationContextRunner` with env-style property sources.
- Criterion 2 / docs consistency: `deploy/docker-compose.yml` forwards `COCKPIT_AUTH_PASSKEY_ANDROID_FINGERPRINTS` (default empty) and `_PACKAGE` (default `ch.sonensei.cockpit`). This matches `.env.example` ("Empty: ch.sonensei.cockpit"), the deploy README table, the ARCHITECTURE.md env row, the code default, and `applicationId` in `android/app/build.gradle.kts` (no debug suffix). The README explains the RP ID != COCKPIT_DOMAIN case, fingerprint formats, `apksigner`/`keytool` usage and how to check the origin. Its Python one-liner gives the same encoding as the server.
- Test evidence: the latest Gradle results in `server/build/test-results/test` for `core.auth.passkey.*` show 10 classes and 89 tests with 0 failures, errors or skips (AndroidAssetLinksTests 11, PasskeyRelyingPartiesTests 12, AssetLinksWebTests 2, AssetLinksDefaultWebTests 4, PasskeyConfiguredRelyingPartyTests 4). This matches the counts in fixes.md for the fix revision.
- `tasks.py verify deploy-assetlinks` shows no recorded evidence yet. At this stage that is expected: the parent worker records it before `done`. It is not a finding.

Not done by this reviewer: running Gradle myself. Running the build was not permitted in this session. The test status above comes from the existing result files and from reading the tests.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.
