# Code Review

Date: 2026-10-20

Task: p0-auth-login - Login with password + TOTP, sessions, CSRF, lockout

Scope: `git diff 23edd3c563f77c5be6c77cea88decf2a6b0bc1a8..HEAD`, excluding `tasks/`. This is the second review pass, on the final code revision `6222476ab1fcb51d2ead8eac566addac5aa44984`. HEAD `a71d976` only changes `reviews/p0-auth-login/tests.md` compared with 6222476. Committed changes only. The working tree was clean before and after the test runs.

Requirements:
- `tasks/p0-auth-login.md`: acceptance criteria 1-7 and the 7 recorded assumptions (including the CR-1/CR-2 assumption from 12:51)
- SPEC.md §4.1 (authentication and security) and §4.2 (conventions)
- docs/ARCHITECTURE.md, AGENTS.md project notes (log hygiene, Problem Details, OpenAPI regeneration, migrations), docs/QUALITY.md (report contract)
- docs/adr/0001-web-sessions-in-database.md
- Previous review, fix and test reports in `reviews/p0-auth-login/`

Git range:
- Branch: main
- HEAD: a71d9765bcf6fd6b84a3dd2eba84e32516bb9f27 (code revision 6222476ab1fcb51d2ead8eac566addac5aa44984)
- Base/start: 23edd3c563f77c5be6c77cea88decf2a6b0bc1a8
- Commits reviewed: 10 (f7c1b45, cc12f97, 3e8dca8, 1928589, a556101, 5d07198, a04d5ab, 8076afd, 6222476, a71d976)

Commands run (by this reviewer, on HEAD a71d976 = code 6222476):
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` (Testcontainers Postgres) -> BUILD SUCCESSFUL. 118 tests, 0 failures, 0 errors, 0 skipped, summed from `build/test-results/test/TEST-ch.sonensei.cockpit.core.auth.*.xml`.
- `cd server && ./gradlew test --tests '*OpenApiContractTest'` (without `UPDATE_OPENAPI`) -> BUILD SUCCESSFUL. 1 test, 0 failures. `git status --short` was empty afterwards, so `api/openapi.yaml` matches the running server.

Files read in full: `AuthController.kt`, `LoginService.kt`, `LoginThrottle.kt`, `AuthSession.kt`, `UserAccount.kt`, `AuthSessionRepository.kt`, `SecurityConfiguration.kt`, `SessionAuthenticationFilter.kt`, `SessionCookies.kt`, `UserAccountRepository.kt`, the Flyway migration `V202610201100__core_auth_session.sql`, `application-dev.properties`, `WithCockpitUser.kt`, `LoginServiceThrottleTests.kt`, `CsrfCookieWebTests.kt`, and the relevant parts of `LoginEdgeCaseTests.kt`, `SecurityConfigurationTests.kt`, `SpaFallbackTests.kt`, `BaseEntity.kt` and `ProblemDetailsAdvice.kt`.

Summary: 0 P0, 0 P1, 1 P2, 0 P3

## Status of the previous findings

- **CR-1 (login backoff bypass through parallel requests): resolved.** `LoginThrottle.tryBeginAttempt()` (`LoginThrottle.kt:39-47`) checks the block and counts the attempt in one `@Synchronized` step. `LoginService.login()` calls it before any credential work (`LoginService.kt:45`). Only `recordSuccess()` takes the count back, and it now runs after `sessions.saveAndFlush(...)` (`LoginService.kt:61-67`, the TW-1 fix). So a login that throws, including the 409 race on a recovery code (the optimistic lock fails in the flush), stays counted as a failure. Regression tests: `parallel logins with a wrong password cannot slip past the backoff` (12 parallel `LoginService.login` calls give exactly 5 `InvalidCredentials` and 7 `Throttled`), `concurrent attempts are admitted atomically...`, and the 4 `LoginServiceThrottleTests` (including the TW-1 test `an attempt that throws while creating its session...`). All pass. The recorded assumptions still hold: attempts 1-4 are free, from the 5th a 30 s block doubles per further failure up to 1 h, blocked attempts do not count, and a success resets.
- **CR-2 (CSRF token not rotated on login): resolved.** `SessionCookies.rotateCsrfToken()` saves a newly generated token through the same `CookieCsrfTokenRepository` instance the filter chain uses (`SessionCookies.kt:44-60`, `SecurityConfiguration.kt:64`). `AuthController` calls it after a successful login (`AuthController.kt:91`) and on every logout (`AuthController.kt:115`). A failed login keeps the existing cookie. `CsrfCookieWebTests` covers all three cases, and the new cookie has the expected attributes (not HttpOnly, Secure, Path=/, SameSite=Lax).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Parallel requests with the same expired session cookie can return 500 instead of 401**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/domain/LoginService.kt:81` (called from `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/infra/SessionAuthenticationFilter.kt:31`)
    - Category: Correctness
    - Problem: when `authenticate()` finds an expired session, it removes it with `sessions.delete(session)`, which is an entity delete. `AuthSession` extends `BaseEntity`, which has a `@Version` field, so Hibernate issues `delete ... where id=? and version=?` and treats 0 affected rows as an optimistic-lock failure. Suppose several requests carry the same expired cookie. All of them load the row before the first one commits. Under Postgres READ COMMITTED, the later deletes wait for the first transaction to commit, then match 0 rows and throw `OptimisticLockingFailureException` when `authenticate()` commits. The exception is raised inside the security filter, not in a controller, so `ProblemDetailsAdvice` (which maps it to 409) never sees it. Those requests end in a server error instead of the anonymous 401 they should get. This situation is normal use, not an edge case. A browser-session cookie stays in the browser while the server ends the session after 12 h idle (recorded assumption 2). An SPA tab left open overnight that loads several endpoints in parallel on return sends the same expired cookie in all of them. Other code paths already avoid this. `logout()` and `login()` use bulk deletes (`deleteByTokenHash`, `deleteExpired`) that do not check the row count, and `touch()` is deliberately a bulk update for the same reason (see its KDoc). No test sends concurrent requests with an expired session.
    - Why it matters: at the moment the session expires, the client gets sporadic 500s and error log entries instead of a clean 401 that sends the user to the login screen. This breaks the "unauthenticated -> 401" contract of criterion 5 under concurrency.
    - Suggested fix: delete the expired session with a bulk query that tolerates a row that is already gone, for example `sessions.deleteByTokenHash(session.tokenHash)` or a `@Modifying` delete by id, as `logout()` already does. Add a test that sends several parallel `authenticate(token)` calls, or parallel `GET /api/v1/auth/me`, with one expired token, and asserts that all of them return null/401 without an exception.

