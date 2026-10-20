# Test Report

Date: 2026-10-20

Task: p0-auth-login - Login with password + TOTP, sessions, CSRF, lockout

Scope: `23edd3c563f77c5be6c77cea88decf2a6b0bc1a8..acecccd9342b8447e4cac31d8f777e57003dcd41` on branch `main` (12 task
commits, all prefixed `p0-auth-login:`; base and range supplied by the parent), excluding `tasks/` and `reviews/`. Final
pass after the CR-1 (second review) fix in `acecccd` (`reviews/p0-auth-login/fixes.md`, third pass): `LoginService.authenticate()`
removes an expired session with the bulk `sessions.deleteByTokenHash(session.tokenHash)` instead of the entity delete
`sessions.delete(session)`, so parallel requests carrying the same expired cookie get 401 instead of a 500. The previous
report used base `8fdb537`. That commit is an ancestor of `23edd3c`, and `8fdb537..23edd3c` only changes
`tasks/p0-auth-login.md` and `tasks/p0-ci.md`, so the production scope is the same. No uncommitted or untracked changes.

Requirements:
- `tasks/p0-auth-login.md` acceptance criteria and recorded assumptions
- SPEC.md §4.1 (via the task), `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md` (fictitious test data)
- Second review finding CR-1 (`reviews/p0-auth-login/review.md`), fix report `reviews/p0-auth-login/fixes.md` (third pass)
- Parent instructions for this pass (commands to run, no production changes)

## Test files changed

None in this pass. The fixer committed the regression test for CR-1 in `acecccd`
(`server/src/test/kotlin/ch/sonensei/cockpit/core/auth/LoginEdgeCaseTests.kt`).

## Tests added or updated

Count: 0

Reason: I reviewed the regression test `LoginEdgeCaseTests > parallel requests with the same expired session all end
anonymous without an error` and found it adequate:
- It runs the real `LoginService.authenticate()` with its transaction against Testcontainers Postgres. The test class is
  not `@Transactional`, so each thread gets its own transaction and the race on the row is real. Nothing is mocked.
- 12 threads start together on a `CyclicBarrier`, and this repeats for 5 rounds. The fixer saw it fail before the fix
  (round 0, 3 of 12 calls threw `Unexpected row count ... delete from auth_session where id=? and version=?`).
- It checks the required outcome: no exception, every call returns null (anonymous), and the row is gone. It then checks
  the HTTP behavior the finding names: 4 parallel `GET /api/v1/auth/me` with one expired cookie each answer 401.
- It uses the test clock (`clock.advance(AuthSession.BROWSER_SESSION_IDLE)`), which lands exactly on the expiry boundary
  (`isExpiredAt` is `!now.isBefore(expiresAt)`).

