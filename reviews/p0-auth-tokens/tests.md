# Test Report

Date: 2026-10-20

Task: p0-auth-tokens - Device tokens for the Android app

Scope: `git diff 49c711e584bd255007ea9e9f4f99240efa4e2aad..72ec6142fa50f07531ac9f6b2011aea9647ab393 -- server api web/src/app/api/generated` (one task commit 72ec614 on branch `main`; base supplied by the parent as merge-base with origin/main). Uncommitted at measurement time: only the two new test files below (no production changes).

Requirements:
- `tasks/p0-auth-tokens.md` acceptance criteria and recorded assumptions
- SPEC.md §4.1 (Android: refresh token + 15 min access token; devices listed and revocable)
- docs/ARCHITECTURE.md "Coverage", docs/QUALITY.md, AGENTS.md project notes (no secrets in logs, fictitious test data)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/DeviceTokenEdgeCaseTests.kt` (new, Spring + Postgres via Testcontainers, same context as `DeviceTokenWebTests`)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/DeviceTokenUnitTests.kt` (new, plain JUnit + MockK)

The existing `DeviceTokenWebTests.kt` (9 tests) is unchanged.

## Tests added or updated

Count: 36 (26 in `DeviceTokenEdgeCaseTests`, 10 in `DeviceTokenUnitTests`)

## Requirement coverage

