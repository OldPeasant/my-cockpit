# Test Report

Date: 2026-10-22

Task: passkeys-server - Passkeys (WebAuthn) backend

Scope: `git diff 99936ef802eab20a25c99700b72d6dfc6dee7c90 993b14dc77dfb64d3cdd39079dcb98dcf9c7336e` on branch `main`.
The parent supplied the base `99936ef8` (merge-base with origin/main) and the task commits: `149437b1` (implementation),
`706267e8` (earlier test-writer run: `PasskeyTotpReenrollmentTests`), `cdf39218` (fixer: TW-1/CR-1,
`PasskeyService.verifyAssertion` now takes the account row lock before it loads the passkey), and `993b14dc` (test JVM
heap 1g -> 2g in `server/build.gradle.kts`). The diff matches the task's `touches` plus the touch-outside edits recorded
in the task assumptions. This report replaces the earlier one, which covered the pre-fix revision `83b45e76` (TW-1 open).
Uncommitted inputs in this run: `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/passkey/PasskeyTotpReenrollmentTests.kt`
(modified by this run). `reviews/passkeys-server/review.md` was also modified in the working tree, but not by this
helper; it is not a test input.

Requirements:
- `tasks/passkeys-server.md` acceptance criteria and assumptions (in particular the 2026-10-22 re-enrollment assumption)
- `SPEC.md` 4.1 (passkeys), 4.2 (Problem Details), 10.2 (restore without TOTP secret)
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md` project notes
- Parent instructions: confirm TW-1 is fixed, look for gaps from the lock-order change (unknown passkey, inactive
  account, concurrent logins with the same passkey / sign counter)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/passkey/PasskeyTotpReenrollmentTests.kt` (2 tests added, class
  KDoc extended by one clause; no existing test changed)

## Tests added or updated

Count: 2

1. `a second passkey login with the same passkey waits for the first, then continues from its sign count`: login A
   (sign count 1) holds its transaction open, login B (sign count 2, same passkey) must wait on the account lock and
   then succeed; the stored sign count is 2. Before the fix, B would have loaded the passkey (version, sign count 0)
   before waiting and then failed the optimistic version check on save.
2. `two concurrent assertions with the same sign count, as from a cloned authenticator, log in only once`: two
   assertions with sign count 1; the second waits for the first and is then rejected as `InvalidCredentials` (no
   exception, no 409); sign count stays 1 and only one passkey session exists besides the setup's password session.
   Before the fix, the replay would have checked against the stale count 0 (and passed verification) before failing
   on the version check.

Both are regression tests for the lock-order change from the sign-counter side; the two TW-1 tests cover the
re-enrollment side. This helper could not run them against the pre-fix code (that would need a checkout or a
production change). The "before the fix" behavior above comes from reading the code: it was not run.