The test is probabilistic, because it depends on thread overlap. A deterministic version would have to mock the
repository and assert which delete method gets called. That would test an implementation detail and miss the Hibernate
behavior that caused the bug, so I did not add one. Remembered sessions that expire go through the same code path. The
existing tests already cover single-request expiry for both lifetimes, so no separate test is needed.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 session cookie attributes, remember/browser lifetime, logout, me | LoginWebTests, LoginEdgeCaseTests, SessionCookiesTests, LoginSecretHidingTests | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` | Pass |
| AC1 + CR-1 (second review): an expired session hit by parallel requests ends anonymous / 401, not 500 | LoginEdgeCaseTests `parallel requests with the same expired session all end anonymous without an error` | same | Pass |
| AC2 CSRF double-submit, no token -> 403; token rotated on login/logout (recorded assumption) | CsrfCookieWebTests, LoginWebTests `login and logout without the CSRF token are rejected with 403` | same | Pass |
| AC3 recovery codes single-use; wrong password/TOTP -> same 401 | LoginWebTests, LoginEdgeCaseTests (recovery-code tests, concurrent recovery-code race), LoginSecretHidingTests | same | Pass |
| AC4 5 failures -> exponential backoff, 429 + Retry-After, reset on success (test clock); atomic admission; reset only after the session is saved (TW-1) | LoginWebTests `5 consecutive failures...`, LoginThrottleTests, LoginEdgeCaseTests `parallel logins with a wrong password cannot slip past the backoff`, `Retry-After is rounded up...`, LoginServiceThrottleTests (4 tests) | same | Pass |
| AC5 `@WithCockpitUser`, unauthenticated `/api/**` -> 401, health + SPA public | SecurityConfigurationTests, SpaFallbackTests, LoginEdgeCaseTests | `cd server && ./gradlew clean check jacocoTestReport` | Pass |
| AC6 `./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` passes (Testcontainers Postgres) | whole package, 15 suites / 119 tests | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` | Pass (119/119) |
| AC7 `OpenApiContractTest` passes without the flag | OpenApiContractTest | `cd server && ./gradlew test --tests '*OpenApiContractTest'` (no `UPDATE_OPENAPI`) | Pass (1 test; `git status` clean afterwards). I did not repeat the regeneration with the flag: the fix does not touch the API |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` (13:17:53 - 13:18:32 +02:00; the `test` task ran,
it was not up-to-date)

Result:

Pass: BUILD SUCCESSFUL; 15 suites, 119 tests, 0 failures, 0 errors, 0 skipped (summed from
`server/build/test-results/test/TEST-ch.sonensei.cockpit.core.auth.*.xml`). The CR-1 regression test ran in 0.338 s.

## Full test execution

Command:

`cd server && ./gradlew clean check jacocoTestReport` (13:18:51 - 13:19:51 +02:00; 15 tasks executed)

Result:

Pass: BUILD SUCCESSFUL; 50 suites, 665 tests, 0 failures, 0 errors, 0 skipped. ktlint (main, test, script) found no
violations. Afterwards `cd server && ./gradlew test --tests '*OpenApiContractTest'` without the flag (13:20:16 - 13:20:26)
also gave BUILD SUCCESSFUL (1 test, 0 failures).

## .taskcheck

Result:

Pass: `sh scripts/check.sh` (13:20:33 - 13:21:35 +02:00, about 62 s; the script uses `set -eu`) printed
"== all checks passed" after the tfp gate tests, server, web and android steps.

## Bugs found

None in this pass. CR-1 (second review) is fixed in `acecccd`, and its regression test passes.

## Coverage

Status: verified. Fresh, clean run of the full server suite on the final revision; all tests passed.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured, because no changed-line tooling is configured. The whole-file line counters show the
fix's changed line `LoginService.kt:82` (`sessions.deleteByTokenHash(session.tokenHash)`) as covered (6 instructions
covered, 0 missed), and the expiry branch at line 80 has both branches covered.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `p0-auth-login`; base
  `23edd3c563f77c5be6c77cea88decf2a6b0bc1a8` (supplied by the parent); HEAD `acecccd9342b8447e4cac31d8f777e57003dcd41`;
  task commits `git log 23edd3c..HEAD` (12, all `p0-auth-login:`); file scope from
  `git diff --name-only 23edd3c..HEAD -- server/src/main web/src android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `acecccd`, clean tree (`git status --short
  --untracked-files=all` was empty before and after all runs); `git ls-files -s server/src | sha256sum` =
  `3c6c168ad13d39346260277e141a26d1d7f72249736a57b6c2ccf4f0f34c7d2d` before and after.
- Exact included files (under `server/src/main/kotlin/ch/sonensei/cockpit/`): `core/auth/api/AuthController.kt`,
  `core/auth/domain/{AuthSession,LoginService,LoginThrottle,UserAccount}.kt`,
  `core/auth/infra/{AuthSessionRepository,SecurityConfiguration,SessionAuthenticationFilter,SessionCookies}.kt`.
  Excluded:
  - `server/src/main/resources/application-dev.properties`: configuration, asserted by SessionCookiesTests.
  - Migration `V202610201100__core_auth_session.sql`: SQL that JaCoCo cannot instrument; every DB test runs it.
  - `web/src/app/api/generated/**` and `api/openapi.yaml`: generated, checked by OpenApiContractTest.
  - `docs/adr/0001-*`: documentation.

  No files were renamed or deleted. All 9 Kotlin files are in the report.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (default tool
  version, not pinned in `build.gradle.kts`), Gradle 9.8.1. Ran `./gradlew clean check jacocoTestReport` in `server/` (full
  server suite). Per-file counters (INSTRUCTION, BRANCH, METHOD, LINE) were read from the package/sourcefile elements of
  the XML with `.tmp/jacoco_scope.py`.
- Run start/completion time and exit status: 13:18:51 - 13:19:51 +02:00, BUILD SUCCESSFUL.
- Generated report paths and report hashes/run identifier:
  - `server/build/reports/jacoco/test/jacocoTestReport.xml`, written 13:19:50.98, sha256
    `6fa8bb9c33e1c9aedee98b08aed00cfff747bb84ef5d52cf611f6eb1215fd0f8`
  - exec file `server/build/jacoco/test.exec`, written 13:19:49.99, sha256
    `ea6a9b81c609261e16f91c723f175168dbe5ca34df16d3f6482a68937b69afc1`
  - JaCoCo session id `dell26-8191f303`, the only session in the report
- Evidence outputs are fresh and inputs stayed unchanged: `clean` removed `build/` first, the report has a single new
  session id, and the snapshot hash above did not change.
- Final run after latest test additions and production fixes: yes. It ran on revision `acecccd`, which includes the CR-1
  fix, and no test or production code changed after it.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. Statements = JaCoCo INSTRUCTION
counter, Functions = METHOD.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `core/auth/api/AuthController.kt` | 323/325 (99.4%) | 32/32 (100%) | 16/16 (100%) | 45/45 (100%) |
| `core/auth/domain/AuthSession.kt` | 81/89 (91.0%) | 4/4 (100%) | 13/15 (86.7%) | 18/18 (100%) |
| `core/auth/domain/LoginService.kt` | 591/597 (99.0%) | 48/50 (96.0%) | 36/37 (97.3%) | 77/77 (100%) |
| `core/auth/domain/LoginThrottle.kt` | 120/120 (100%) | 12/12 (100%) | 8/8 (100%) | 20/20 (100%) |
| `core/auth/domain/UserAccount.kt` | 137/152 (90.1%) | 12/12 (100%) | 18/22 (81.8%) | 30/31 (96.8%) |
| `core/auth/infra/AuthSessionRepository.kt` | N/A (interface, no bytecode; Spring Data proxy, exercised by the DB tests) | N/A | N/A | N/A |
| `core/auth/infra/SecurityConfiguration.kt` | 221/223 (99.1%) | 2/4 (50.0%) | 10/10 (100%) | 34/34 (100%) |
| `core/auth/infra/SessionAuthenticationFilter.kt` | 93/95 (97.9%) | 10/10 (100%) | 5/6 (83.3%) | 22/23 (95.7%) |
| `core/auth/infra/SessionCookies.kt` | 141/141 (100%) | 14/14 (100%) | 8/8 (100%) | 25/25 (100%) |
| **Aggregate (summed counters, 8 measured files; AuthSessionRepository has no bytecode, none missing)** | 1707/1742 (98.0%) | 134/138 (97.1%) | 114/122 (93.4%) | 271/273 (99.3%) |

Change from the previous pass: `AuthSession.tokenHash` is now read in production (the fix passes it to
`deleteByTokenHash`), so its getter is covered: AuthSession line 25 is now covered and the file reaches 18/18 lines.
LoginService gained 2 instructions from the new call and they are covered. Line numbers moved by one because of the added
comment.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `LoginService.kt:104` (2 branches: `recoveryCode == null` with `totp == null`) | Cannot be reached through the API: the controller rejects requests without exactly one factor (tested, 400) | Justified gap, defensive code |
| `LoginService.kt:149` (`CockpitPrincipal` data-class generated members) | Generated code | Justified gap |
| `SecurityConfiguration.kt:93` (2 branches: CSRF token attribute missing or not a `CsrfToken`) | Defensive; Spring always sets the attribute after `CsrfFilter` | Justified gap |
| `SessionAuthenticationFilter.kt:55` (`getCredentials`) | Trivial, returns null | Justified gap |
| `AuthSession.kt:29,31` (unused Kotlin accessors/setters of JPA fields) | Trivial | Justified gap |
| `UserAccount.kt:24,26,28,57` (pre-existing accessors from p0-auth-setup) | Not task behavior | Justified gap |
| `AuthController.kt:148` (synthetic default-argument constructor of `LoginRequest`) | Generated code | Justified gap |

## Unable to test

- A deterministic interleaving of the expired-session race (row deleted between `findByTokenHash` and the delete). This
  would need a fault-injecting repository or datasource. The barrier-based regression test reproduced the bug before the
  fix and passes after it. Low risk; no follow-up needed.
- An error raised only at transaction commit, after `LoginService.login()` returns (and so after `recordSuccess()`). The
  earlier fix report lists this as out of scope. It would need a fault-injecting datasource. Low risk.
- Whether the pre-login `XSRF-TOKEN` value is still accepted after login. CSRF handling is stateless double-submit (a
  recorded assumption), so this is by design. Not a requirement.
- Real browser behavior of `SameSite=Lax`/`Secure` and the browser-session cookie lifetime. This needs a browser, and
  Playwright e2e is not in this task's criteria. The tests assert the attributes on the `Set-Cookie` headers.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. The CR-1 regression test from `acecccd` is
adequate and passes. On revision `acecccd9342b8447e4cac31d8f777e57003dcd41`, all of these pass: the focused auth package
(119 tests), `./gradlew clean check` (665 tests), `OpenApiContractTest` without the flag, and `.taskcheck`.
