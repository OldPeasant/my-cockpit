# Code Review

Date: 2026-10-20

Task: p0-auth-login - Login with password + TOTP, sessions, CSRF, lockout

Scope: `git diff beb6dc5f09d0e07a81f9cf9106e318733a331253..d8a9416fffd65f45d0a39b56ec348bac22c84806` (the 5 `p0-auth-login:` commits; base = merge-base with origin/main). Committed changes only; the working tree was clean.

Requirements:
- `tasks/p0-auth-login.md` acceptance criteria 1-7 and the 6 recorded assumptions (`python tasks.py assumptions`)
- SPEC.md §4.1 (authentication & security), §4.2 (ids, timestamps, Problem Details)
- docs/ARCHITECTURE.md conventions, AGENTS.md project notes, docs/QUALITY.md (report contract)
- docs/adr/0001-web-sessions-in-database.md (added by this task)

Git range:
- Branch: main
- HEAD: d8a9416fffd65f45d0a39b56ec348bac22c84806
- Base/start: beb6dc5f09d0e07a81f9cf9106e318733a331253
- Commits reviewed: 5 (f91fa8c, 43ceb13, c7bb6f9, 7c3b0c2, d8a9416)

Commands run:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` (Testcontainers Postgres) -> 108 tests, 0 failures, 0 errors, 0 skipped (summed from `build/test-results/test/TEST-ch.sonensei.cockpit.core.auth.*.xml`)

Files read in full: `AuthController.kt`, `LoginService.kt`, `LoginThrottle.kt`, `AuthSession.kt`, `UserAccount.kt`, `SecurityConfiguration.kt`, `SessionAuthenticationFilter.kt`, `SessionCookies.kt`, `AuthSessionRepository.kt`, the Flyway migration, `application-dev.properties`, `BaseEntity.kt`, `ProblemDetailsAdvice.kt`, `WithCockpitUser.kt`; tests inspected by name and in the relevant parts (concurrency, throttle, CSRF, cookies, SPA, log hygiene).

Summary: 0 P0, 1 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | Login backoff can be bypassed by parallel requests (check-then-act race)**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/domain/LoginService.kt:43` (with `:47-51`) and `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/domain/LoginThrottle.kt:26-38`
    - Category: Security
    - Problem: `login()` calls `throttle.retryAfter()` first, then runs the Argon2id password check (and, on the recovery-code path, one Argon2id check per unused code), and only afterwards calls `throttle.recordFailure()`. Each throttle method is `@Synchronized` on its own, but the check and the record are not one atomic step, and nothing limits in-flight attempts. Any number of requests arriving while no block is active (the 4 free attempts, or right after a block expires) all pass the check before any of them records its failure. With Tomcat's default 200 worker threads an attacker gets up to ~200 guesses per block window instead of 1, which defeats the "5 consecutive failures -> exponential backoff" protection (SPEC.md §4.1, criterion 4) for exactly the adversarial case it exists for. The existing test `concurrent failures are all counted` (`LoginThrottleTests.kt:89`) only calls `recordFailure()` in parallel, so it does not cover this; the web tests make sequential calls only.
    - Why it matters: the throttle is the only brute-force protection on the single owner account; it is effective against a sequential client but not against a parallel one.
    - Suggested fix: make admission atomic, e.g. a `LoginThrottle.tryBegin()` that, under the same lock, rejects while blocked and otherwise counts the attempt as in flight (pessimistically treat it as a failure and undo on success, or allow at most one in-flight credential check and answer 429 to the rest). Add a test that fires more than `FREE_ATTEMPTS` wrong logins concurrently through `LoginService.login` and asserts that at most `FREE_ATTEMPTS` of them reach the credential check (the rest get `Throttled`).

## P2 Medium

None.

## P3 Low

- [ ] **CR-2 | CSRF token is not rotated on login**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/api/AuthController.kt:86-90` (with `SecurityConfiguration.kt:60-66`)
    - Category: Security
    - Problem: Spring Security normally replaces the CSRF token when a user authenticates (`CsrfAuthenticationStrategy`), so a token planted before login can't be reused after it. This login is a custom controller and never goes through Spring's session authentication strategy, so the pre-login `XSRF-TOKEN` stays valid for the authenticated session. Exploiting this needs an attacker who can already set cookies for the origin (a sibling subdomain, or a man in the middle on plain HTTP). That is why the priority is low.
    - Why it matters: it is defence in depth that Spring gives by default and this login quietly drops. The token is also independent of the session, so it is never tied to the logged-in session.
    - Suggested fix: after a successful login (and after logout), clear the CSRF token through the configured `CsrfTokenRepository` (`saveToken(null, request, response)`), so the next request gets a fresh `XSRF-TOKEN`. Add a test that the cookie is replaced on login.

## Notes (not findings)

- Verified and found correct: cookie attributes (`HttpOnly`, `SameSite=Lax`, `Secure` unless `cockpit.auth.secure-cookie=false`, which only the dev profile sets); the remembered cookie's 30-day sliding Max-Age is re-issued when the expiry slides; non-remembered sessions use a browser-session cookie plus a 12 h idle limit on the server (assumption 2); tokens are stored only as SHA-256 hashes (256-bit `SecureRandom`); the stateless chain sets no `JSESSIONID`; CSRF double-submit via `.spa()` with a custom repository, and login/logout require the header (assumption 4); setup is still exempt; a uniform 401 `Invalid credentials` for a wrong username, password, TOTP or recovery code, a dummy Argon2 hash when no account exists, and the second factor checked even when the password is wrong; a recovery code is consumed only on full success. A concurrent reuse of the same recovery code is stopped by `@Version` optimistic locking (the second request gets a 409 and its transaction rolls back), and a test covers it; `Retry-After` rounds up to whole seconds; secrets are hidden by the `toString` overrides, and a test checks the log output. `/actuator/health` and SPA GET/HEAD are public, `/api/**`, `/actuator/**` and the API docs return 401, and `@WithCockpitUser`/`cockpitUser()` are in place.
- `python tasks.py verify p0-auth-login` shows all 7 criteria still unrecorded. The parent records them after the quality workflow (AGENTS.md step 7), so this is not reported as a finding. Criterion 6 was re-run here (see "Commands run"). Criterion 7 (OpenAPI contract) was not re-run in this review: `api/openapi.yaml` contains the three auth operations and the `LoginRequest`/`MeResponse` schemas, and the generated web client is part of the diff.
- Unauthenticated 401s and CSRF 403s are plain responses, not Problem Details. That comes from the earlier task p0-auth-setup and is outside this diff.

## Review conclusion

Material issues found; fix before completing the task.
