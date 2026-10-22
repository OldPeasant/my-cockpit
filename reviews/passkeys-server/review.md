# Code Review

Date: 2026-10-22

Task: passkeys-server - Passkeys (WebAuthn) backend

Scope: final revision d5c2c241a02a16b1914443996cf8ee1f5578ba3a. New review work is commit d5c2c241a (`git show d5c2c241a -- android`). The server, API and web code is carried over from the review of 1235c108058d8cddfdd5f0fb3e96166634ae0cd8 (`git diff fc7737af2..1235c1080`). The parent states that `git diff 1235c1080 d6512521a -- server api web` is empty, so that code is unchanged. This report replaces the review of 1235c1080.

The local `main` HEAD is 82e0413125ba8191ece4e205445a533e0eb6b4b3, which is a rebased copy of d5c2c241a. `git diff d5c2c241a 82e041312` only touches `tasks/passkeys-server.md`, `tasks/web-totp-reenroll.md` and `tasks/web-weekly-review.md`. No code differs, so this review also applies to 82e041312.

Task commits (after the rebase): e19f713c0 (implementation), 2e1aca37d (test-writer tests), 35f64b753 (fix for CR-1/TW-1), 3bb996573 (concurrency tests), 741d65149 (migration rename), 6589bd58e / 774c65349 (duplicate-version test), 1235c1080 / d6512521a (OpenAPI regeneration), d5c2c241a / 82e041312 (Android generator mapping). The other commits are review reports, quality.json, and `tasks/`-only heartbeat and assume commits.

Requirements:
- `tasks/passkeys-server.md`: acceptance criteria and recorded assumptions, including the one from 2026-10-22 03:31 (`android/app/build.gradle.kts` touched outside `touches`, AnyType mapped to JsonElement)
- `SPEC.md` §4.1, §4.2 and §10.2; `docs/ARCHITECTURE.md`; the `AGENTS.md` project notes (generated clients are built from `api/openapi.yaml` and never edited by hand; lint is part of the contract; stay inside `touches` or record why not); `docs/QUALITY.md`
- Parent instructions: review d5c2c241a for correctness, conventions, and side effects on other generated models. Keep the earlier conclusions that still apply.

Git range:
- Branch: main
- HEAD: d5c2c241a02a16b1914443996cf8ee1f5578ba3a (reviewed final revision; local HEAD 82e041312 has the same code)
- Base/start: cc9bf955b9cf1c254e3f971a95d82d2eb361d728 (task base given by the parent)
- Commits reviewed: 1 new code commit (d5c2c241a). The task code commits listed in Scope are carried over from the earlier reviews. There were no uncommitted code changes. This was a read-only inspection: I did not run Gradle. Build and test results come from the parent: `android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug` -> BUILD SUCCESSFUL, and `PasskeyModelsSerializationTest` has 1 test with 0 failures.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Android generator mapping (d5c2c241a)

File: `android/app/build.gradle.kts:110-113`

- **Cause and fix match.**
    - Three passkey request schemas in `api/openapi.yaml` declare `credential: type: object, additionalProperties: {}`, at lines 11371-11373, 11414-11416 and 11456-11458. These are PasskeyRegistrationRequest, PasskeyLoginRequest and PasskeyTokenRequest.
    - The Kotlin generator emits `{}` as `AnyType`, which turns into `Map<String, Any?>`. The kotlinx.serialization plugin cannot compile that type.
    - `typeMappings` AnyType -> JsonElement fixes the generated type. `importMappings` gives the fully qualified `kotlinx.serialization.json.JsonElement`.
    - The generated models now declare `credential: Map<String, kotlinx.serialization.json.JsonElement?>`. kotlinx.serialization can serialize that type, and it keeps arbitrary WebAuthn JSON intact. That matters because the server verifies the credential bytes as sent.
- **Contract kept honest.** The server contract was not narrowed to make the client compile. That fits the `AGENTS.md` rule "API changes: server first, regenerate, then clients" and the earlier `schemaMappings` pattern for `Settings`/`SettingsPatch`.
- **Side effects on other generated models: none found.**
    - `grep -rn "JsonElement|kotlin.Any|Any?"` over `android/app/build/generated/openapi/src/main/kotlin` finds JsonElement in only the 3 passkey models.
    - Every other `additionalProperties` in the contract is either typed or already covered by `schemaMappings`:
        - typed: backup `counts`, settings `creativityLabels`/`energyLabels`, and the nullable label maps in `SettingsPatch`;
        - covered by `schemaMappings`: `Settings`/`SettingsPatch` with `additionalProperties: true`.
      None of these is `AnyType`, so their generated types are unchanged.
    - `AuthApi.confirmTotpEnrollment` still returns `Response<kotlin.Any>`. That comes from an untyped `object` response, not from `AnyType`, so this change does not affect it. It is also not part of this task.
    - Future free-form `{}` values will also become JsonElement. That is the right default for a kotlinx client, and the build-file comment documents it.
