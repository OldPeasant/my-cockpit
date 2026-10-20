# Test Report

Date: 2026-10-20

Task: p0-auth-login - Login with password + TOTP, sessions, CSRF, lockout

Scope: `ad1a2064bdf53727253298be84092c021e07a147..2d407816d428a210592aa75bfe8cd7e1c175ede7` on branch `main`
(4 task commits `1068bca`, `996c502`, `9660aca`, `2d40781`, all prefixed `p0-auth-login:`), plus the new,
uncommitted test files listed below. No other uncommitted changes.

Requirements:
- `tasks/p0-auth-login.md` acceptance criteria and recorded assumptions (12 h idle timeout, backoff 30 s doubling from
  the 5th failure, max 1 h, blocked attempts do not count, login/logout need CSRF, `cockpit.auth.secure-cookie`)
- SPEC.md §4.1 (via the task), `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md` (log hygiene, fictitious data)
- Parent instructions (context reuse, CSRF tests stay in `CsrfCookieWebTests`)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/LoginThrottleTests.kt` (new, plain unit test, no Spring context)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/SessionCookiesTests.kt` (new, plain unit test)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/LoginSecretHidingTests.kt` (new, plain unit test)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/LoginEdgeCaseTests.kt` (new; `@SpringBootTest @AutoConfigureMockMvc
  @Import(MutableClockTestConfiguration::class)`, the same context key as `LoginWebTests`/`SetupEdgeCaseTests`, so no new
  context or connection pool; uses the `csrf()` post-processor like `LoginWebTests`)

Existing tests were not modified.

## Tests added or updated

Count: 36 (LoginThrottleTests 8, SessionCookiesTests 6, LoginSecretHidingTests 4, LoginEdgeCaseTests 18)

- LoginThrottleTests: fresh throttle allows; 4 failures free, 5th blocks 30 s; block ends exactly at its end (1 ms before
  still blocked); each further failure doubles from its own time; success resets counter and lifts an active block;
  1000 failures cap at 1 h; delay table incl. negative/zero inputs and the cap boundary (21/22); concurrent failures all counted.
- SessionCookiesTests: dev profile (and only it) sets `cockpit.auth.secure-cookie=false`; with false the session cookie
  is HttpOnly + SameSite=Lax + Path=/ without Secure, browser-session cookie has no Max-Age/Expires, remembered has
  Max-Age 30 d; default is Secure; clearing yields Max-Age=0 with same attributes; XSRF-TOKEN follows Secure, is not
  HttpOnly, Path=/, SameSite=Lax; reading ignores missing/other/blank cookies.
- LoginSecretHidingTests: `LoginRequest`/`LoginCredentials`/`SessionGrant` `toString` hide password, TOTP, recovery code,
  token; recovery code `markUsed` twice throws and keeps the first time; `AuthSession.isExpiredAt` boundary.
- LoginEdgeCaseTests: browser session valid at 12 h - 1 µs, expired at exactly 12 h; remembered session expired at exactly
  30 d; expiry written at most once per minute (59 s: no re-issued cookie, DB unchanged; 60 s: cookie re-issued, DB slid);
  browser session slides on the server but its cookie is never re-issued as persistent; login deletes expired sessions;
  deleting the account cascades its sessions; logout with expired/unknown cookie -> 204 + clear; an existing
  authentication is not replaced/cleared by a stale cookie; no account at all -> same 401 and counts towards backoff;
  `me` fallback for a non-Cockpit principal; token never in the response body; recovery code not consumed by a wrong
  username/password or by a 429-blocked attempt; blank `totp` next to a recovery code (and vice versa) counts as absent;
  4 concurrent logins with the same recovery code -> exactly one session, losers only with optimistic-lock failure;
  Retry-After rounds 0.5 s up to `1` and the block ends exactly at 30 s; 9 malformed/oversized/missing-field/invalid-JSON
  bodies -> 400 problems and do not count as failures; log hygiene (no password, wrong password, recovery code, token or
  token hash anywhere in captured output; no TOTP codes in log messages; "Login failed", "Login succeeded", "Logged out" present).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 login creates session cookie HttpOnly, Secure outside dev, SameSite=Lax | LoginWebTests (existing) `login with password and TOTP sets...`; SessionCookiesTests `with secure-cookie=false ...`, `by default ... Secure`, `the dev profile, and only it, turns the Secure flag off`, `clearing the cookie ...` | `./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` | Pass |
