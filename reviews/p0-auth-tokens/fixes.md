# Fix Report

Date: 2026-10-20

Task: p0-auth-tokens (device tokens: issue, rotate with reuse detection, bearer auth, device list/revoke)

Scope: current task, base commit 49c711e584bd255007ea9e9f4f99240efa4e2aad, task commits `git log 49c711e..HEAD` (HEAD 6a213a8); finding TW-1 from `reviews/p0-auth-tokens/tests.md`, approved by the parent worker together with the chosen fix.

## Fixed findings

### TW-1 — Revoked or expired bearer token answers 403 (CSRF) instead of 401 on state-changing requests

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/infra/BearerAuthenticationFilter.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/infra/SecurityConfiguration.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/DeviceTokenWebTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/DeviceTokenUnitTests.kt`

Problem:
The CSRF filter skipped only requests that `BearerAuthenticationFilter` had authenticated with a valid token (a request attribute). A request with no cookies and a revoked or expired bearer token to PATCH /api/v1/settings or DELETE /api/v1/auth/devices/{id} was rejected by CSRF with 403 before authorization could answer 401, so the app could not tell it should refresh (task AC3: revoked tokens -> 401).

Fix:
CSRF now ignores every request carrying an `Authorization: Bearer` header, valid or not, through the new matcher `BearerAuthenticationFilter.withBearerHeader` (based on the existing `hasBearerHeader`). It replaces `isBearerAuthenticated`; the request attribute it relied on is no longer set and has been removed. This is safe because `SessionAuthenticationFilter` never authenticates a request with a bearer header by its cookie, and a browser cannot add an `Authorization` header to a cross-site request without a CORS preflight. KDoc in both classes describes the new rule. Test changes:
- `DeviceTokenWebTests` test `a session cookie still needs the CSRF token, and an invalid bearer token is not rescued by a cookie`: the last step (cookie plus invalid bearer token, no CSRF token) now expects 401 instead of 403, and its comment says why. The cookie-only CSRF 403 step in the same test is unchanged.
- `DeviceTokenUnitTests`: the assertions on the removed attribute matcher were dropped, and two test names that mentioned the "mark" were shortened. The header-parsing tests now also check that `withBearerHeader` matches exactly the headers `hasBearerHeader` accepts, including an empty `Bearer ` header, and nothing else.
- The two TW-1 tests in `DeviceTokenEdgeCaseTests` were left as they were and now pass.

Validation:
- `cd server && ./gradlew ktlintFormat` — Pass (no further changes)
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.auth.*'` — Pass (164 tests, 0 failures, 0 errors; includes `a revoked access token answers 401 on state-changing requests too` and `an expired access token answers 401 on state-changing requests, so the app knows to refresh`)
- `cd server && ./gradlew test --tests '*OpenApiContractTest'` (UPDATE_OPENAPI not set) — Pass (1 test, 0 failures); `api/openapi.yaml` is unchanged

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.auth.*'` — Pass (164/164)
- `cd server && ./gradlew test --tests '*OpenApiContractTest'` — Pass (1/1)

Full test suite:
- Not run by the fixer (the parent worker asked for the focused commands above; it runs the final validation)

Lint/type-check/build:
- `cd server && ./gradlew ktlintCheck` — Pass (part of the focused command); compiling main and test sources succeeded

`.taskcheck`:
- Not run (the parent worker runs it)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
