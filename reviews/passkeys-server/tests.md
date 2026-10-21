# Test Report

Date: 2026-10-22

Task: passkeys-server - Passkeys (WebAuthn) backend

Scope: `git diff 02030f45ce121c7092583362d666dd74b3dfeb7e 83b45e76e087cdb2acb3409fd84e50246c7fe7ae` on branch `main`.
Base `02030f45` (merge-base with origin/main) and the single task code commit `83b45e76` (a squash of the earlier
owner's work plus the adaptation to main's TOTP re-enrollment) were supplied by the parent worker. The diff matches
the task's `touches` plus the touch-outside edits recorded in the task assumptions (LoginService.startSession,
DeviceTokenService.issueFor, SecurityConfiguration permit/CSRF rules, migration). Working tree before this run: clean.
Uncommitted input added by this run: `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/passkey/PasskeyTotpReenrollmentTests.kt`
(new, untracked, sha256 `f34f35fe439adb8b3d542119f12a964a2a575decf41cfeee468bc390f81e291f`). This report replaces the
earlier one, which described an older revision on an older main.

Requirements:
- `tasks/passkeys-server.md` acceptance criteria and recorded assumptions, in particular the 2026-10-22 assumption:
  a re-enrolling account (restored without its TOTP secret) cannot register a passkey (403 via fullAccess, the service
  ignores the unconfirmed secret), a passkey web login gives a re-enroll-only session, the passkey device token
  returns 403 TotpEnrollmentRequired like the password token
- `SPEC.md` 4.1 (passkeys), 4.2 (Problem Details), 10.2 (restore without TOTP secret, forced re-enrollment)
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md` project notes
- Parent instructions: focus on the changes since the old revision (re-enrollment handling, `findFirstForUpdate`
  lock in `verifyAssertion`, `MeResponse.of(principal)`, migration move)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/passkey/PasskeyTotpReenrollmentTests.kt` (new)

Existing passkey tests (`PasskeyWebTests`, `PasskeyEdgeCaseTests`, `PasskeyServiceUnitTests`,
`PasskeyCeremoniesTests`, `PasskeyRelyingPartiesTests`, `PasskeyConfiguredRelyingPartyTests`, `TestAuthenticator`)
are unchanged. A separate class was used so the re-enrollment setup (restore simulation, enrollment helpers, held
transactions) stays in one place; it uses the same Spring context configuration as `PasskeyWebTests`.

## Tests added or updated

Count: 9 (all in `PasskeyTotpReenrollmentTests`)

1. `a passkey web login of a normal account reports no TOTP enrollment and has full access`
2. `a passkey web login of a re-enrolling account gets a session that may only enroll TOTP again`
3. `a session of a re-enrolling account is refused passkey registration before its TOTP code is checked`
4. `the service does not accept a code of the unconfirmed TOTP secret of a re-enrollment`
5. `a passkey device token is refused with 403 while re-enrolling, wrong passkeys stay 401, and no device is created`
6. `after the re-enrollment the passkey device token works and has full access`
7. `a passkey token request that runs while a confirm is not committed yet waits, then gets a device with full access` (FAILS, TW-1)
8. `a passkey web login that runs while a confirm is not committed yet waits, then gets a session with full access` (FAILS, TW-1)
9. `a passkey login whose transaction runs while the confirm runs is ended by the confirm and never gets full access`

## Requirement coverage

