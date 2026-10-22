# Code Review

Date: 2026-10-22

Task: passkeys-server - Passkeys (WebAuthn) backend

Scope: final revision ec8afadfe747cd29dc8440fc993f782de17ac68d. Its task code is identical to 151ba4d69950facc4f6f7aba3ffb92cc2288f420: `git diff 151ba4d6 HEAD -- android server api` is empty. The merge ec8afadf only brings in main's `web-weekly-review` work, which is outside this task. New review work is commit 151ba4d6 (`git show 151ba4d6 -- android`), which adds test cases to `PasskeyModelsSerializationTest.kt`. Everything else is carried over from the previous review, which covered the code up to cee7e92d (rebased hash of d5c2c241a). This report replaces that review.

Task code commits on the rebased history: 86984b11 (implementation), e6c38ed5 (TOTP re-enrollment tests), 5b5cc661 (fix for CR-1/TW-1), a9f18cd5 (concurrent login tests), c0d04155 (migration rename), 737768bd (duplicate Flyway version test), e8cafe3e (OpenAPI regeneration), cee7e92d (Android generator mapping), 151ba4d6 (more serialization tests). The other commits are review reports, quality.json, and `tasks/`-only commits.

Requirements:
- `tasks/passkeys-server.md`: acceptance criteria and recorded assumptions, including the one about touching `android/app/build.gradle.kts` outside `touches` (AnyType mapped to JsonElement)
- `SPEC.md` §4.1, §4.2 and §10.2; `docs/ARCHITECTURE.md`; the `AGENTS.md` project notes (generated clients are built from `api/openapi.yaml`, lint is part of the contract, test data is fictitious); `docs/QUALITY.md`
- Parent instructions: review the test addition in 151ba4d6, confirm the earlier conclusions still apply, and rewrite the report for the final revision.

Git range:
- Branch: main
- HEAD: ec8afadfe747cd29dc8440fc993f782de17ac68d (task code identical to 151ba4d69950facc4f6f7aba3ffb92cc2288f420)
- Base/start: 4020f3d52d917a84501544fc59ac1cc9730f2d22 (merge-base with origin/main, given by the parent)
- Commits reviewed: 1 new code commit (151ba4d6). The task code commits listed in Scope are carried over from the earlier reviews. There were no uncommitted changes. This was a read-only inspection and I did not run Gradle. The test result comes from the parent: `android/gradlew -p android testDebugUnitTest --tests '*PasskeyModelsSerializationTest'` -> BUILD SUCCESSFUL, 4 tests, 0 failures.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## New tests (151ba4d6): `PasskeyModelsSerializationTest`

File: `android/app/src/test/java/ch/sonensei/cockpit/core/network/PasskeyModelsSerializationTest.kt`

- **Right serializer configuration.** The new cases use `Serializer.kotlinxSerializationJson`, the same default that `CockpitHttp.retrofit(client, json = Serializer.kotlinxSerializationJson)` uses (`NetworkModule.kt:58-61`). So they check the Json configuration that ships, not a test-only one. The original test keeps its local `Json { ignoreUnknownKeys = true }`, which still works as a plain model round trip.
- **All three request models.** `PasskeyLoginRequest`, `PasskeyRegistrationRequest` and `PasskeyTokenRequest` are encoded with a credential that contains every JSON value kind: strings, a nested object, an array, a negative number, a boolean and a `JsonNull`. The test then checks that `credential` arrives as a nested JSON object equal to the input, and not as a string. The previous review argued that one model was enough because all three share the same generated field type. Covering all three is still better, because it would catch a per-schema generator mapping regression.
- **Decoding from wire JSON.** A wire body with `"authenticatorAttachment":null` decodes, keeps the key (`containsKey`), and encodes back to JSON equal to the original. That confirms the nullable value type `JsonElement?` (a JSON null becomes Kotlin `null`, which the earlier review noted) does not lose or change keys on re-encoding.
- **Retrofit end to end.** The test builds `CockpitHttp.okHttpClient(ServerUrlInterceptor { server.url("/") }, null, null)` and calls `PasskeysApi.issueDeviceTokenWithPasskey` against `MockWebServer`. The test asserts:
    - the request is a POST to `/api/v1/auth/passkeys/token`, which matches `api/openapi.yaml:567`;
    - the credential in the sent body is a nested object equal to the input;
    - the token response is parsed (`accessToken`).

  `mockwebserver3` is already a test dependency (`libs.okhttp.mockwebserver`) and other `core.network` tests use it in the same way. The server is closed with `use`, and `runBlocking` only wraps one suspend call against a local server, so the test cannot hang.
- **Test data.** The ceremony and device IDs, tokens and base64 values are fictitious, and the device name is generic ("Test phone"). No secrets or personal data.
- **Quality.** The tests assert behavior (wire JSON and HTTP request) and not implementation details. The helper and companion constants keep the cases short. No production code changed.

## Android generator mapping (cee7e92d; carried over)

- `android/app/build.gradle.kts` maps OpenAPI `AnyType` to `kotlinx.serialization.json.JsonElement` (`typeMappings` + `importMappings`). This fixes the compile error caused by the free-form `credential` (`additionalProperties: {}`) in the three passkey request schemas, without narrowing the server contract.
- The generated models declare `credential: Map<String, kotlinx.serialization.json.JsonElement?>`, as I re-checked in `android/app/build/generated/openapi/.../models/Passkey*Request.kt:48`. No other generated model uses `AnyType`, so the change has no side effects on them.
- The 4-line edit outside `touches` is required for `.taskcheck` to compile once the passkey schemas are in the contract, and it is recorded as an assumption. No new library was added, so ARCHITECTURE.md needs no stack update for it.

## Carried over from earlier reviews (server, API and web code unchanged)

- **Migration convention test (737768bd):** `MigrationConventionTests` fails on duplicate Flyway version keys across module folders. `core/V202610221005__core_auth_passkey.sql` (c0d04155) follows `<module>/V<yyyyMMddHHmm>__<module>_<desc>.sql`, and its version is unique.
- **OpenAPI (e8cafe3e):** the contract was regenerated, not merged by hand. Across the task it adds the `Passkeys` tag, paths and schemas, and removes nothing from main. The generated web client matches it.
- **Concurrency (5b5cc661, CR-1 of the first review):** `PasskeyService.verifyAssertion` takes the account lock before it loads the passkey, so concurrent logins with the same passkey cannot accept a replayed sign counter. The concurrency tests are bounded.
- **Ceremonies:** each challenge is 32 random bytes from `SecureRandom`. Each ceremony is single use, expires after 5 minutes, and is kept in a bounded store. A registration ceremony is bound to its owner, and the RP ID and origin come from the stored ceremony.
- **WebAuthn verification:** user verification and user presence are required, and attestation is `none`. The sign counter is checked and persisted, and the user handle must match the owner.
- **TOTP re-enrollment:** registration is refused while re-enrollment is required. A passkey web login gets a re-enroll-only session, a passkey token request gets 403 `TotpEnrollmentRequired`, and a restore wipes `auth_passkey`.
- **Errors, logging and access:** errors are Problem Details and no secrets are logged. Only the authentication-options, login and token endpoints are public, and CSRF still applies to the web login.

## Review conclusion

No material issues found.
