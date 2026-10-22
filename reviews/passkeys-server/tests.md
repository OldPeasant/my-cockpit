# Test Report

Date: 2026-10-22

Task: passkeys-server - Passkeys (WebAuthn) backend

Scope (final, this run): `git diff cc9bf955b9cf1c254e3f971a95d82d2eb361d728 d5c2c241a02a16b1914443996cf8ee1f5578ba3a` on
branch `main`. The base is the merge-base with origin/main, supplied by the parent. The final revision named by the
parent is `d5c2c241a`.

Final-revision update (this run): after another rebase, the task commits are `cc9bf955b..HEAD`:
`9f81810a7`, `1e9acdd3d`, `2aeb30577`, `4388997a7`, `d4ed56f42`, `0265a11a1`, `53751cb86`, `f18983e8b`, `4a2106b56`,
`82e041312`. I checked the hash mapping with git:
- `git rev-parse HEAD` is `82e0413125ba8191ece4e205445a533e0eb6b4b3`. `git diff d5c2c241a 82e041312` touches only
  `tasks/` (three task files), so `82e041312` is the same code as `d5c2c241a`.
- `d6512521a` is the parent's name for `4a2106b56`: `git diff d6512521a 4a2106b56 -- server api web android` is empty.
- `git diff 1235c1080 d6512521a -- server api web android` is empty, so everything below about `1235c108` still applies.
- The only code change since then is `d5c2c241a` (`82e041312`): `android/app/build.gradle.kts` (4 lines) and the new
  `android/app/src/test/java/ch/sonensei/cockpit/core/network/PasskeyModelsSerializationTest.kt`. See
  "Android client change (d5c2c241a)" below.

The rest of this section is from the previous run, on the pre-rebase scope
`fc7737af267cba0c4fcbf1b0e41c889b2232b745..1235c108058d8cddfdd5f0fb3e96166634ae0cd8`.

Task commits on the first-parent chain `fc7737af2..1235c1080` (history after the latest rebase):
- `e19f713c0` implementation
- `2e1aca37d` test-writer: `PasskeyTotpReenrollmentTests`
- `35f64b753` fixer: TW-1/CR-1, the account row lock is taken before the passkey is loaded
- `3bb996573` concurrent same-passkey login tests
- `31f582ecf` review
- `8c2744705` quality.json
- `741d65149` migration rename
- `6589bd58e` this helper's duplicate-version test, committed unchanged
- `1235c1080` `api/openapi.yaml` regenerated

The parent named some of these by their hashes before the latest rebase: `00be3bd6b` for the rename and `c94b045e2` for the
test. I checked the pairs with git:
- `git diff c94b045e2 6589bd58e -- server api web` is empty.
- `git diff 00be3bd6b 741d65149` touches only `tasks/`.

Earlier reports used hashes from before the first rebase. `99936ef8` / `993b14dc` match `fc7737af2` / `70bed4e02`.
`70bed4e02` matches `3bb996573` here: `git diff 70bed4e02 1235c1080` shows no change under `core/auth` in main or test.

Changes since the coverage run on `70bed4e02` / `3bb996573`:
1. Rename `741d65149`: `server/src/main/resources/db/migration/core/V202610221000__core_auth_passkey.sql` became
   `V202610221005__core_auth_passkey.sql`, with the content unchanged. Main had gained
   `review/V202610221000__review_weekly_review.sql`, and Flyway refused to start with "Found more than one migration with
   version 202610221000". As the parent reported, 2039 of 4083 server tests failed.
2. Test `6589bd58e`: one new test in `MigrationConventionTests` (see below).
3. Regen `1235c1080`: `api/openapi.yaml`. I checked `git diff 00be3bd6b 1235c1080 -- api/openapi.yaml`: it only reorders
   entries in the top-level `tags` list after the merge with main, with no tag or path content changed. The parent
   reports that `npm --prefix web run api:gen` produced no diff in `web/src/app/api/generated`.

The other files in `git diff 70bed4e02 1235c1080` (journal-api, its generated client, tasks, reviews) came in with
main, not with this task.

Uncommitted inputs in this run: only this report. `reviews/passkeys-server/review.md` is also modified in the working
tree, but not by this helper. It is not a test input.

