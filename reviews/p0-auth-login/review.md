# Code Review

Date: 2026-10-20

Task: p0-auth-login - Login with password + TOTP, sessions, CSRF, lockout

Scope: `git diff 23edd3c563f77c5be6c77cea88decf2a6b0bc1a8..HEAD`, excluding `tasks/`. This is the third and final review pass, on the final code revision `acecccd9342b8447e4cac31d8f777e57003dcd41`. HEAD `0658a2f` only changes `reviews/p0-auth-login/tests.md` compared with acecccd (`git diff --stat acecccd..HEAD`). The code delta since the second pass (6222476..acecccd) is one line in `LoginService.kt` plus a regression test in `LoginEdgeCaseTests.kt`. The rest of the diff was reviewed in full in the earlier passes and re-checked here for regressions. Committed changes only; the working tree was clean before and after the test runs.

Requirements:
- `tasks/p0-auth-login.md`: acceptance criteria 1-7 and the recorded assumptions
- SPEC.md §4.1 (authentication and security) and §4.2 (conventions)
- docs/ARCHITECTURE.md, AGENTS.md project notes (log hygiene, Problem Details, OpenAPI regeneration, migrations), docs/QUALITY.md (report contract)
- docs/adr/0001-web-sessions-in-database.md
- Earlier review, fix and test reports in `reviews/p0-auth-login/` (fixes.md third pass, tests.md final report)

Git range:
- Branch: main
- HEAD: 0658a2f (code revision acecccd9342b8447e4cac31d8f777e57003dcd41)
- Base/start: 23edd3c563f77c5be6c77cea88decf2a6b0bc1a8
- Commits reviewed: 13 (f7c1b45, cc12f97, 3e8dca8, 1928589, a556101, 5d07198, a04d5ab, 8076afd, 6222476, a71d976, c240eb2, acecccd, 0658a2f)

Commands run (by this reviewer, on HEAD 0658a2f = code acecccd):
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` (Testcontainers Postgres) -> BUILD SUCCESSFUL. 119 tests, 0 failures, 0 errors, 0 skipped, summed from `build/test-results/test/TEST-ch.sonensei.cockpit.core.auth.*.xml`. This is 118 from the second pass plus the new regression test.
- `cd server && ./gradlew test --tests '*OpenApiContractTest'` (without `UPDATE_OPENAPI`) -> BUILD SUCCESSFUL. 1 test, 0 failures, 0 errors. `git status --short` was empty afterwards, so `api/openapi.yaml` matches the running server.

Files read for this pass: `LoginService.kt` (in full), `AuthSessionRepository.kt` (in full), the acecccd diff of `LoginEdgeCaseTests.kt`, and the third-pass section of `fixes.md`.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Status of the previous findings

- **First pass CR-1 (login backoff bypass through parallel requests): resolved** (confirmed in pass 2, unchanged since). `LoginThrottle.tryBeginAttempt()` admits and counts atomically, and `recordSuccess()` runs only after `sessions.saveAndFlush(...)` (`LoginService.kt:44`, `:63-67`).
- **First pass CR-2 (CSRF token not rotated on login): resolved** (confirmed in pass 2, unchanged since). Rotation on successful login and on logout through `SessionCookies.rotateCsrfToken()`.
- **Second pass CR-1 (P2, parallel requests with the same expired session cookie can return 500): resolved.** `LoginService.authenticate()` now removes an expired session with the bulk JPQL `sessions.deleteByTokenHash(session.tokenHash)` (`LoginService.kt:80-83`). The query (`AuthSessionRepository.kt:29-33`) is a `@Modifying` delete that does not check the affected row count or the `@Version`, so a row already deleted by a parallel request no longer raises `OptimisticLockingFailureException` inside the security filter. It is the same query `logout()` uses. No repository, schema or API change. Regression test `LoginEdgeCaseTests > parallel requests with the same expired session all end anonymous without an error` runs 5 rounds of 12 parallel `authenticate(token)` calls on one expired token, asserting no exception, all null and the row gone, and then 4 parallel `GET /api/v1/auth/me` with an expired cookie, each answering 401. The fix report says this test failed before the fix with `Unexpected row count ... delete from auth_session where id=? and version=?`, so it does reproduce the defect. It passes in this reviewer's run.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Acceptance criteria check (final code)

1. Login/logout/me with session cookie (`HttpOnly`, `Secure` unless `cockpit.auth.secure-cookie=false` in the dev profile, `SameSite=Lax`), 30-day sliding lifetime with remember device, browser-session cookie otherwise: met, covered by `LoginWebTests`, `SessionCookiesTests`, `LoginEdgeCaseTests`.
2. CSRF double-submit `XSRF-TOKEN` / `X-XSRF-TOKEN` enforced, missing token -> 403: met, `CsrfCookieWebTests`, `LoginWebTests`.
3. Recovery codes single-use, wrong password/TOTP -> identical 401: met, `LoginWebTests`, `LoginEdgeCaseTests`.
4. 5 failures -> exponential backoff, 429 with `Retry-After`, reset on success, with the test clock: met, `LoginThrottleTests`, `LoginServiceThrottleTests`, `LoginWebTests`.
5. `@WithCockpitUser` test support, unauthenticated `/api/**` -> 401, `/actuator/health` and SPA public: met, `SecurityConfigurationTests`, `SpaFallbackTests`. The expired-session path now also yields 401 under concurrency (second pass CR-1).
6. Focused auth tests pass with Testcontainers: met, 119/119 in this reviewer's run.
7. `api/openapi.yaml` regenerated and `OpenApiContractTest` passes without the flag: met, see "Commands run".

## Notes (not findings)

- No regressions found: the acecccd change is local to the expired branch of `authenticate()`. Valid-session handling (`touch()` bulk update, renewal) and `login()`/`logout()` are unchanged.
- Points recorded in the second pass that remain decisions for the humans, not findings: per-response `XSRF-TOKEN` re-issue on authenticated responses (12:51 assumption); an error raised only at transaction commit after `recordSuccess()` would still reset the backoff (very unlikely with `saveAndFlush`); unauthenticated 401 and CSRF 403 are plain status codes, not Problem Details, which predates this task (p0-auth-setup).
- `python tasks.py verify` evidence is recorded by the parent after the quality workflow (AGENTS.md step 7). This review re-ran criteria 6 and 7 itself.

## Review conclusion

No material issues found. All three earlier findings (first pass CR-1 and CR-2, second pass CR-1) are resolved, and every acceptance criterion is met on code revision acecccd.
