# Test Report

Date: 2026-10-20

Task: p0-auth-login - Login with password + TOTP, sessions, CSRF, lockout

Scope: `8fdb53746871962175316feeaac371071578904b..cb1fe064bd6b6180b6bb4f27466da637991788c9` on branch `main` (9 task
commits, all prefixed `p0-auth-login:`; base and range supplied by the parent, base = `git merge-base HEAD origin/main`).
Third (final) pass after the TW-1 fix in `cb1fe06` (`reviews/p0-auth-login/fixes.md`, second pass: `throttle.recordSuccess()`
moved after `sessions.saveAndFlush(...)` in `LoginService.login`). The earlier pass used base `e93430e`; `e93430e..8fdb537`
changes only `tasks/p0-auth-login.md` and `tasks/p0-ci.md` (heartbeat lines), so the production scope is the same. No
uncommitted or untracked changes.

Requirements:
- `tasks/p0-auth-login.md` acceptance criteria and recorded assumptions (in particular CR-1: "a login ending in an exception
  ... counts as a failure"; backoff: "failures 1-4 free ... a success resets")
- SPEC.md §4.1 (via the task), `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md` (fictitious test data)
- Parent instructions for this pass (commands to run, no production changes)

## Test files changed

None in this pass. The TW-1 regression test
(`server/src/test/kotlin/ch/sonensei/cockpit/core/auth/LoginServiceThrottleTests.kt`) was committed in `8f1c657` and is
unchanged by the fix commit `cb1fe06` (only `LoginService.kt` and `fixes.md` changed there).

## Tests added or updated

Count: 0

Reason: the fix moved one call. The existing test `an attempt that throws while creating its session is not a success and
does not reset the backoff` is the regression test for it (it failed before the fix with `Expected PT30S but actual was
null` and now passes), and `a correct fifth attempt logs in and lifts the block it started` together with the web-level
reset test in LoginWebTests covers the success path, where `recordSuccess()` must still run after the save. A failing
`deleteExpired` takes the same path before `recordSuccess()` as a failing `saveAndFlush`, so a separate test would add
nothing. No real gap remains for the scope; no test was added to raise numbers.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 session cookie attributes, remember/browser lifetime, logout, me | LoginWebTests, LoginEdgeCaseTests, SessionCookiesTests, LoginSecretHidingTests | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` | Pass |
| AC2 CSRF double-submit, no token -> 403; token rotated on login/logout (CR-2 assumption) | CsrfCookieWebTests, LoginWebTests `login and logout without the CSRF token are rejected with 403` | same | Pass |
| AC3 recovery codes single-use; wrong password/TOTP -> same 401 | LoginWebTests, LoginEdgeCaseTests (recovery-code tests, concurrent recovery-code race), LoginSecretHidingTests | same | Pass |
| AC4 5 failures -> exponential backoff, 429 + Retry-After, reset on success (test clock); atomic admission (CR-1) | LoginWebTests `5 consecutive failures...`, LoginThrottleTests, LoginEdgeCaseTests `parallel logins with a wrong password cannot slip past the backoff`, `Retry-After is rounded up...`, LoginServiceThrottleTests (4 tests) | same | Pass |
| AC4 + assumption: a login that ends in an exception counts as a failure, only a success resets (TW-1 regression) | LoginServiceThrottleTests `an attempt that throws while creating its session is not a success and does not reset the backoff`, `an attempt that throws during the credential check counts as a failure` | same | Pass |
| AC5 `@WithCockpitUser`, unauthenticated `/api/**` -> 401, health + SPA public | SecurityConfigurationTests, SpaFallbackTests, LoginEdgeCaseTests | `cd server && ./gradlew clean check jacocoTestReport` | Pass |
| AC6 `./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` passes (Testcontainers Postgres) | whole package, 15 suites / 118 tests | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` | Pass (118/118) |
| AC7 `OpenApiContractTest` passes without the flag | OpenApiContractTest | `cd server && ./gradlew test --tests '*OpenApiContractTest'` (no `UPDATE_OPENAPI`) | Pass (1 test, BUILD SUCCESSFUL; `git status` clean afterwards). Regeneration with the flag was not repeated in this pass: the fix does not touch the API, and the previous pass's regeneration produced no diff |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` (13:02:50 - 13:03:30 +02:00)

Result:

Pass - BUILD SUCCESSFUL; 15 suites, 118 tests, 0 failures, 0 errors, 0 skipped (counted from
`server/build/test-results/test/*.xml`).

## Full test execution

Command:

`cd server && ./gradlew clean check jacocoTestReport` (13:03:41 - 13:04:31 +02:00)

Result:

Pass - BUILD SUCCESSFUL; 50 suites, 664 tests, 0 failures, 0 errors, 0 skipped; ktlint tasks (main, test, script) ran
without violations; OpenApiContractTest passed. Then `cd server && ./gradlew test --tests '*OpenApiContractTest'` without
the flag - BUILD SUCCESSFUL (1 test, 0 failures).

## .taskcheck

Result:

Pass - `sh scripts/check.sh` (13:05:17 - 13:06:23 +02:00, about 66 s): "== all checks passed" (server, web and android
steps).

## Bugs found

None in this pass. TW-1 (previous pass) is fixed in `cb1fe06`: its test now passes.

## Coverage

Status: verified - fresh, clean run of the full server suite on the final revision; all tests passed.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (no changed-line tooling is configured). The fix's changed lines
(`LoginService.kt:66-67`) are reported as covered by the whole-file line counters (no missed line in 60-70).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `p0-auth-login`; base
  `8fdb53746871962175316feeaac371071578904b` (supplied by the parent); HEAD `cb1fe064bd6b6180b6bb4f27466da637991788c9`;
  task commits `git log 8fdb537..HEAD` (9, all `p0-auth-login:`); file scope from
  `git diff --name-only 8fdb537..HEAD -- server/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `cb1fe06`, clean tree (`git status --short
  --untracked-files=all` empty before and after all runs); `git ls-files -s server/src | sha256sum` =
  `18126984e6d2745af2a253ff33cde38709cad7aaafa23a4583c5422514d4af1d` before and after.
- Exact included files (under `server/src/main/kotlin/ch/sonensei/cockpit/`): `core/auth/api/AuthController.kt`,
  `core/auth/domain/{AuthSession,LoginService,LoginThrottle,UserAccount}.kt`,
  `core/auth/infra/{AuthSessionRepository,SecurityConfiguration,SessionAuthenticationFilter,SessionCookies}.kt`.
  Excluded: `server/src/main/resources/application-dev.properties` (configuration; asserted by SessionCookiesTests), migration
  `V202610201100__core_auth_session.sql` (SQL, exercised by every DB test, not instrumentable by JaCoCo), `api/openapi.yaml`
  and `web/src/app/api/generated/**` (generated; checked by OpenApiContractTest), `docs/adr/0001-*`, `reviews/**` (docs).
  No renamed or deleted files. All 9 Kotlin files are present in the report.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (default tool
  version, not pinned in `build.gradle.kts`), Gradle 9.8.1; in `server/`: `./gradlew clean check jacocoTestReport` (full server
  suite). Per-file counters read from the XML with `.tmp/jacoco_scope.py` (package/sourcefile element counters INSTRUCTION,
  BRANCH, METHOD, LINE).
- Run start/completion time and exit status: 13:03:41 - 13:04:31 +02:00, BUILD SUCCESSFUL.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`
  (written 13:04:31.46) sha256 `a4bbc87b33e6b2882748c5e607b4210573fdf3b1b9927c76bb68b9af860755d8`; exec
  `server/build/jacoco/test.exec` (written 13:04:30.40) sha256
  `a5652461c33474609d835214058a2f33d67e030fe37309e296f2c07846e68ff6`; JaCoCo session id `dell26-d8773412` (the only session
  in the report).
- Evidence outputs are fresh and inputs stayed unchanged: `clean` removed `build/` first, the report has a single new
  session id, and the snapshot hash above did not change.
- Final run after latest test additions and production fixes: yes (revision `cb1fe06`, includes the TW-1 fix; no test or
  production change after it).

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
| **Aggregate (summed counters, 8 measured files; AuthSessionRepository has no bytecode, none missing)** | 1702/1740 (97.8%) | 134/138 (97.1%) | 113/122 (92.6%) | 270/273 (98.9%) |

The counters are identical to the previous pass: the fix only reordered two statements and added a comment.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `LoginService.kt:103` (2 branches: `recoveryCode == null` with `totp == null`) | not reachable through the API: the controller rejects requests without exactly one factor (tested, 400) | justified gap, defensive |
| `LoginService.kt:148` (`CockpitPrincipal` data-class generated members) | generated code | justified gap |
| `SecurityConfiguration.kt:93` (2 branches: CSRF token attribute missing/not a `CsrfToken`) | defensive; Spring always sets the attribute after `CsrfFilter` | justified gap |
| `SessionAuthenticationFilter.kt:55` (`getCredentials`) | trivial, returns null | justified gap |
| `AuthSession.kt:25,29,31` (unused Kotlin accessors/setters of JPA fields) | trivial | justified gap |
| `UserAccount.kt:24,26,28,57` (pre-existing accessors from p0-auth-setup) | not task behavior | justified gap |
| `AuthController.kt:148` (synthetic default-argument constructor of `LoginRequest`) | generated code | justified gap |

## Unable to test

- An error raised only at transaction commit, after `LoginService.login()` returns (and so after `recordSuccess()`): noted
  as out of scope in `fixes.md`; `saveAndFlush` surfaces constraint and connection errors before the reset, and forcing a
  commit-only failure would need a fault-injecting datasource. Low risk; no follow-up needed for this task.
- Whether the pre-login `XSRF-TOKEN` value is still accepted after login: the CSRF handling is stateless double-submit
  (recorded assumption), so an old value sent in both cookie and header still matches by design. Not a requirement.
- The per-request `XSRF-TOKEN` re-issue on authenticated responses (observation in `fixes.md`) is pre-existing behavior
  covered by the recorded assumption; not tested further.
- Real browser behavior of `SameSite=Lax`/`Secure` and browser-session cookie lifetime (needs a browser; Playwright e2e is
  not in this task's criteria). The attributes are asserted on the `Set-Cookie` headers.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. The TW-1 fix in `cb1fe06` makes the regression
test pass. The focused auth package (118 tests), `./gradlew clean check` (664 tests), `OpenApiContractTest` without the flag
and `.taskcheck` all pass on revision `cb1fe064bd6b6180b6bb4f27466da637991788c9`.