Command A: `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` (164 tests, 2 failed: the TW-1 tests).
Command B: `cd server && ./gradlew check jacocoTestReport --continue` (754 tests, 2 failed: the TW-1 tests).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 token endpoint returns 15-min access + opaque refresh token, hashed at rest | existing `token issues a 15-minute access token...`; new `the stored hashes are the SHA-256 of the issued and of the rotated tokens`, `a device's access token expires exactly 15 minutes and its refresh token exactly 90 days after issue`, `a token response carries the bearer type and both tokens` | A, B | Pass |
| AC1 token endpoint takes login credentials (incl. recovery code) + deviceName, shared throttle | `a recovery code logs a device in once and is consumed`, `both or neither second factor is a 400 and consumes no recovery code`, `a recovery code is not consumed by a token request with a wrong password`, `a successful token request resets the shared throttle`, `malformed or oversized token requests are 400 problems`, `the device name is trimmed and a name of exactly 100 characters is accepted` | A, B | Pass |
| AC1 refresh rotates; reuse of an old refresh token revokes the device | existing rotation/reuse test; new `concurrent refreshes with the same token give at most one new pair and are treated as a reuse`, `rotating a device replaces both hashes and restarts both lifetimes`, `a refresh just before the 90 days succeeds and restarts the lifetime`, `an expired refresh is answered 401 and deletes the device` | A, B | Pass |
| AC2 bearer access on /api/** alongside the session cookie; CSRF not required for bearer | `the bearer scheme is case-insensitive and surrounding blanks are ignored`, `an empty or expired bearer token is not rescued by a valid session cookie`, `another authorization scheme leaves the session cookie in charge`, `the bearer principal is the account owner`, `the token endpoints need no CSRF token even next to a session cookie`, unit tests on header parsing and `BearerAuthenticationFilter` (valid / unknown / empty token / existing authentication) | A, B | Pass |
| AC3 list and revoke devices; revoked tokens -> 401 | `device management needs a login`, `revoking with a session cookie needs the CSRF token`, `a device can revoke itself, and an id that is not a UUID is a 400`, `the device list shows when a device was created and last refreshed`, `an expired device not yet cleaned up is not listed`, `deleting the account deletes its devices` | A, B | Pass |
| AC3 revoked (and expired) tokens -> 401 also on state-changing requests | `a revoked access token answers 401 on state-changing requests too`, `an expired access token answers 401 on state-changing requests, so the app knows to refresh` | A, B | **Fail (TW-1)** |
| Assumption: expired devices deleted lazily; retired tokens cleaned up | `an expired device is deleted by the next token request and its retired tokens go with it`, `retired tokens older than the refresh lifetime are cleaned up, newer ones are kept` | A, B | Pass |
| Project rule: never log secrets; `toString` hides secrets | `issuing, refreshing, reusing and revoking never log a password, token or token hash`, `token request, refresh request, token response and device tokens hide their secrets` | A, B | Pass |
| AC4 `./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` passes | whole package | A | **Fail** (only the 2 TW-1 tests) |
| AC5 OpenAPI regenerated and `OpenApiContractTest` passes | `OpenApiContractTest` (existing, not modified) | B | Pass (ran inside the full suite) |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.DeviceToken*'` then `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.auth.*' --continue`

Result:

Fail: 164 tests in `core.auth`, all pass except the two TW-1 tests (expected 401, got 403). ktlintCheck: Pass. (An intermediate run also failed my own log test because of a wrong expected message and MockMvc's failure printout; both were test errors and are fixed.)

## Full test execution

Command:

`cd server && ./gradlew check jacocoTestReport --continue`

Result:

Fail: 754 tests completed, 2 failed (the TW-1 tests only); all pre-existing tests pass. `cd server && ./gradlew ktlintCheck`: Pass.

## .taskcheck

Result:

Not run: its server step (`./gradlew check`) is already known to fail on the two TW-1 tests (command B); the parent must run it after the fix.

## Bugs found

### A revoked or expired bearer token gets 403 instead of 401 on state-changing requests

Finding ID:

`TW-1`

Test:

`DeviceTokenEdgeCaseTests.a revoked access token answers 401 on state-changing requests too`, `DeviceTokenEdgeCaseTests.an expired access token answers 401 on state-changing requests, so the app knows to refresh`

Production source:

`server/src/main/kotlin/ch/sonensei/cockpit/core/auth/infra/SecurityConfiguration.kt:71` (`.ignoringRequestMatchers(BearerAuthenticationFilter.isBearerAuthenticated)`), together with `BearerAuthenticationFilter.kt` (the attribute is set only for a valid token)

Requirement:

AC3 "revoked tokens -> 401"; AC2 "Bearer access works on all /api/** endpoints"; SPEC.md §4.1 (15-min access token that the app refreshes).

Expected behavior:

A request from the app (no cookies) with `Authorization: Bearer <revoked or expired token>` to a POST/PATCH/PUT/DELETE endpoint (e.g. `PATCH /api/v1/settings`, `DELETE /api/v1/auth/devices/{id}`) answers 401, as GET requests do, so the client knows to refresh or log in again.

Actual behavior:

403 Forbidden. CSRF exemption applies only when the bearer token is valid, so an invalid bearer request without the XSRF cookie/header fails the CSRF check before authorization. An Android client whose access token just expired gets 403 on every write and cannot tell it should refresh. A possible fix (parent's decision): exempt every request carrying a bearer header from CSRF; this is safe because `SessionAuthenticationFilter` never authenticates such a request by its cookie, and browsers cannot add an `Authorization` header cross-site without CORS. If that is chosen, the existing assertion in `DeviceTokenWebTests` "An invalid bearer token does not lift CSRF protection either" (invalid bearer + session cookie -> 403) would expect 401 instead; changing it is the parent's/fixer's call, not done here.

Severity:

Medium

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Coverage

Status: partial-failed. A fresh full run, but the test task failed (the 2 TW-1 tests), so `jacocoTestReport` was generated with `-x test` from that run's fresh `test.exec`. All 754 tests executed, so the counters are complete, but they are diagnostic until a passing rerun after the fix.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: p0-auth-tokens; base 49c711e584bd255007ea9e9f4f99240efa4e2aad; HEAD 72ec6142fa50f07531ac9f6b2011aea9647ab393; single task commit 72ec614 and scope given by the parent, cross-checked with `git diff --stat 49c711e..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 72ec614 with no production changes; untracked `DeviceTokenEdgeCaseTests.kt` sha256 5a3f61d6517f3e2cc84ca1b3369b67dd9958b86cefb487808c4b1e6cedc6e730, `DeviceTokenUnitTests.kt` sha256 9eb6a7b548a058409881ba70da3bad9ef21fabc060002f85511018ad1e3a84c5 (hashes taken before the run and the same after it).
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/` `api/DeviceTokenController.kt`, `api/AuthController.kt`, `domain/AuthDevice.kt`, `domain/DeviceTokenService.kt`, `domain/LoginService.kt`, `infra/AuthDeviceRepository.kt`, `infra/BearerAuthenticationFilter.kt`, `infra/SecurityConfiguration.kt`, `infra/SessionAuthenticationFilter.kt`. Excluded: `V202610201200__core_auth_device.sql` (SQL migration, not instrumentable; exercised by every Postgres test through Flyway), `api/openapi.yaml` and `web/src/app/api/generated/**` (generated). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin (default tool version of Gradle 9.8.1, OpenJDK 21.0.12.1); `./gradlew check jacocoTestReport --continue` then `./gradlew jacocoTestReport -x test`; working dir `server/`; full test suite (754 tests).
- Run start/completion time and exit status: started 2026-10-20T14:19 +02:00, `test.exec` written 14:20:32, report written 14:20:45; the check run exited with a failure (2 failed tests), the report generation succeeded.
- Generated report paths and report hashes/run identifier: `server/build/jacoco/test.exec` sha256 1197ddfb3ab298fbf832972fd1ca669a51fc20322d3a3b677afc03b00887a67a; `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 c94bf31591e87eca013361c50c9cba6c9c2f483cef6a3d5333c3a5c9575686b2 (the earlier report from 13:56 was replaced).
- Evidence outputs are fresh and inputs stayed unchanged: yes (timestamps and hashes above).
- Final run after latest test additions and production fixes: yes for the test additions; no production fix exists yet (TW-1 open), so a rerun is needed after the fix.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. Statements = JaCoCo INSTRUCTION.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `core/auth/api/DeviceTokenController.kt` | 423/425 (99.5%) | 30/30 (100%) | 36/36 (100%) | 73/73 (100%) |
| `core/auth/api/AuthController.kt` | 323/325 (99.4%) | 32/32 (100%) | 16/16 (100%) | 45/45 (100%) |
| `core/auth/domain/AuthDevice.kt` | 167/173 (96.5%) | 4/4 (100%) | 22/24 (91.7%) | 32/34 (94.1%) |
| `core/auth/domain/DeviceTokenService.kt` | 396/399 (99.2%) | 17/18 (94.4%) | 26/26 (100%) | 67/67 (100%) |
| `core/auth/domain/LoginService.kt` | 650/662 (98.2%) | 50/52 (96.2%) | 41/44 (93.2%) | 88/88 (100%) |
| `core/auth/infra/AuthDeviceRepository.kt` | N/A (0/0, interface) | N/A | N/A | N/A |
| `core/auth/infra/BearerAuthenticationFilter.kt` | 146/146 (100%) | 24/24 (100%) | 7/7 (100%) | 21/21 (100%) |
| `core/auth/infra/SecurityConfiguration.kt` | 254/256 (99.2%) | 2/4 (50.0%) | 10/10 (100%) | 36/36 (100%) |
| `core/auth/infra/SessionAuthenticationFilter.kt` | 99/101 (98.0%) | 12/12 (100%) | 5/6 (83.3%) | 23/24 (95.8%) |
| **Aggregate (summed counters, 8 measured files; repository interface 0/0; SQL migration and generated files excluded)** | 2458/2487 (98.8%) | 171/176 (97.2%) | 163/169 (96.4%) | 385/388 (99.2%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `domain/AuthDevice.kt:81,83` | Getters of `AuthDeviceRetiredToken.device` / `tokenHash` (never read in Kotlin; JPQL reads the columns) | Non-material; retired-token behavior tested through reuse and cleanup tests |
| `domain/DeviceTokenService.kt:110` (`createdAt ?: lastSeenAt`) | Defensive fallback; `createdAt` is always set by auditing once persisted | Non-material; `createdAt` itself asserted in the device list test |
| `infra/SecurityConfiguration.kt:101` (2 branches) | `CsrfCookieFilter` with no CSRF token attribute (pre-existing code) | Non-material, outside task |
| `domain/LoginService.kt:122` (2 branches), `:168,175,176` | `?: return null` when neither second factor is given (the controllers reject this earlier, tested with 400); synthetic accessors of data classes | Non-material |
| `infra/SessionAuthenticationFilter.kt:57`, `api/AuthController.kt:148`, `api/DeviceTokenController.kt:187` | `getCredentials()` of the session authentication; Kotlin default-parameter synthetic code | Non-material |

## Unable to test

- Concurrent refreshes: the test serializes four threads on one refresh token through a barrier; it shows the row-lock outcome (one grant, then reuse revocation) but cannot force a specific interleaving. Passed in 3 runs.
- No browser/Android client test of the bearer flow: out of scope for this server task (MockMvc against the real Spring Security chain and Postgres was used).

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion.
