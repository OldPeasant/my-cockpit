# Fix Report

Date: 2026-10-20

Task: p0-auth-login - Login with password + TOTP, sessions, CSRF, lockout

Scope: current task; findings CR-1 and CR-2 from `reviews/p0-auth-login/review.md` (reviewed revision d8a9416). Uncommitted working-tree changes on top of e07f465.

## Fixed findings

### CR-1 — Login backoff can be bypassed by parallel requests (check-then-act race)

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/domain/LoginThrottle.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/domain/LoginService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/LoginThrottleTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/LoginEdgeCaseTests.kt`

Problem:
`LoginService.login()` called `throttle.retryAfter()` before the Argon2 checks and `throttle.recordFailure()` only after them. Each call held the lock separately, so any number of parallel requests could pass the check before the first failure was counted. Confirmed: with that ordering the new regression test shows 12 of 12 parallel wrong-password logins reaching the credential check (`expected:<5> but was:<12>`).

Fix:
`recordFailure()` is replaced by `@Synchronized LoginThrottle.tryBeginAttempt(): Duration?`. Under one lock it either rejects the attempt with the remaining block time (the attempt is not counted, as before) or admits it and counts it as a failure right away. Reaching the 5th failure starts the block at once. `recordSuccess()` still resets the counter and lifts the block. `LoginService.login()` calls `tryBeginAttempt()` first and no longer records failures afterwards. The recorded assumptions still hold: failures 1-4 are free, from the 5th the block is 30 s doubled per further failure, at most 1 h, blocked attempts do not count, a success resets. One small change in timing: a block now starts when the attempt is admitted, not when its credential check fails, so it starts about one Argon2 verification earlier. An attempt that throws (for example the 409 when two requests use the same recovery code at once) now counts as a failure. This only makes the throttle stricter. `LoginThrottleTests` now drives the throttle through `tryBeginAttempt()` with the same expectations as before: thresholds, the exact end of a block, doubling, reset, and the 1 h cap. Repeated failures in the reset and cap tests wait out each block, because blocked attempts no longer count. New tests:
- a blocked attempt is rejected with the remaining time and does not count;
- the attempt that started the block can still succeed and reset it;
- 16 concurrent `tryBeginAttempt()` calls admit exactly `FREE_ATTEMPTS`;
- `LoginEdgeCaseTests`: 12 parallel `LoginService.login` calls with a wrong password give exactly 5 `InvalidCredentials` and 7 `Throttled`, then the endpoint answers 429.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` — Pass (114 tests, 0 failures, 0 errors, 0 skipped)
- New regression test against the old check-then-record ordering (temporary local edit, reverted) — Fail as expected (12 of 12 admitted)

### CR-2 — CSRF token is not rotated on login

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/infra/SessionCookies.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/api/AuthController.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/infra/SecurityConfiguration.kt` (KDoc only)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/CsrfCookieWebTests.kt`

Problem:
The custom login controller never goes through Spring's `CsrfAuthenticationStrategy`, so the `XSRF-TOKEN` from before login stayed in place after login. Confirmed: the login response set no new `XSRF-TOKEN`.

Fix:
`SessionCookies` now holds a single `CookieCsrfTokenRepository` instance. `csrfTokenRepository()` returns it, so the filter chain uses the same instance, with the same Secure, SameSite=Lax and Path=/ customizer. A new method, `rotateCsrfToken(request, response)`, saves a freshly generated token through that repository. `AuthController` calls it after a successful login and on every logout. The token is replaced, not just cleared, so the SPA has a valid cookie for its next state-changing request without first making a GET. A failed login keeps the existing cookie.

The existing test `with the cookie echoed in the header the SPA logs in and out` now logs out with the cookie issued by the login response, as Angular does. Reusing the old value would no longer match what a browser holds. New tests:
- login sets exactly one new, different `XSRF-TOKEN`, with HttpOnly off, Secure, Path=/ and SameSite=Lax;
- logout replaces it again, both with and without a valid session;
- a failed login sets no `XSRF-TOKEN`.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` — Pass (114 tests)

## Not fixed

None.

## Observation for the parent worker (not a supplied finding, not changed)

While testing CR-2, a response to an authenticated request (GET `/api/v1/auth/me` with a valid session) contained `XSRF-TOKEN=; Max-Age=0` followed by a new `XSRF-TOKEN`. This behaviour predates these fixes. It is consistent with Spring's `SessionManagementFilter` running its default `CsrfAuthenticationStrategy` on every authenticated request, because the stateless context repository never holds the authentication; I did not confirm the cause any further. The double-submit check compares the cookie and header of the same request, so requests still pass, but the token changes on every authenticated response. A logout with a valid session therefore carries this rotation plus the explicit one. Browsers keep the last `Set-Cookie`, and the tests check the last one. Whether to keep this per-request rotation is a separate decision for the parent. It was not changed here.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat` — no remaining changes needed
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.auth.*'` — Pass (114 tests, 0 failures, 0 errors, 0 skipped; Testcontainers Postgres)

Full test suite:
- `cd server && ./gradlew check` — Pass (660 server tests, 0 failures, 0 errors, 0 skipped; includes OpenApiContractTest. The new `HttpServletRequest` parameter on login does not change the contract.)

Lint/type-check/build:
- ktlint and Kotlin compile are part of `./gradlew check` — Pass

`.taskcheck`:
- `sh scripts/check.sh` — Pass ("all checks passed")

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