Focused command (F): `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.passkey.*'` -> 67 tests,
65 passed, 2 failed (both TW-1). Postgres via Testcontainers.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 registration needs an authenticated session + TOTP; registration ceremony with webauthn4j | `PasskeyWebTests`: `a logged-in owner registers a passkey after confirming with TOTP`, `registration needs a login, the CSRF header and a correct TOTP code`, `wrong TOTP codes ... throttle`, `a registration ceremony works once ...`, `the same authenticator cannot be registered twice`; `PasskeyEdgeCaseTests` (UV required, RP binding, name length, transports, ceremony swap) | F | Pass |
| AC1 authentication ceremony, web session login | `PasskeyWebTests`: `a passkey logs the browser in ...`, `the web passkey login needs the CSRF header`, `invalid assertions are rejected ...`, `a sign count that does not increase is rejected`, `failed passkey logins share the login throttle`; `PasskeyEdgeCaseTests` (synced passkeys, browser-session cookie, no user handle, throttle reset) | F | Pass |
| AC1 Android token login and Android registration with a bearer token | `PasskeyWebTests`: `a passkey logs an Android device in ...`, `an Android device registers a passkey with its bearer token`; `PasskeyConfiguredRelyingPartyTests` (apk-key-hash origin) | F | Pass |
| AC1 credential list/delete | `PasskeyWebTests`: `a deleted passkey can no longer log in`; `PasskeyEdgeCaseTests`: `managing passkeys needs a login, the CSRF header for a session, and a UUID` | F | Pass |
| New: passkey web login returns `MeResponse.of(principal)` incl. `totpEnrollmentRequired` | tests 1, 2 | F | Pass |
| New: re-enrolling account's passkey web login gets a re-enroll-only session (me/enrollment ok; passkeys, settings, devices, registration 403); after confirm it gets full access | test 2 | F | Pass |
| New: registration refused for a re-enrolling session (fullAccess rule), before the TOTP check (no throttle use) | test 3 | F | Pass |
| New: `startRegistration` ignores the unconfirmed re-enrollment secret even for a principal claiming full access; the same code works once the flag is cleared | test 4 | F | Pass |
| New: passkey device token returns 403 Problem `TotpEnrollmentRequired` while re-enrolling, no device created; a non-verifying assertion stays 401 | test 5 | F | Pass |
| New: after re-enrollment the passkey device token gives a full-access device | test 6 | F | Pass |
| New: `verifyAssertion` takes the account row lock, so a passkey login cannot interleave with a re-enrollment confirm (login holds the lock first -> confirm waits and ends the passkey session) | test 9 | F | Pass |
| New: same lock, other order (confirm holds the lock first -> passkey token/web login waits, then sees the cleared flag) | tests 7, 8 | F | Fail (TW-1) |
| Migration moved to `core/V202610221000__core_auth_passkey.sql` | every Postgres-backed passkey test (tables `auth_passkey` created by Flyway on a fresh Testcontainers DB) | F | Pass |
| AC2 `./gradlew test --tests 'ch.sonensei.cockpit.core.auth.passkey.*'` passes | whole package | F | Fail: 2 failing tests (TW-1) |
| AC3 OpenAPI contract test passes without the flag | `OpenApiContractTest` | `cd server && ./gradlew test --tests '*OpenApiContractTest'` | Pass (1 test). Regeneration with `UPDATE_OPENAPI=1` not re-run by this helper. |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.auth.passkey.*'` (ktlint) and
`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.passkey.*'` (final, 2026-10-22 ~01:52)

Result:

Fail: 67 tests, 2 failed (`PasskeyTotpReenrollmentTests` tests 7 and 8, production defect TW-1). Per class:
PasskeyCeremoniesTests 7/7, PasskeyWebTests 14/14, PasskeyEdgeCaseTests 17/17, PasskeyRelyingPartiesTests 9/9,
PasskeyConfiguredRelyingPartyTests 4/4, PasskeyServiceUnitTests 7/7, PasskeyTotpReenrollmentTests 7/9.
`ktlintTestSourceSetCheck` passes after one formatting fix in the new file.

Also run: `./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` (part of the coverage run) -> 278 tests, 2 failed
(the same two); all other auth tests incl. `TotpEnrollmentWebTests` and the LoginService/DeviceTokenService tests pass.

## Full test execution

Command:

`cd server && ./gradlew check` (the server step of `.taskcheck`), run twice:
1. with the new test file -> exit 1: the 2 TW-1 failures, plus `Gradle Test Executor 11 FAILED ... java.lang.OutOfMemoryError`
   after 2230 tests (1 skipped); about 100 test classes never ran.
2. without the new test file (moved aside temporarily, then restored with identical sha256) -> exit 1, again
   `OutOfMemoryError` in the test executor, so the OOM exists without this run's tests.

Result:

Fail. The OOM happens in `ch.sonensei.cockpit.files.FilesWebTests` `a 60 MB file uploads in chunks and downloads
identical` (last entries of `build/test-results/test/binary/results-generic.bin`), test heap `maxHeapSize = "1g"`.
That test is outside this task. This helper did not check whether the OOM also happens at base `02030f45`. That needs
a checkout, which it must not do. The task's passkey test classes add Spring contexts to the test JVM's context cache
(e.g. `PasskeyConfiguredRelyingPartyTests` with its own properties), so the task may add to the memory pressure.
The parent worker must decide whether this blocks `done` (`.taskcheck` runs this command).

## .taskcheck

Result:

Not run as a whole. Its server step (`./gradlew check`) was run directly (above) and fails, so `.taskcheck` would
fail too. The web and Android steps were not run: this task changes no web or Android code, apart from the
regenerated web API client.

## Bugs found

### Passkey login fails with an optimistic-lock error instead of waiting for a concurrent account update

Finding ID:

`TW-1`

Test:

`PasskeyTotpReenrollmentTests.a passkey token request that runs while a confirm is not committed yet waits, then gets a device with full access`
and `PasskeyTotpReenrollmentTests.a passkey web login that runs while a confirm is not committed yet waits, then gets a session with full access`

Production source:

`server/src/main/kotlin/ch/sonensei/cockpit/core/auth/passkey/domain/PasskeyService.kt:242-244` (`verifyAssertion`),
with `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/passkey/infra/PasskeyRepository.kt:11`
(`select p from Passkey p join fetch p.user where p.credentialId = :credentialId`).

Requirement:

Task assumption 2026-10-22 and the code comment on `verifyAssertion`: the passkey login is "locked like in
LoginService.verify, so the grant cannot interleave with a TOTP re-enrollment confirm". `TotpEnrollmentService`
documents that the confirm and every login lock the account row, so a login running in parallel waits. The password
path's equivalent test (`TotpEnrollmentWebTests` `a device token request that runs while a confirm is not committed yet
waits, then gets a device with full access`) passes.

Expected behavior:

While a re-enrollment confirm holds the account row lock and has not committed, a passkey token or web login waits for
it. Then it reads the committed account (flag cleared) and issues a full-access device or session.

Actual behavior:

`findByCredentialId` join-fetches `passkey.user`, so the `UserAccount` enters the persistence context with the old,
unlocked `version` *before* `users.findFirstForUpdate()` runs. When the lock is granted after the confirm commits, the
locked query returns a newer version of the same entity, and Hibernate throws `StaleObjectStateException`
("Query result contains conflicting version of entity already held in persistence context"), surfacing as
`ObjectOptimisticLockingFailureException`. The service call throws: no device or session is created, the
single-use ceremony is used up, and the throttle attempt that `tryBeginAttempt` counted is never reset. Over HTTP,
`ProblemDetailsAdvice` maps this to 409 Conflict, which is not documented for `/passkeys/login` or `/passkeys/token`.
Any concurrent committed update of the account row between the passkey read and the lock does the same, not just the
TOTP confirm. The reverse order (passkey login takes the lock first) works (test 9 passes). Both failures are
deterministic: they happened in every run.

Possible fix direction, for the fixer to decide: take the account lock before loading the passkey with its user, or
load the passkey without fetching the user and compare `passkey.user.id` via the FK id, as `LoginService.verify` does.

Severity:

Medium. A narrow race with no security bypass (it fails closed), but a legitimate login fails with an undocumented
409, uses up a throttle attempt, and the documented locking guarantee is not met.

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Coverage

Status: partial-failed (diagnostic). The coverage run had 2 failing tests (TW-1). It ran an auth-scoped test
selection, not the full suite, because the full suite cannot finish (OOM, see above).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (the JaCoCo setup reports per file and per line, but this run did not intersect
those lines with the diff).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `passkeys-server`; base
  `02030f45ce121c7092583362d666dd74b3dfeb7e`; HEAD `83b45e76e087cdb2acb3409fd84e50246c7fe7ae` (the single task-owned
  commit, supplied by the parent); file scope `git diff --name-only 02030f45 83b45e76 -- server/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `83b45e76` plus untracked
  `PasskeyTotpReenrollmentTests.kt` sha256 `f34f35fe439adb8b3d542119f12a964a2a575decf41cfeee468bc390f81e291f`; same
  hash before and after the run; no other working-tree changes.