Requirements:
- `tasks/passkeys-server.md` acceptance criteria and assumptions (in particular the 2026-10-22 re-enrollment assumption)
- `SPEC.md` 4.1 (passkeys), 4.2 (Problem Details), 10.2 (restore without TOTP secret)
- `docs/ARCHITECTURE.md` "Coverage" and "Persistence conventions", `docs/QUALITY.md`
- `AGENTS.md` project notes: migrations go to `db/migration/<module>/V<yyyyMMddHHmm>__<module>_<desc>.sql`, "so parallel
  tasks never collide on version numbers"
- Parent instructions: decide whether the rename needs a test; carry the coverage from `70bed4e02` forward; record the
  parent's runs at `00be3bd6b` and `1235c108`

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/core/network/PasskeyModelsSerializationTest.kt` (this run): added by the
  parent in `d5c2c241a` with one test. This helper kept that test unchanged and added three tests. Uncommitted, sha256
  `d10d0736abc32c71aad4a58d3814dbc3ed5fc95207bebaee346f028bf3f88e9d`.
- `server/src/test/kotlin/ch/sonensei/cockpit/core/persistence/MigrationConventionTests.kt`: one test and one import
  added by this helper, no existing test changed. Committed by the parent in `6589bd58e`, sha256
  `f7e907da870726c66914bbfd59a73bfdd21798b68c0306365563af29c750529f`. The file is outside the task's `touches`
  (`server/src/test/kotlin/ch/sonensei/cockpit/core/auth/passkey`). The parent decides whether this needs a touch-outside
  assumption.
- Earlier runs, unchanged since `70bed4e02`: `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/passkey/*`

## Android client change (d5c2c241a)

What changed: `api/openapi.yaml` declares the WebAuthn `credential` of `PasskeyLoginRequest`,
`PasskeyRegistrationRequest` and `PasskeyTokenRequest` as a free-form object (`additionalProperties: {}`). The Kotlin
generator turned that into `Map<String, Any?>`, and the kotlinx.serialization compiler plugin failed on it, so
`:app:compileDebugKotlin` (part of `.taskcheck`) broke. `android/app/build.gradle.kts` now maps `AnyType` to
`kotlinx.serialization.json.JsonElement`. The parent reports that only the three passkey models reference `JsonElement`
in the generated code. I confirmed in `android/app/build/generated/openapi/.../models/` that all three have
`@Contextual val credential: Map<String, JsonElement?>`.

Requirement: AC3 (OpenAPI regenerated, clients follow) together with the project rule that `.taskcheck` (Android
compile + unit tests) passes. The behavior that matters is that the Android client sends the credential to the server
as the same nested JSON object that the authenticator produced, through the app's real serializer and Retrofit stack.

Assessment of the parent's test (`credential JSON survives a round trip`): it is a correct regression guard for the
compile failure (the file does not compile without the mapping), and its assertions are meaningful. Gaps:
- It uses its own `Json { ignoreUnknownKeys = true }`, not the app's `Serializer.kotlinxSerializationJson`, whose
  `serializersModule` is what resolves the `@Contextual` annotation on the map at runtime in the app.
- It covers only `PasskeyLoginRequest`, and only strings and a nested object, not arrays, numbers, booleans or `null`,
  which real `RegistrationResponseJSON` / `AuthenticationResponseJSON` contain (`transports`, `publicKeyAlgorithm`,
  `authenticatorAttachment: null`, `clientExtensionResults`).
- It does not check what Retrofit actually puts on the wire.

Tests added by this helper in `PasskeyModelsSerializationTest` (the parent's test is unchanged):
1. `all three passkey requests keep every JSON kind of the credential with the app Json`: encodes each of the three
   models with `Serializer.kotlinxSerializationJson` and checks that `credential` in the output is the same nested JSON
   object (strings, nested objects, an array, a negative number, a boolean, a `null`).
2. `a credential decoded from wire JSON encodes back to the same JSON`: decodes a fictitious `PasskeyTokenRequest` body
   with the app Json, checks that the `null` key is kept, and that re-encoding gives the identical JSON tree.
3. `the passkey token call posts the credential as a nested JSON object`: `PasskeysApi.issueDeviceTokenWithPasskey`
   through `CockpitHttp.okHttpClient` / `CockpitHttp.retrofit` (the app's stack, default Json) against `MockWebServer`.
   It checks `POST /api/v1/auth/passkeys/token`, that the body's `credential` equals the object passed in, and that the
   `TokenResponse` is decoded. This follows the pattern of `AuthApiTokenRefresherTest`.

All data is fictitious (base64url placeholders, a made-up UUIDv7, "Test phone").

These tests are written but not run: this helper cannot run Gradle. The parent must run
`android/gradlew -p android ktlintCheck testDebugUnitTest --tests '*PasskeyModelsSerializationTest'` and expects 4 tests.
If one fails, it must be checked before treating it as a production defect. A possible test-side cause is the decoding
of `null` inside `Map<String, JsonElement?>`: test 2 deliberately asserts only the key and the re-encoded JSON, not
whether the value decodes as Kotlin `null` or `JsonNull`.

## Tests added or updated

Count: 3 in this run (Android, see above). The run at `1235c108` added 1 (`MigrationConventionTests`). The run on
`70bed4e02` added 2. The other passkey test classes come from the implementation and
the first test-writer run.

1. `MigrationConventionTests` `no two migrations share a Flyway version, even across module folders`
   - What it does: it groups every `classpath*:db/migration/**/*.sql` resource by its Flyway version, which is
     `yyyyMMddHHmm` plus the optional test-only `_<n>` suffix. It fails when two migrations share a version, and names
     the files.
   - Why it is needed: no existing test guarded this.
     - `MigrationConventionTests` checked only the folder, the name pattern, the timestamp's validity and the lowest
       version.
     - `FlywayConfigurationTests` and `PersistenceConventionsTests` do not compare versions across modules.
     - The only guard was Flyway's own check when the application context starts. It catches the collision, but it
       fails every Spring/Testcontainers test at once (2039 failures) and needs Docker.

     The new test is a plain unit test: no Spring, no Postgres, one failure that names both files.
   - Regression value: the passkey migration collided twice in this task.
     - First with `core/V202610201300` (oauth-connections; see the 2026-10-22 01:29 assumption).
     - Then with `review/V202610221000`.

     The modules differ, so the existing folder and name checks pass for both files. The new test fails on the
     pre-rename state, where both files map to the version key `202610221000`. I worked this out from the code and the
     migration listing; I did not run it on the pre-rename revision.
   - At HEAD: all 30 version keys (29 main, 1 test) are unique, including `202610200000` vs `202610200000_1`. The parent's
     run (2) at `1235c108` passed it.

The passkey tests needed no change. The rename keeps the migration's content and its module folder (`core`). It also
keeps its order after `core/V202610201000__core_auth_user.sql`, whose account table the passkey FK references. Every
Postgres passkey test runs Flyway on a fresh database, so the renamed file is executed by them.

## Requirement coverage

Commands (all run by the parent; this helper cannot run Gradle):

- **(AND-1) Android check**, on `d5c2c241a` with the mapping, before the test file was added:
  `android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug`
  - Result: BUILD SUCCESSFUL in 5m29s.
- **(AND-2) Android focused**, on `d5c2c241a`:
  `android/gradlew -p android ktlintCheck testDebugUnitTest --tests '*PasskeyModelsSerializationTest'`
  - Result: BUILD SUCCESSFUL. The result XML shows tests=1, failures=0 (the parent's test only).
- **(AND-3) Android focused, with this helper's tests**: the same command as AND-2 on the working tree with the
  updated test file (sha256 `d10d0736...88e9d`). Not run yet; the parent runs it. Expected: 4 tests.

- **(A) Auth suite with coverage**, on `70bed4e02` (= `3bb996573`):
  `cd server && ./gradlew clean test --tests 'ch.sonensei.cockpit.core.auth.*' jacocoTestReport`
  - Result: BUILD SUCCESSFUL, 280 tests, 0 failures, 0 errors, 0 skipped (Postgres via Testcontainers).
- **(C) Full check**, on `00be3bd6b` (= `741d65149`), before the new test and the yaml regen: `server/gradlew -p server check`
  - Result: 4129 tests, 1 failed, 20m51s. The failure was `OpenApiContractTest`, with the top-level tag order differing
    after the merge with main.
  - All other tests passed, including every passkey test and the existing `MigrationConventionTests`.
  - `1235c1080` fixed the failure by regenerating `api/openapi.yaml`.
- **(F) Focused**, on `1235c108` (final):
  `server/gradlew -p server ktlintCheck test --tests *OpenApiContractTest --tests *MigrationConventionTests --tests ch.sonensei.cockpit.core.auth.passkey.*`
  - Result: BUILD SUCCESSFUL, 73 tests, 0 failures. This includes the new test and `OpenApiContractTest` without
    `UPDATE_OPENAPI`.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 registration needs an authenticated session + TOTP; registration ceremony (webauthn4j) | `PasskeyWebTests` registration tests; `PasskeyEdgeCaseTests` (UV required, RP binding, name length, transports, ceremony swap); `PasskeyTotpReenrollmentTests` re-enrollment refusals | A, C, F | Pass |
| AC1 authentication ceremony, web session login | `PasskeyWebTests` login, CSRF, invalid assertions, sign count, throttle; `PasskeyEdgeCaseTests` (synced passkeys, session cookie, no user handle, throttle reset, inactive account) | A, C, F | Pass |
| AC1 Android token login and registration with a bearer token | `PasskeyWebTests` token login and Android registration; `PasskeyConfiguredRelyingPartyTests` (apk-key-hash origin) | A, C, F | Pass |
| AC1 credential list/delete | `PasskeyWebTests` `a deleted passkey can no longer log in`; `PasskeyEdgeCaseTests` management authorization | A, C, F | Pass |
| Re-enrollment assumption: registration refused, re-enroll-only web session, 403 device token | `PasskeyTotpReenrollmentTests` tests 1-6 | A, C, F | Pass |
| TW-1 fixed: a passkey login that waits for an uncommitted re-enrollment confirm continues with full access | the two `PasskeyTotpReenrollmentTests` tests named "... runs while a confirm is not committed yet waits ..." | A, C, F | Pass (both failed on the pre-fix revision) |
| Lock order: login first, confirm waits; concurrent same-passkey logins serialised | `PasskeyTotpReenrollmentTests` lock-order and sign-counter tests | A, C, F | Pass |
| Migration rename: the passkey migration has a unique Flyway version and follows the naming convention | `MigrationConventionTests`: the new duplicate-version test and the existing naming test; every Postgres passkey test runs the renamed migration | C (naming and passkey tests, before the new test), F (all, incl. the new test) | Pass |
| AC2 `./gradlew test --tests 'ch.sonensei.cockpit.core.auth.passkey.*'` passes | all passkey classes | F at `1235c108` (also in A and C) | Pass |
| AC3 OpenAPI regenerated, `OpenApiContractTest` passes without the flag | `OpenApiContractTest` | C, F | C at `00be3bd6b`: Fail (tag order only), fixed by `1235c1080`. F at `1235c108`: Pass |
| AC3 / `.taskcheck`: the generated Android client compiles with the passkey models | Android `:app:compileDebugKotlin` within the Android check | AND-1 at `d5c2c241a` (mapping only, before the test file) | Pass (parent: BUILD SUCCESSFUL in 5m29s) |
| Android passkey models serialize the WebAuthn credential as nested JSON | `PasskeyModelsSerializationTest` `credential JSON survives a round trip` | AND-2 at `d5c2c241a` | Pass (parent: tests=1, failures=0) |
| Same, with the app Json, all three models, all JSON kinds, and on the wire via Retrofit | `PasskeyModelsSerializationTest`: the three tests added by this helper | AND-3 (to be run by the parent) | Not run: this helper cannot run Gradle |

## Focused test execution

Android (this run), command (parent):

`android/gradlew -p android ktlintCheck testDebugUnitTest --tests '*PasskeyModelsSerializationTest'`

Result:

At `d5c2c241a` with the parent's single test: Pass (tests=1, failures=0). With the three tests added by this helper: Not
run yet (AND-3, parent).

Server, command (run by the parent at `1235c108`, same server code as `d5c2c241a`):

`server/gradlew -p server ktlintCheck test --tests *OpenApiContractTest --tests *MigrationConventionTests --tests ch.sonensei.cockpit.core.auth.passkey.*`

Result:

Pass: BUILD SUCCESSFUL, 73 tests, 0 failures, ktlint passes.

## Full test execution

Android, command (parent, at `d5c2c241a` before the test file was added):

`android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug`

Result:

Pass: BUILD SUCCESSFUL in 5m29s.

Server, command (run by the parent at `00be3bd6b`, before `6589bd58e` and `1235c1080`):

`server/gradlew -p server check`

Result:

Fail: 4129 tests, 1 failure (`OpenApiContractTest`, top-level tag order only), 20m51s. All passkey tests passed.
`1235c1080` regenerated `api/openapi.yaml`, and the contract test then passed in F at `1235c108`. The full `check` was
not rerun at `1235c108`. The commits since `00be3bd6b` change only a test file and the generated yaml, and F covers both.
`.taskcheck`, run by the parent, includes `./gradlew check` on the final revision.

## .taskcheck

Result:

Not run by this helper. The parent runs `sh scripts/check.sh` after the reports. Its earlier run is what found the
Android compile failure fixed by `d5c2c241a`; it must be rerun on the final tree, including the updated Android test
file.

## Bugs found

None in production code.
- The Android compile failure (`Map<String, Any?>` in the generated passkey models) was found by the parent's
  `.taskcheck` run and fixed by `d5c2c241a` before this run.
- The Flyway version collision was fixed by `741d65149` (`00be3bd6b`) before this run. The new test guards against it
  happening again.
- The `OpenApiContractTest` failure was a stale generated file after the merge (tag order), not a defect. It is fixed by
  `1235c1080`.

## Coverage

Android change `d5c2c241a` (this run): not applicable, with no task-scoped Android coverage.
- `git diff --name-only cc9bf955b d5c2c241a -- android/app/src/main` is empty, so the task changed no Android production
  file that the Android JaCoCo run (`createDebugUnitTestCoverageReport`, docs/ARCHITECTURE.md "Coverage") would measure.
- `android/app/build.gradle.kts` is a build script, so it is not instrumented. The models in
  `android/app/build/generated/openapi/` are generated code, outside the task coverage scope. Their behavior is
  exercised by `PasskeyModelsSerializationTest` (AND-2, and AND-3 once the parent runs it), not measured as coverage.
- No Android coverage command was run, and no Android numbers are reported.

Server: the server code at `d5c2c241a` / `82e041312` is identical to `1235c108` (see Scope), so the server coverage
below still applies unchanged.

Status: verified at `70bed4e02` (= `3bb996573`) and carried forward to `1235c108`. No instrumented input changed:
- `git diff 70bed4e02 1235c1080` shows no change to any task Kotlin source under `core/auth` or any auth test.
- The renamed SQL file and `api/openapi.yaml` are not instrumented by JaCoCo.
- The new `MigrationConventionTests` test only lists classpath resources and loads no task code.

Coverage was not re-measured at `1235c108`. The parent's runs C and F did not report task-scoped JaCoCo numbers.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source:
  - Task `passkeys-server`.
  - Measured on the pre-rebase base `99936ef8` and HEAD `993b14dc`, which match `fc7737af2` and `70bed4e02` / `3bb996573`.
  - The final revision is `1235c108058d8cddfdd5f0fb3e96166634ae0cd8`, with base `fc7737af267cba0c4fcbf1b0e41c889b2232b745`.
  - File scope: `git diff --name-only fc7737af2 1235c1080 -- server/src/main`, which gives the same Kotlin files as
    measured plus the renamed SQL file.
- Source/test snapshot identifier (including uncommitted inputs):
  - Measured at `993b14dc`, with `PasskeyTotpReenrollmentTests.kt` sha256
    `f93d444d9598bcb6ee88f13887cadb9789a0bbb26e994b0624cf778b74325fb9`, later committed in `70bed4e02`.
  - Final: `1235c108`, all test inputs committed. `MigrationConventionTests.kt` has sha256 `f7e907da...0529f`.
- Exact included files, under `server/src/main/kotlin/ch/sonensei/cockpit/`:
  - `core/auth/domain/DeviceTokenService.kt`
  - `core/auth/domain/LoginService.kt`
  - `core/auth/infra/SecurityConfiguration.kt`
  - `core/auth/passkey/api/{PasskeyController,PasskeyDtos,PasskeyRelyingParties}.kt`
  - `core/auth/passkey/domain/{Passkey,PasskeyCeremonies,PasskeyService}.kt`
  - `core/auth/passkey/infra/PasskeyRepository.kt`
- Excluded files and reasons:
  - `server/src/main/resources/db/migration/core/V202610221005__core_auth_passkey.sql`: SQL, not instrumented. It was
    renamed from `V202610221000__...` in `741d65149` with the content unchanged. Flyway runs it in every Postgres
    passkey test, and `MigrationConventionTests` checks its name and version.
  - `api/openapi.yaml` and `web/src/app/api/generated/**`: generated.
  - Docs, tasks, reviews.
  - `server/build.gradle.kts`: no longer in the task diff, because main already had the same heap change.
- Provider/version, command, working directory, and test selection:
  - JaCoCo via the Gradle `jacoco` plugin. The project configuration is unchanged; the plugin uses its default version.
  - Command `./gradlew clean test --tests 'ch.sonensei.cockpit.core.auth.*' jacocoTestReport` in `server/`, 280 tests.
- Run start/completion time and exit status: 2026-10-22T02:16:33+02:00 to 02:18:41+02:00, exit 0, 0 test failures.
- Generated report paths and report hashes/run identifier, both from the `70bed4e02` run:
  - `server/build/reports/jacoco/test/jacocoTestReport.xml`, sha256
    `27f11be0c29aff39f8d41ebee7f3731f54291a2bfad4367f2205944d0223618e`
  - `server/build/jacoco/test.exec`, sha256 `45f7f17cb579bc8afc277a91b408016e08bea05fa97cdcef04d8ad924c04214f`
- Evidence outputs are fresh and inputs stayed unchanged:
  - For `70bed4e02`: yes. It was a `clean` run, and the test-file hash was the same before and after.
  - For `1235c108`: the numbers are carried forward. The instrumented inputs are byte-identical; see Status.
- Final run after latest test additions and production fixes: no coverage run on the final revision. The last coverage
  run was on `70bed4e02`, after the fix and the concurrency tests. Since then the changes are:
  - an SQL rename and a generated-yaml regen, neither instrumented;
  - one convention test that does not exercise task code.

  The parent's test runs C and F cover the final code.

Values below use covered/total (percentage), with JaCoCo counters: INSTRUCTION as statements, METHOD as functions.
Measured at `70bed4e02`.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `core/auth/passkey/domain/PasskeyService.kt` | 1198/1230 (97.4%) | 79/88 (89.8%) | 49/50 (98.0%) | 178/178 (100.0%) |
| `core/auth/passkey/api/PasskeyController.kt` | 328/335 (97.9%) | 18/20 (90.0%) | 14/14 (100.0%) | 47/47 (100.0%) |
| `core/auth/passkey/api/PasskeyDtos.kt` | 584/586 (99.7%) | 2/2 (100.0%) | 70/70 (100.0%) | 127/127 (100.0%) |
| `core/auth/passkey/api/PasskeyRelyingParties.kt` | 188/197 (95.4%) | 21/22 (95.5%) | 3/3 (100.0%) | 28/28 (100.0%) |
| `core/auth/passkey/domain/Passkey.kt` | 102/109 (93.6%) | 1/4 (25.0%) | 17/18 (94.4%) | 28/28 (100.0%) |
| `core/auth/passkey/domain/PasskeyCeremonies.kt` | 265/269 (98.5%) | 12/14 (85.7%) | 26/26 (100.0%) | 38/38 (100.0%) |
| `core/auth/passkey/infra/PasskeyRepository.kt` | N/A (interface, no bytecode) | N/A | N/A | N/A |
| `core/auth/domain/LoginService.kt` | 723/729 (99.2%) | 60/60 (100.0%) | 46/48 (95.8%) | 105/105 (100.0%) |
| `core/auth/domain/DeviceTokenService.kt` | 405/408 (99.3%) | 17/18 (94.4%) | 27/27 (100.0%) | 69/69 (100.0%) |
| `core/auth/infra/SecurityConfiguration.kt` | 345/347 (99.4%) | 2/4 (50.0%) | 11/11 (100.0%) | 48/48 (100.0%) |
| **Aggregate (summed counters, 9 measured files; PasskeyRepository N/A; SQL migration excluded)** | 4138/4210 (98.3%) | 212/232 (91.4%) | 263/267 (98.5%) | 668/668 (100.0%) |

`LoginService.kt`, `DeviceTokenService.kt` and `SecurityConfiguration.kt` are whole-file numbers. They include
pre-existing code and hits from pre-existing auth tests.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `PasskeyService.kt:247` (1/10 branches: locked account present but its id differs from the passkey's owner) | Single-account system; the passkey FK always points to the one account | Not reachable with one account. The inactive-account and unknown-passkey branches are covered. Justified gap. |
| `PasskeyService.kt:212` (1/4: compiler-generated variant of the `Verified if` guard) | Re-enroll check on token issue | Both outcomes covered (`PasskeyTotpReenrollmentTests` tests 5, 6, token-login tests). Justified. |
| `PasskeyService.kt:125, 142-143, 255, 268, 296, 315` | `checkNotNull` on webauthn4j results after verify, `findFirstBy()` null and owner-mismatch variants, transport parsing, static `ALGORITHMS` init | Defensive or constant code that webauthn4j / the single account already guarantees. Justified. |
| `PasskeyController.kt:269` (2/4: principal not a `CockpitPrincipal`) | Defensive `IllegalStateException` | Every authenticated request carries a `CockpitPrincipal`. Justified. |
| `PasskeyRelyingParties.kt:44` (1/2: request origin without host) | `requireNotNull` | A servlet request always has a host. Justified. |
| `PasskeyDtos.kt:191` (2 instructions) | Generated/default code | No behavior. Justified. |
| `Passkey.kt:25, 53` (3/4 branches) | JPA no-arg / `uvInitialized \|\|` short-circuit variants | Generated or trivial. Justified. |
| `PasskeyCeremonies.kt:89-90` (1/2 each: `removeIf` predicate false path) | Purge of expired ceremonies | Expiry and eviction covered in `PasskeyCeremoniesTests`. Low risk. Justified. |
| `SecurityConfiguration.kt:138` (2/4), `LoginService.kt:225-226`, `DeviceTokenService.kt:124` | Pre-existing CSRF filter branch, data-class methods, `createdAt` null fallback | Pre-existing or generated code, not task behavior. Justified. |
| `core/V202610221005__core_auth_passkey.sql` (not instrumented) | Passkey schema; unique Flyway version | Every Postgres passkey test runs it. The new `MigrationConventionTests` test guards version uniqueness (passed in F). |

## Unable to test

- The three Android tests added in this run have not been executed (AND-3). The parent must run them and record the
  result before using them as evidence.
- This helper cannot run Gradle. Every result above comes from runs by the parent, as the parent reported them.
- The full `./gradlew -p server check` was not rerun at `1235c108`. It ran at `00be3bd6b`, and F covers the two changes
  since then. The parent's `.taskcheck` runs it on the final revision.
- The new duplicate-version test was not run on the pre-rename revision (that needs a checkout). Its regression value
  comes from the version keys in the listing: core and review both had `202610221000`.
- Coverage was not re-measured at `1235c108`. The numbers are carried forward from `70bed4e02`, and the instrumented
  inputs are unchanged.
- From the earlier run: real browser or Android Credential Manager ceremonies were not run. `TestAuthenticator`
  produces the same JSON, and no e2e criterion names them.

## Summary

Final revision `d5c2c241a` (this run): tests added. The parent's Android runs at `d5c2c241a` passed (AND-1, AND-2).
The three Android tests added in this run still have to be run by the parent (AND-3), followed by `.taskcheck`.
- The parent's `PasskeyModelsSerializationTest` test is a valid regression guard for the compile failure, but it was
  narrow. The added tests cover all three models with the app's Json, every JSON value kind, and the request Retrofit
  actually sends.
- Android coverage is not applicable: no `android/app/src/main` file changed. The build script and the generated code
  are not instrumented.
- The server code is unchanged since `1235c108`, so the server results and coverage below still apply.

Previous run (`1235c108`): tests added and all validation passed. The focused run (F) at `1235c108` passed 73 tests,
0 failures.
- The rename needed no passkey test change.
- One convention test now checks that Flyway versions are unique across module folders. It is outside the task's
  `touches`.
- The `OpenApiContractTest` failure in the full check at `00be3bd6b` was a stale tag order, fixed by `1235c1080`. It
  passes at `1235c108`.
- No production defects found.
- Coverage was measured at `70bed4e02` and still applies to `1235c108`: 98.3% instructions, 91.4% branches, 100%
  lines.