## P3 Low

None.

## Notes (not findings)

- Verified as correct on the final code:
  - **Cookies:** the session cookie is `HttpOnly` and `SameSite=Lax`. It is `Secure` unless `cockpit.auth.secure-cookie=false`, which only the dev profile sets. With remember device it is persistent with a sliding 30-day `Max-Age` that is re-issued when the expiry slides; without it, it is a browser-session cookie plus a 12 h server idle limit. Tokens are 256 bits from `SecureRandom` and stored only as SHA-256 hashes.
  - **Sessions and CSRF:** no servlet session or `JSESSIONID` is created. CSRF uses the double-submit cookie via `.spa()`, and login/logout also need the header. Setup stays exempt.
  - **Failed logins:** the response is the same 401 Problem Detail for a wrong username, password, TOTP or recovery code. A dummy Argon2 hash is checked when no account exists, and all unused recovery codes are compared, so timing gives nothing away. A recovery code is used up only on full success. 429 responses carry `Retry-After` rounded up to whole seconds. Validation errors (400) are rejected before the throttle and do not count.
  - **Public and protected routes:** `/actuator/health` and SPA GET/HEAD are public. `/api/**`, other actuator paths and the API docs answer 401. `@WithCockpitUser` and `cockpitUser()` work in other modules' MockMvc tests.
  - **Log hygiene:** logs contain no username, password, code or token at INFO. `toString` overrides hide the secrets, and a captured-output test checks this.
  - **Migration:** the Flyway migration follows the `core/V<yyyyMMddHHmm>__core_...` naming.
- Not independently reproduced: the fix report says an authenticated response also re-issues `XSRF-TOKEN` (an expired cookie followed by a new one), apparently through Spring's default session-authentication strategy. The 12:51 assumption records per-response rotation as intended, and double-submit compares cookie and header of the same request. If a state-changing request is built from the old cookie value while a parallel response replaces the cookie, it could get a 403, but this is unlikely. That is a decision for the humans reviewing the assumption, not a finding here.
- Accepted residual risk, as in the fix and test reports: an error that only appears at transaction commit, after `recordSuccess()`, would still reset the backoff. `saveAndFlush` makes this very unlikely.
- Unauthenticated 401 and CSRF 403 responses are plain status codes, not Problem Details (`HttpStatusEntryPoint`). This predates the task (p0-auth-setup) and the API does not document a 403, so it is not raised here.
- `python tasks.py verify` evidence is recorded by the parent after the quality workflow (AGENTS.md step 7). This review re-ran criteria 6 and 7 itself (see "Commands run").

## Review conclusion

Material issues found; fix before completing the task. (One P2 resilience defect, CR-1. Both earlier findings are resolved, and no acceptance criterion is unmet.)
