# Test Report

Date: 2026-10-20

Task: p0-auth-login - Login with password + TOTP, sessions, CSRF, lockout

Scope: `e93430e72144890d4d99396d53647ce3f8043ab6..32595e2ecc1eb090b8d97ab601f47deecfe2ea03` on branch `main` (7 task commits,
all prefixed `p0-auth-login:`; base and range supplied by the parent). Second pass after the fixes in `32595e2`
(`reviews/p0-auth-login/fixes.md`: CR-1 atomic `LoginThrottle.tryBeginAttempt`, CR-2 `XSRF-TOKEN` rotation on login and
logout). Plus one new, uncommitted test file (below). No other uncommitted changes.

Requirements:
- `tasks/p0-auth-login.md` acceptance criteria and recorded assumptions, in particular the CR-1/CR-2 assumption: "A login
  attempt is counted against the throttle when it starts (CR-1), so a login ending in an exception (e.g. 409
  recovery-code race) counts as a failure", and "failures 1-4 free ... a success resets"
- SPEC.md §4.1 (via the task), `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md` (fictitious test data)
- Parent instructions for this pass (commands to run, OpenAPI regeneration check)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/LoginServiceThrottleTests.kt` (new, untracked; plain unit test:
  real `LoginThrottle`, `Totp`, `MutableClock`; `UserAccountRepository`/`AuthSessionRepository` mocked with MockK, which is
  already a declared test dependency; a fast fake `PasswordEncoder` instead of Argon2)

No existing test was modified. The fixer's tests from `32595e2` (CsrfCookieWebTests rotation tests, LoginThrottleTests
admission/concurrency tests, LoginEdgeCaseTests parallel-guess test) were reviewed and cover CR-1/CR-2 at the throttle and
HTTP level; the gap left was how `LoginService` drives the throttle when a login fails half-way, which needs forced
failures that the Postgres-backed tests cannot produce deterministically.

## Tests added or updated

Count: 4 (all in LoginServiceThrottleTests)

- `a blocked attempt is rejected before the account is even loaded` - Pass (blocked attempts do not check credentials)
- `a correct fifth attempt logs in and lifts the block it started` - Pass (the admission of the 5th attempt starts a block;
  its success must lift it and reset the count)
- `an attempt that throws during the credential check counts as a failure` - Pass (recorded assumption)
- `an attempt that throws while creating its session is not a success and does not reset the backoff` - **Fail** (TW-1)

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 session cookie attributes, remember/browser lifetime, logout, me | LoginWebTests, LoginEdgeCaseTests, SessionCookiesTests, LoginSecretHidingTests (existing) | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` | Pass |
| AC2 CSRF double-submit, no token -> 403; token rotated on login/logout (CR-2 assumption) | CsrfCookieWebTests (incl. `login and logout replace the XSRF-TOKEN cookie...`, `logout without a session replaces...`, `a failed login keeps the XSRF-TOKEN cookie`), LoginWebTests `login and logout without the CSRF token are rejected with 403` | same | Pass |
| AC3 recovery codes single-use; wrong password/TOTP -> same 401 | LoginWebTests, LoginEdgeCaseTests (recovery-code tests, concurrent recovery-code race), LoginSecretHidingTests | same | Pass |
| AC4 5 failures -> exponential backoff, 429 + Retry-After, reset on success (test clock); atomic admission (CR-1) | LoginWebTests `5 consecutive failures...`, LoginThrottleTests (10), LoginEdgeCaseTests `parallel logins with a wrong password cannot slip past the backoff`, `Retry-After is rounded up...`; LoginServiceThrottleTests `a blocked attempt is rejected before...`, `a correct fifth attempt...`, `an attempt that throws during the credential check...` | same | Pass |
| AC4 + assumption: a login that does not succeed (ends in an exception) counts as a failure, only a success resets | LoginServiceThrottleTests `an attempt that throws while creating its session is not a success and does not reset the backoff` | same | **Fail** (TW-1) |
| AC5 `@WithCockpitUser`, unauthenticated `/api/**` -> 401, health + SPA public | SecurityConfigurationTests, SpaFallbackTests, LoginEdgeCaseTests (existing) | `cd server && ./gradlew clean check jacocoTestReport --continue` | Pass (these suites; the build as a whole fails because of TW-1) |
| AC6 `./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` passes (Testcontainers Postgres) | whole package, 15 suites / 118 tests | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` | **Fail**: 117 pass, 1 fails (TW-1) |
| AC7 `api/openapi.yaml` regenerated, `OpenApiContractTest` passes without the flag | OpenApiContractTest | `cd server && UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`, then `cd web && npm run api:gen`, then `git status --short` / `git diff --stat -- api/ web/src/app/api/generated` -> empty; then `cd server && ./gradlew test --tests '*OpenApiContractTest'` without the flag | Pass (no diff; BUILD SUCCESSFUL both times) |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.LoginServiceThrottleTests'`, then
`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` (12:54:12 - 12:54:47 +02:00)