- Exact included files: `core/auth/domain/DeviceTokenService.kt`, `core/auth/domain/LoginService.kt`,
  `core/auth/infra/SecurityConfiguration.kt`, `core/auth/passkey/api/PasskeyController.kt`, `.../api/PasskeyDtos.kt`,
  `.../api/PasskeyRelyingParties.kt`, `.../domain/Passkey.kt`, `.../domain/PasskeyCeremonies.kt`,
  `.../domain/PasskeyService.kt`, `.../infra/PasskeyRepository.kt` (all under
  `server/src/main/kotlin/ch/sonensei/cockpit/`). Excluded:
  `server/src/main/resources/db/migration/core/V202610221000__core_auth_passkey.sql` (SQL, not instrumented by
  JaCoCo; every Postgres passkey test runs it through Flyway); `api/openapi.yaml` and `web/src/app/api/generated/**`
  (generated); docs, tasks and reviews. No renames or deletions of production files in scope.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (project
  configuration, unchanged); `./gradlew -I ../.tmp/ignore-failures.gradle clean test --tests 'ch.sonensei.cockpit.core.auth.*' jacocoTestReport`
  in `server/`. The Git-ignored init script only sets `ignoreFailures = true` on Test tasks, so that
  `jacocoTestReport` runs although tests fail. No project file was changed. Selection: all tests in
  `ch.sonensei.cockpit.core.auth.*` (278 tests).