- **Nullable values.** Because the generator always makes free-form values nullable, they are `JsonElement?` instead of `JsonElement`. A JSON `null` therefore decodes to Kotlin `null` instead of `JsonNull`. This is harmless: the WebAuthn credential JSON only goes from the client to the server, and a `JsonObject` can be assigned to the field directly, as the test does.
- **Test.**
    - `android/app/src/test/java/ch/sonensei/cockpit/core/network/PasskeyModelsSerializationTest.kt` sits next to the other `core.network` tests.
    - It checks three things for a nested credential object:
        - the encoded wire JSON equals the input;
        - a value can be read back after decoding;
        - the models are equal after the round trip.

      The equality check is valid across `JsonObject` and `LinkedHashMap` because both follow the `Map` equality contract.
    - The test compiles against the generated `PasskeyLoginRequest`, so it would also catch a regression of the mapping at compile time. The other two models share the same generated field type, so one round-trip test is enough.
    - The parent reports that the test passes and that ktlint and Android lint pass.
- **Outside `touches`.** The task only touches server packages. The build-file edit is 4 lines, and its necessity is recorded as an assumption (03:31). Without it, `.taskcheck` fails on `:app:compileDebugKotlin` as soon as the passkey schemas are in the contract. So it belongs to this task rather than a later Android task.
- **Docs.** The change adds no new tool, runtime or library, because kotlinx.serialization is already a dependency. So no update to the ARCHITECTURE.md stack table is needed.

## New test (6589bd58e/774c65349): `MigrationConventionTests` (carried over)

File: `server/src/test/kotlin/ch/sonensei/cockpit/core/persistence/MigrationConventionTests.kt:45-55`

- The test groups migrations by the Flyway version key: the 12-digit timestamp plus the optional `_<n>` suffix. The clash `V202610221000` in `core/` and `review/` would fail it.
- The scan uses `classpath*:db/migration/**/*.sql`. That covers the main migrations and the test fixture, which has its own key `202610200000_1`. It excludes the Flyway configuration fixtures, which are not under `db/migration`.
- File names that do not match the pattern are already caught by the first test in the class. The assertion lists the clashing paths. The test needs no database or Spring.
- The file is outside `touches`. The change only adds lines, and it is recorded as an assumption (02:48).

## OpenAPI regeneration (1235c1080/d6512521a; carried over)

- Compared with 00be3bd6b, the YAML change only reorders top-level `tags:` entries. The set of tags and their descriptions are unchanged, and no path, operation or schema changed.
- Over the whole task, the YAML adds the `Passkeys` tag, the passkey paths and the passkey component schemas. Nothing from main was removed.
- The file was regenerated rather than merged by hand. `OpenApiContractTest` passes without the update flag. `npm --prefix web run api:gen` produces no diff in the web client.

## Migration rename (741d65149; carried over)

- `core/V202610221005__core_auth_passkey.sql` is a pure rename and follows `<module>/V<yyyyMMddHHmm>__<module>_<desc>.sql`.
- No version is used twice, and the new test guards this. The old version was never on main.

## Carried over from the review of e111036d (server code unchanged)

- **CR-1 of the first review is resolved.** `PasskeyService.verifyAssertion` takes the account lock (`users.findFirstForUpdate()`) before it loads the passkey. The sign-counter and replay concurrency tests are correct and bounded.
- **Ceremonies:**
    - Each challenge is 32 random bytes from `SecureRandom`.
    - Each ceremony is single use, expires after 5 minutes, and is kept in a bounded store.
    - A registration ceremony is bound to its owner.
    - The RP ID and origin come from the stored ceremony.
- **WebAuthn verification:**
    - User verification and user presence are required, and attestation is `none`.
    - The sign counter is checked and persisted, and the user handle must match the owner.
- **TOTP re-enrollment:**
    - Registration is refused while re-enrollment is required.
    - A passkey web login gets a re-enroll-only session.
    - A passkey token request gets 403 `TotpEnrollmentRequired`.
    - A restore wipes `auth_passkey`.
- **Errors, logging and access:**
    - Errors are Problem Details, and no secrets are logged.
    - Only the authentication-options, login and token endpoints are public, and CSRF still applies to the web login.

## Review conclusion

No material issues found.