Result:

Fail - 15 suites, 118 tests, 1 failure, 0 errors, 0 skipped (counted from `build/test-results/test/*.xml`). The only
failure is `LoginServiceThrottleTests > an attempt that throws while creating its session is not a success and does not
reset the backoff()`: `Expected PT30S but actual was null` (TW-1).

## Full test execution

Command:

`cd server && ./gradlew clean check jacocoTestReport --continue` (finished 12:55:53 +02:00, ~50 s)

Result:

Fail - 664 tests, 1 failed (TW-1), BUILD FAILED, non-zero exit. ktlint tasks ran (`build/reports/ktlint/*`) and reported
no violation; OpenApiContractTest passed. Before this pass the fixer reported 660 tests passing at `32595e2`; the 4 new
tests account for the difference.

## .taskcheck

Result:

Fail - `sh scripts/check.sh`: "tfp: completion gate tests" passed, then "server: ktlint + tests" failed with
"664 tests completed, 1 failed" (TW-1). The web and android steps did not run after the server failure.

## Bugs found

### A login that throws after the credential check resets the backoff although it did not succeed

Finding ID:

`TW-1`

Test:

`LoginServiceThrottleTests > an attempt that throws while creating its session is not a success and does not reset the backoff`

Production source:

`server/src/main/kotlin/ch/sonensei/cockpit/core/auth/domain/LoginService.kt:59-66` (`throttle.recordSuccess()` is called
before `sessions.deleteExpired(now)` and `sessions.saveAndFlush(...)`, inside the `@Transactional` method)

Requirement:

AC4 "5 consecutive failures -> exponential backoff ..., reset on success", and the recorded assumption (CR-1): "A login
attempt is counted against the throttle when it starts, so a login ending in an exception (e.g. 409 recovery-code race)
counts as a failure."

Expected behavior:

After 4 failed logins, a 5th attempt with correct credentials whose session cannot be created (the save throws, the
transaction rolls back, no session cookie, the client gets an error) is not a success: the throttle keeps it counted as
the 5th failure and blocks for 30 s (`throttle.retryAfter() == PT30S`).

Actual behavior:

`recordSuccess()` has already reset the counter and lifted the block before the session is saved, so after the exception
`throttle.retryAfter()` is `null` and the count starts over. The rolled-back database state (recovery code not consumed, no
session) and the in-memory throttle state disagree. The same ordering applies to the 409 recovery-code race the assumption
names: the losing request fails in `saveAndFlush` (optimistic lock on the recovery code), after its own `recordSuccess()`.
In that race a winner also resets the counter, so the race itself shows no visible difference; the test forces the
failure with a mocked `saveAndFlush` to show the ordering deterministically.

Severity:

Low - reaching `recordSuccess()` requires the correct username, password and a valid second factor, so this does not let
a guessing attacker bypass the backoff; it is a mismatch with the recorded rule "only a success resets" and the CR-1
assumption. (One possible fix, for the fixer to decide: reset the throttle only once the session is saved, e.g. after
`saveAndFlush`, or after commit via a transaction synchronization.)

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Coverage

Status: partial-failed - fresh run of the full server suite on the final snapshot, but the test task failed (1 test,
TW-1), so the numbers are diagnostic only. They are the counters of the run in which 663 of 664 tests passed.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (no changed-line tooling is configured).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `p0-auth-login`; base
  `e93430e72144890d4d99396d53647ce3f8043ab6` (supplied by the parent); HEAD `32595e2ecc1eb090b8d97ab601f47deecfe2ea03`;
  task commits `git log e93430e..HEAD` (7, all `p0-auth-login:`); file scope from
  `git diff --name-only e93430e..HEAD -- server/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `32595e2` with a clean tracked tree plus the untracked
  `LoginServiceThrottleTests.kt` sha256 `a04c0e9b3aabebfb9556a690fa0ab50bd8b7feecb2c7cc8f0084ea67c0aff9c0` (same hash before
  the focused run and after the coverage run; `git status --short` shows only this file).
- Exact included files (under `server/src/main/kotlin/ch/sonensei/cockpit/`): `core/auth/api/AuthController.kt`,
  `core/auth/domain/{AuthSession,LoginService,LoginThrottle,UserAccount}.kt`,
  `core/auth/infra/{AuthSessionRepository,SecurityConfiguration,SessionAuthenticationFilter,SessionCookies}.kt`.
  Excluded: `application-dev.properties` (configuration; asserted by SessionCookiesTests), migration
  `V202610201100__core_auth_session.sql` (SQL, exercised by every DB test, not instrumentable by JaCoCo), `api/openapi.yaml`
  and `web/src/app/api/generated/**` (generated; checked by regeneration, see AC7), `docs/adr/0001-*`, `reviews/**` (docs).
  No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (default tool
  version, not pinned in `build.gradle.kts`), Gradle 9.8.1, JDK 21.0.12.1; in `server/`:
  `./gradlew clean check jacocoTestReport --continue` (full server suite). Because `jacocoTestReport` is skipped when
  `test` fails, the report was then generated from that run's exec file with `./gradlew jacocoTestReport -x test`.
- Run start/completion time and exit status: test run ended 12:55:52 +02:00 (`build/jacoco/test.exec` written 12:55:52.938),
  BUILD FAILED (1 failed test); report generated 12:56:01 +02:00, BUILD SUCCESSFUL.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256
  `edc114f0c5c41d344ae892630acb9881475bc94f74de1092d31ef729a70ac8b4`; exec `server/build/jacoco/test.exec` sha256
  `3b4f66325bd5ce9a2119613a82c2f53d614a1fe23f461e63f5a6b88bce4597f3`; JaCoCo session id `dell26-3ada0098`.
- Evidence outputs are fresh and inputs stayed unchanged: `clean` removed `build/` first (the `build/jacoco/` directory was
  created 12:55:09 in this run); no source or test file changed during or after the run.
- Final run after latest test additions and production fixes: yes for the test additions and the fixes in `32595e2`; a new
  run is needed after TW-1 is fixed.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. Statements = JaCoCo INSTRUCTION
counter, Functions = METHOD.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `core/auth/api/AuthController.kt` | 323/325 (99.4%) | 32/32 (100%) | 16/16 (100%) | 45/45 (100%) |
| `core/auth/domain/AuthSession.kt` | 78/89 (87.6%) | 4/4 (100%) | 12/15 (80.0%) | 17/18 (94.4%) |
| `core/auth/domain/LoginService.kt` | 589/595 (99.0%) | 48/50 (96.0%) | 36/37 (97.3%) | 77/77 (100%) |
| `core/auth/domain/LoginThrottle.kt` | 120/120 (100%) | 12/12 (100%) | 8/8 (100%) | 20/20 (100%) |
| `core/auth/domain/UserAccount.kt` | 137/152 (90.1%) | 12/12 (100%) | 18/22 (81.8%) | 30/31 (96.8%) |
| `core/auth/infra/AuthSessionRepository.kt` | N/A (interface, no bytecode; Spring Data proxy, exercised by the DB tests) | N/A | N/A | N/A |
| `core/auth/infra/SecurityConfiguration.kt` | 221/223 (99.1%) | 2/4 (50.0%) | 10/10 (100%) | 34/34 (100%) |
| `core/auth/infra/SessionAuthenticationFilter.kt` | 93/95 (97.9%) | 10/10 (100%) | 5/6 (83.3%) | 22/23 (95.7%) |
| `core/auth/infra/SessionCookies.kt` | 141/141 (100%) | 14/14 (100%) | 8/8 (100%) | 25/25 (100%) |
| **Aggregate (summed counters, 8 measured files; AuthSessionRepository has no bytecode)** | 1702/1740 (97.8%) | 134/138 (97.1%) | 113/122 (92.6%) | 270/273 (98.9%) |

The new code from `32595e2` is fully covered: `LoginThrottle.tryBeginAttempt` (both outcomes), `SessionCookies.rotateCsrfToken`
and the shared `csrfTokens` repository, and the two `rotateCsrfToken` calls in `AuthController` (login success, logout).
`UserAccount.kt` is counted whole although the task changed only `RecoveryCode.isUsed`/`markUsed` (both covered).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `LoginService.kt:59-66` (covered, but the order of `recordSuccess()` and the session save is wrong for a failing save) | AC4 reset only on success; CR-1 assumption | LoginServiceThrottleTests (new), finding TW-1 |
| `LoginService.kt:102` (2 branches: `recoveryCode == null` with `totp == null`) | not reachable through the API: the controller rejects requests without exactly one factor (tested, 400) | justified gap, defensive |
| `LoginService.kt:147` (`CockpitPrincipal` data-class generated members) | generated code | justified gap |
| `SecurityConfiguration.kt:93` (2 branches: CSRF token attribute missing/not a `CsrfToken`) | defensive; Spring always sets the attribute after `CsrfFilter` | justified gap |
| `SessionAuthenticationFilter.kt:55` (`getCredentials`) | trivial, returns null | justified gap |
| `AuthSession.kt:25,29,31` (unused Kotlin accessors/setters of JPA fields) | trivial | justified gap |
| `UserAccount.kt:24,26,28,57` (pre-existing accessors from p0-auth-setup) | not task behavior | justified gap |
| `AuthController.kt:148` (synthetic default-argument constructor of `LoginRequest`) | generated code | justified gap |

## Unable to test

- Whether the pre-login `XSRF-TOKEN` value is still accepted after login: the CSRF handling is stateless double-submit
  (cookie and header of the same request are compared, recorded assumption), so an old value sent in both places still
  matches by design. Not a requirement; no test, no follow-up from the tests' side.
- The per-request `XSRF-TOKEN` re-issue on authenticated responses (observation in `fixes.md`) is pre-existing behavior and
  covered by the assumption "rotated ... on every authenticated response"; not tested further here.
- Real browser behavior of `SameSite=Lax`/`Secure` and browser-session cookie lifetime (needs a browser; Playwright e2e is not
  in this task's criteria). Attributes are asserted on `Set-Cookie` headers.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion.
TW-1 (Low): `LoginService.login` resets the throttle before the session is saved, so a login that fails after the credential
check (exception while saving) resets the backoff although it did not succeed. Because the new test fails,
`./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'`, `./gradlew check` and `.taskcheck` currently fail. The fixes for
CR-1 and CR-2 are otherwise covered and pass; the OpenAPI regeneration produced no diff.