- Run start/completion time and exit status: 2026-10-22T01:48:54+02:00 to 01:51:40+02:00. Gradle exit 0 because of
  `ignoreFailures`, with 2 test failures (TW-1).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`
  sha256 `4c64738be8945d2c84586e87ba5455eddb4d5aefbd5a51b39fcd7e83a8618bcd`; `server/build/jacoco/test.exec` sha256
  `f492ba6f0c57b0d389d4d7d02c40c5abadefeca4b26168a66d66425d4ea83888`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The run started with `clean`, so `build/` (including
  old reports and exec data) was regenerated, and the input hashes were the same before and after.
- Final run after latest test additions and production fixes: yes, after the latest test additions. No production
  fix has been made yet. After the TW-1 fix, coverage must be measured again.

Values below use covered/total (percentage); counters are JaCoCo's (INSTRUCTION as statements). Each file is in the
report.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `core/auth/passkey/domain/PasskeyService.kt` | 1196/1228 (97.4%) | 79/88 (89.8%) | 49/50 (98.0%) | 177/177 (100.0%) |
| `core/auth/passkey/api/PasskeyController.kt` | 328/335 (97.9%) | 18/20 (90.0%) | 14/14 (100.0%) | 47/47 (100.0%) |
| `core/auth/passkey/api/PasskeyDtos.kt` | 584/586 (99.7%) | 2/2 (100.0%) | 70/70 (100.0%) | 127/127 (100.0%) |
| `core/auth/passkey/api/PasskeyRelyingParties.kt` | 188/197 (95.4%) | 21/22 (95.5%) | 3/3 (100.0%) | 28/28 (100.0%) |
| `core/auth/passkey/domain/Passkey.kt` | 102/109 (93.6%) | 1/4 (25.0%) | 17/18 (94.4%) | 28/28 (100.0%) |
| `core/auth/passkey/domain/PasskeyCeremonies.kt` | 265/269 (98.5%) | 12/14 (85.7%) | 26/26 (100.0%) | 38/38 (100.0%) |
| `core/auth/passkey/infra/PasskeyRepository.kt` | N/A (interface, no bytecode instructions) | N/A | N/A | N/A |
| `core/auth/domain/LoginService.kt` | 723/729 (99.2%) | 60/60 (100.0%) | 46/48 (95.8%) | 105/105 (100.0%) |
| `core/auth/domain/DeviceTokenService.kt` | 405/408 (99.3%) | 17/18 (94.4%) | 27/27 (100.0%) | 69/69 (100.0%) |
| `core/auth/infra/SecurityConfiguration.kt` | 345/347 (99.4%) | 2/4 (50.0%) | 11/11 (100.0%) | 48/48 (100.0%) |
| **Aggregate (summed counters, 9 measured files; PasskeyRepository N/A; SQL migration excluded)** | 4136/4208 (98.3%) | 212/232 (91.4%) | 263/267 (98.5%) | 667/667 (100.0%) |

`LoginService.kt`, `DeviceTokenService.kt` and `SecurityConfiguration.kt` are whole-file numbers: they include
pre-existing code and hits from pre-existing auth tests.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `PasskeyService.kt:244` (1/10 branches: account present but id differs from passkey owner) | Single-account system; the passkey FK always points to the one account | Not reachable with one account (the FK cascades on delete); inactive account is covered (`PasskeyEdgeCaseTests` setup-not-complete). Justified gap. |
| `PasskeyService.kt:212` (1/4: Kotlin guard `Verified if ...` false-path variant) | Re-enroll check on token issue | Both outcomes covered (tests 5, 6 and the `PasskeyWebTests` token login); the remaining branch is compiler-generated. Justified. |
| `PasskeyService.kt:125` (1/6), `142-143`, `252`, `265`, `293` | `checkNotNull` on webauthn4j results after successful verify, null-safe transport parsing, `findFirstBy()` null | Defensive null checks that webauthn4j's verification already guarantees. No meaningful test. Justified. |
| `PasskeyService.kt:312` (static `ALGORITHMS` init) | Constant | Constant initialization only, nothing to test. |
| `PasskeyController.kt:269` (2/4: principal not a `CockpitPrincipal`) | Defensive `IllegalStateException` | Every authenticated request carries a `CockpitPrincipal`. Justified. |
| `PasskeyRelyingParties.kt:44` (1/2: origin without host) | `requireNotNull` on a parsed request origin | A servlet request always has a host. Justified. |
| `Passkey.kt:25,53` (3/4 branches) | Kotlin JPA no-arg / nullable `createdAt` fallbacks | Generated or defensive code. Justified. |
| `PasskeyCeremonies.kt:89-90` (1/2 each: `removeIf` predicate false path) | Purge of expired ceremonies | Expiry and eviction are covered in `PasskeyCeremoniesTests`; the missing branch is a not-yet-expired entry seen during a purge. Low risk. Justified. |
| `SecurityConfiguration.kt:138` (2/4: request without a CSRF attribute) | Pre-existing CSRF cookie filter | Pre-existing code outside the task's change. |
| `LoginService.kt:225-226` (`CredentialCheck.Verified` data-class methods), `DeviceTokenService.kt:124` (`createdAt` null fallback) | Generated or defensive code | Not task behavior. Justified. |
| Concurrent account update before the passkey assertion's lock | Locking guarantee of `verifyAssertion` | Tests 7 and 8 added; they fail -> TW-1. |

## Unable to test

- The OpenAPI regeneration path (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) was not re-run.
  Only the contract test without the flag was run (pass). Follow-up: the parent worker records AC3 with its own run.
- Full server suite: cannot finish because of the test-JVM `OutOfMemoryError` in `FilesWebTests` (also without this
  run's tests). About 100 test classes never ran in `./gradlew check`. Follow-up: the parent worker decides. Options
  are to check the base, or to hold the task if `.taskcheck` cannot pass.
- Real browser or Android Credential Manager ceremonies: not run here. The software authenticator `TestAuthenticator`
  produces the same JSON. No e2e criterion names this.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion.
Defect TW-1 (Medium): `PasskeyService.verifyAssertion` join-fetches the account before it takes the row lock. A
passkey login that waits for a concurrent re-enrollment confirm therefore fails with an optimistic-lock error (HTTP
409) instead of continuing. In addition, `./gradlew check` (the server step of `.taskcheck`) currently fails with an
`OutOfMemoryError` in `FilesWebTests`, also without this run's tests.