The other paths the parent named were already covered, and the lock order does not change their result:
- Unknown passkey (lock is taken, then `rejected("unknown passkey")`; the transaction ends and releases it):
  `PasskeyWebTests` `a deleted passkey can no longer log in`, and `invalid assertions are rejected like wrong
  credentials` (foreign authenticator with the owner's user handle).
- Inactive account: `PasskeyEdgeCaseTests` `a passkey of an account whose setup is not complete cannot log in`.
- Account missing: not reachable while a passkey exists (the FK cascades on account delete; `deleting the account
  deletes its passkeys`).

## Requirement coverage

Focused command (F): `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.auth.passkey.PasskeyTotpReenrollmentTests'`
-> BUILD SUCCESSFUL, 11/11 tests.
Auth command (A): `cd server && ./gradlew clean test --tests 'ch.sonensei.cockpit.core.auth.*' jacocoTestReport`
-> BUILD SUCCESSFUL, 280 tests, 0 failures, 0 errors, 0 skipped (Postgres via Testcontainers).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 registration needs an authenticated session + TOTP; registration ceremony (webauthn4j) | `PasskeyWebTests` registration tests; `PasskeyEdgeCaseTests` (UV required, RP binding, name length, transports, ceremony swap); `PasskeyTotpReenrollmentTests` re-enrollment refusals | A | Pass |
| AC1 authentication ceremony, web session login | `PasskeyWebTests` login, CSRF, invalid assertions, sign count, throttle; `PasskeyEdgeCaseTests` (synced passkeys, session cookie, no user handle, throttle reset, inactive account) | A | Pass |
| AC1 Android token login and registration with a bearer token | `PasskeyWebTests` token login and Android registration; `PasskeyConfiguredRelyingPartyTests` (apk-key-hash origin) | A | Pass |
| AC1 credential list/delete | `PasskeyWebTests` `a deleted passkey can no longer log in`; `PasskeyEdgeCaseTests` management authorization | A | Pass |
| Re-enrollment assumption (registration refused, re-enroll-only web session, 403 device token) | `PasskeyTotpReenrollmentTests` tests 1-6 | F, A | Pass |
| TW-1 fixed: a passkey login that waits for an uncommitted re-enrollment confirm continues with full access | `... a passkey token request that runs while a confirm is not committed yet waits, then gets a device with full access`, `... a passkey web login that runs while a confirm is not committed yet waits, then gets a session with full access` | F, A | Pass (both failed on the pre-fix revision) |
| Lock order: login first, confirm waits | `a passkey login whose transaction runs while the confirm runs is ended by the confirm and never gets full access` | F, A | Pass |
| Lock order: concurrent logins with the same passkey are serialised and read the committed sign counter | the 2 new tests | F, A | Pass |
| AC2 `./gradlew test --tests 'ch.sonensei.cockpit.core.auth.passkey.*'` passes | all passkey classes (subset of A) | A | Pass |
| AC3 OpenAPI regenerated, `OpenApiContractTest` passes without the flag | `OpenApiContractTest` | Not run: not in this helper's selection, and this run changed no production code or API. The parent's `./gradlew check` on `993b14dc` includes it. | Not run |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.auth.passkey.PasskeyTotpReenrollmentTests'`

Result:

Pass (11 tests, 0 failures; ktlint main and test source sets pass).

## Full test execution

Command:

`cd server && ./gradlew clean test --tests 'ch.sonensei.cockpit.core.auth.*' jacocoTestReport` (the parent's request).

Result:

Pass: 280 tests, 0 failures. This helper did not rerun the full `./gradlew check`. Per the parent, `./gradlew check` on
`993b14dc` (heap 2g) gave BUILD SUCCESSFUL in 6m22s. That run did not include the 2 tests added here.

## .taskcheck

Result:

Not run (the parent runs it; its server step `./gradlew check` passed on `993b14dc`, as the parent reported).

## Bugs found

None. TW-1 (from the previous report) is fixed. Its two regression tests pass, and no new defect was found.

## Coverage

Status: verified (task-scoped JaCoCo run on the final code plus this run's new tests; selection
`ch.sonensei.cockpit.core.auth.*`, which contains every passkey test and the auth tests for the touch-outside files).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `passkeys-server`; base
  `99936ef802eab20a25c99700b72d6dfc6dee7c90`; HEAD `993b14dc77dfb64d3cdd39079dcb98dcf9c7336e`; commits
  `149437b1`, `706267e8`, `cdf39218`, `993b14dc` as supplied by the parent; file scope
  `git diff --name-only 99936ef8 993b14dc -- server/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `993b14dc` plus modified
  `PasskeyTotpReenrollmentTests.kt` sha256 `f93d444d9598bcb6ee88f13887cadb9789a0bbb26e994b0624cf778b74325fb9` (the
  same before and after the run); no other source or test changes in the working tree.
- Exact included files (under `server/src/main/kotlin/ch/sonensei/cockpit/`): `core/auth/domain/DeviceTokenService.kt`,
  `core/auth/domain/LoginService.kt`, `core/auth/infra/SecurityConfiguration.kt`,
  `core/auth/passkey/api/{PasskeyController,PasskeyDtos,PasskeyRelyingParties}.kt`,
  `core/auth/passkey/domain/{Passkey,PasskeyCeremonies,PasskeyService}.kt`, `core/auth/passkey/infra/PasskeyRepository.kt`.
  Excluded: `server/src/main/resources/db/migration/core/V202610221000__core_auth_passkey.sql` (SQL, not instrumented;
  Flyway runs it in every Postgres passkey test); `server/build.gradle.kts` (build config, heap size only);
  `api/openapi.yaml` and `web/src/app/api/generated/**` (generated); docs, tasks, reviews. No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (project
  configuration, unchanged; tool version is the plugin default, not pinned in `build.gradle.kts`);
  `./gradlew clean test --tests 'ch.sonensei.cockpit.core.auth.*' jacocoTestReport` in `server/`; 280 tests.
- Run start/completion time and exit status: 2026-10-22T02:16:33+02:00 to 02:18:41+02:00, exit 0, 0 test failures.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`
  sha256 `27f11be0c29aff39f8d41ebee7f3731f54291a2bfad4367f2205944d0223618e`; `server/build/jacoco/test.exec` sha256
  `45f7f17cb579bc8afc277a91b408016e08bea05fa97cdcef04d8ad924c04214f`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. `clean` removed the old `build/`, and the test-file
  hash was the same before and after the run.
- Final run after latest test additions and production fixes: yes (after the fix `cdf39218` and this run's 2 tests).

Values below use covered/total (percentage); JaCoCo counters (INSTRUCTION as statements, METHOD as functions).

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

`LoginService.kt`, `DeviceTokenService.kt` and `SecurityConfiguration.kt` are whole-file numbers: they include
pre-existing code and hits from pre-existing auth tests.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `PasskeyService.kt:247` (1/10 branches: locked account present but its id differs from the passkey's owner) | Single-account system; the passkey FK always points to the one account | Not reachable with one account. The inactive-account and unknown-passkey branches are covered (see above). Justified gap. |
| `PasskeyService.kt:212` (1/4: compiler-generated variant of the `Verified if` guard) | Re-enroll check on token issue | Both outcomes covered (`PasskeyTotpReenrollmentTests` tests 5, 6, token-login tests). Justified. |
| `PasskeyService.kt:125, 142-143, 255, 268, 296, 315` | `checkNotNull` on webauthn4j results after verify, `findFirstBy()` null and owner-mismatch variants, transport parsing, static `ALGORITHMS` init | Defensive or constant code that webauthn4j / the single account already guarantees. Justified. |
| `PasskeyController.kt:269` (2/4: principal not a `CockpitPrincipal`) | Defensive `IllegalStateException` | Every authenticated request carries a `CockpitPrincipal`. Justified. |
| `PasskeyRelyingParties.kt:44` (1/2: request origin without host) | `requireNotNull` | A servlet request always has a host. Justified. |
| `PasskeyDtos.kt:191` (2 instructions) | Generated/default code | No behavior. Justified. |
| `Passkey.kt:25, 53` (3/4 branches) | JPA no-arg / `uvInitialized ||` short-circuit variants | Generated or trivial. Justified. |
| `PasskeyCeremonies.kt:89-90` (1/2 each: `removeIf` predicate false path) | Purge of expired ceremonies | Expiry and eviction covered in `PasskeyCeremoniesTests`. Low risk. Justified. |
| `SecurityConfiguration.kt:138` (2/4), `LoginService.kt:225-226`, `DeviceTokenService.kt:124` | Pre-existing CSRF filter branch, data-class methods, `createdAt` null fallback | Pre-existing or generated code, not task behavior. Justified. |

## Unable to test

- The new tests were not run against the pre-fix revision. Doing so needs a checkout or a production change, and this
  helper may do neither. Their regression value is argued from the code above.
- `OpenApiContractTest` and the `UPDATE_OPENAPI=1` regeneration were not rerun here (no production or API change in this
  run). The parent records AC3 from its own run.
- Real browser or Android Credential Manager ceremonies: not run here. `TestAuthenticator` produces the same JSON. No
  e2e criterion names this.

## Summary

Tests added and all validation passed. TW-1 is fixed: both regression tests pass. Two concurrency tests were added for
the sign-counter side of the new lock order. No production defects found. The 280 auth tests pass, and task-scoped
coverage is 98.3% instructions / 91.4% branches / 100% lines; the remaining gaps are justified.