| AC1 30-day sliding lifetime with remember, browser session otherwise | LoginWebTests (existing) remember/slide/idle tests; LoginEdgeCaseTests `a remembered session ends exactly 30 days...`, `the expiry of a remembered session is written at most once per minute`, `a browser-session cookie slides on the server...`, `a session without remember device is valid until just before 12 hours...`, `a login removes sessions that have already expired`; LoginSecretHidingTests `a session is expired from its expiry instant on` | same | Pass |
| AC1 logout, me | LoginWebTests (existing) logout tests; LoginEdgeCaseTests `logout with an expired or unknown session cookie...`, `me falls back to the authentication name...`, `deleting the account ends its sessions`, `the login response carries the token only in the cookie` | same | Pass |
| AC2 CSRF double-submit, no token -> 403 | CsrfCookieWebTests, LoginWebTests (existing), SessionCookiesTests `the XSRF-TOKEN cookie follows the Secure setting...` | same | Pass |
| AC3 recovery codes single-use | LoginWebTests (existing) `a recovery code logs in only once`; LoginEdgeCaseTests `a recovery code is not consumed by an attempt with a wrong username or password`, `... blocked by the backoff`, `concurrent logins with the same recovery code create at most one session`, `a blank totp next to a recovery code counts as absent`; LoginSecretHidingTests `a recovery code can be consumed only once` | same | Pass |
| AC3 wrong password/TOTP -> 401 without hint | LoginWebTests (existing); LoginEdgeCaseTests `without any account a login is the same 401...` | same | Pass |
| AC4 5 failures -> exponential backoff, 429 + Retry-After, reset on success (test clock) | LoginWebTests (existing); LoginThrottleTests (8 tests); LoginEdgeCaseTests `Retry-After is rounded up...`, `malformed or oversized requests ... do not count` | same | Pass |
| AC5 `@WithCockpitUser`, unauthenticated `/api/**` -> 401, health + SPA public | SecurityConfigurationTests, SpaFallbackTests (existing); LoginEdgeCaseTests `an authentication already present is not replaced...` | `./gradlew clean check jacocoTestReport` | Pass |
| AC6 `./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` passes (Testcontainers Postgres) | whole package, 14 suites / 108 tests | `./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` | Pass |
| AC7 OpenAPI regenerated and `OpenApiContractTest` passes without the flag | OpenApiContractTest (existing, part of `check`, run without `UPDATE_OPENAPI`) | `./gradlew clean check jacocoTestReport` | Pass (regeneration itself not re-run by me) |
| Log hygiene (AGENTS.md: no secrets at INFO+) | LoginEdgeCaseTests `login never logs the password, second factor or session token`; LoginSecretHidingTests toString tests | `./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` | Pass |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` (after `./gradlew ktlintFormat`)

Result:

Pass - BUILD SUCCESSFUL, 14 suites, 108 tests, 0 failures, 0 errors, 0 skipped (counted from `build/test-results/test/*.xml`).

## Full test execution

Command:

`cd server && ./gradlew clean check jacocoTestReport` (12:32:04 - 12:32:55 +02:00)

Result:

Pass - BUILD SUCCESSFUL in 50 s; 49 suites, 654 tests, 0 failures, 0 errors, 0 skipped (618 before the new tests); ktlint and
OpenApiContractTest included in `check`.

## .taskcheck

Result:

Pass - `sh scripts/check.sh` from the repo root (server, web, android) ended with `== all checks passed`.

## Bugs found

None. No production defect was found; all new tests pass against the implementation at `2d40781`.

## Coverage

Status: verified (fresh run after the latest test additions; no production changes during or after the run).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (JaCoCo XML has line counters per file; no changed-line tooling is configured).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `p0-auth-login`; base
  `ad1a2064bdf53727253298be84092c021e07a147` (supplied by the parent, merge-base with origin/main); HEAD
  `2d407816d428a210592aa75bfe8cd7e1c175ede7`; task commits `git log ad1a206..HEAD` (4, all `p0-auth-login:`); file scope
  from `git diff --name-only ad1a206..HEAD -- server/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `2d40781` plus untracked test files (sha256)
  LoginEdgeCaseTests.kt `08fc492cda008e0532ba290fa20f8e53277935da42af8bd88d16eebfe6d295bf`, LoginThrottleTests.kt
  `0a761a64b0160a068fda37ffee33af78b303dabd7843c9d9dd4c4731dc25912b`, SessionCookiesTests.kt
  `4c09baff1e50bfdef579036e33f95399b21e71a5e69e2b0f6d2ff0b7f0d039ef`, LoginSecretHidingTests.kt
  `97e6804d6d981812e095f6bf6ba82f39d35b9a67a542632bdac92a9f5df9871f` (taken before the run; no file changed afterwards).
- Exact included files: `core/auth/api/AuthController.kt`, `core/auth/domain/{AuthSession,LoginService,LoginThrottle,UserAccount}.kt`,
  `core/auth/infra/{AuthSessionRepository,SecurityConfiguration,SessionAuthenticationFilter,SessionCookies}.kt` (all under
  `server/src/main/kotlin/ch/sonensei/cockpit/`). Excluded: `application-dev.properties` (configuration only; its value is
  asserted by SessionCookiesTests), migration `V202610201100__core_auth_session.sql` (SQL, exercised by every DB test, not
  instrumentable by JaCoCo), `api/openapi.yaml` and `web/src/app/api/generated/**` (generated), `docs/adr/0001-*` (docs).
  No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo 0.8.15.202606040825 (Gradle `jacoco` plugin,
  Gradle 9.8.1, JDK 21.0.12.1); `./gradlew clean check jacocoTestReport` in `server/`; full server test suite.
- Run start/completion time and exit status: 2026-10-20 12:32:04 - 12:32:55 +02:00, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`
  sha256 `2749bc34d725b344c9b5c2df1bdb50a017e11872c82fc48644262cdff2a06480`; HTML in `server/build/reports/jacoco/test/html/`.
- Evidence outputs are fresh and inputs stayed unchanged: `clean` removed `build/` before the run; test file hashes and
  `git status` taken before the run, no edits afterwards.
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. Statements = JaCoCo INSTRUCTION
counter, Functions = METHOD.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `core/auth/api/AuthController.kt` | 313/315 (99.4%) | 32/32 (100%) | 16/16 (100%) | 43/43 (100%) |
| `core/auth/domain/AuthSession.kt` | 78/89 (87.6%) | 4/4 (100%) | 12/15 (80.0%) | 17/18 (94.4%) |
| `core/auth/domain/LoginService.kt` | 592/598 (99.0%) | 48/50 (96.0%) | 36/37 (97.3%) | 78/78 (100%) |
| `core/auth/domain/LoginThrottle.kt` | 110/110 (100%) | 10/10 (100%) | 8/8 (100%) | 19/19 (100%) |
| `core/auth/domain/UserAccount.kt` | 137/152 (90.1%) | 12/12 (100%) | 18/22 (81.8%) | 30/31 (96.8%) |
| `core/auth/infra/AuthSessionRepository.kt` | N/A (0/0, interface; Spring Data proxy) | N/A | N/A | N/A |
| `core/auth/infra/SecurityConfiguration.kt` | 221/223 (99.1%) | 2/4 (50.0%) | 10/10 (100%) | 34/34 (100%) |
| `core/auth/infra/SessionAuthenticationFilter.kt` | 93/95 (97.9%) | 10/10 (100%) | 5/6 (83.3%) | 22/23 (95.7%) |
| `core/auth/infra/SessionCookies.kt` | 125/125 (100%) | 14/14 (100%) | 7/7 (100%) | 22/22 (100%) |
| **Aggregate (summed counters, 8 measured files; AuthSessionRepository has no bytecode)** | 1669/1707 (97.8%) | 132/136 (97.1%) | 112/121 (92.6%) | 265/268 (98.9%) |

An intermediate run (12:29 - 12:30, before LoginSecretHidingTests and the two last edge cases were added) measured
1640/1707 instructions and 126/136 branches; it is superseded by the table above and only shows what the last additions
covered (no-account login, `me` fallback, `toString`, `markUsed` twice). Coverage before any of the new tests was not
measured. `UserAccount.kt` is counted whole although the task changed only `RecoveryCode.isUsed`/`markUsed`
(both fully covered now).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `LoginService.kt:102` (2 branches: `recoveryCode == null` with `totp == null`) | none reachable: the controller rejects requests without exactly one factor (tested, 400) | justified gap, defensive |
| `LoginService.kt:147` (`CockpitPrincipal` data-class `copy`/`componentN`) | generated code | justified gap |
| `SecurityConfiguration.kt:92` (2 branches: CSRF token attribute missing/not a `CsrfToken`) | defensive; Spring always sets the attribute after `CsrfFilter` | justified gap |
| `SessionAuthenticationFilter.kt:55` (`getCredentials`) | trivial, returns null | justified gap |
| `AuthSession.kt:25,29,31` (unused Kotlin accessors/setters of JPA fields) | trivial | justified gap |
| `UserAccount.kt:24,26,28,57` (pre-existing accessors from p0-auth-setup) | not task behavior | justified gap |
| `AuthController.kt:145` (synthetic default-argument constructor of `LoginRequest`) | generated code | justified gap |

## Unable to test

- Real browser behavior of `SameSite=Lax`/`Secure` and the browser-session cookie lifetime (needs a browser; e2e
  Playwright is out of this task's criteria). Attributes are asserted on the `Set-Cookie` header and the servlet cookie.
- The dev profile is not started as a whole application (it targets a local Postgres); the property value is asserted
  from `application-dev.properties` and its effect via `SessionCookies(secure = false)`. Follow-up not required.
- AC7 regeneration (`UPDATE_OPENAPI=1 ...`) was not re-run by me; `OpenApiContractTest` passed without the flag in `check`.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production defects found.
