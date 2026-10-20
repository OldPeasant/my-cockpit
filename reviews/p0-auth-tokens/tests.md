# Test Report

Date: 2026-10-20

Task: p0-auth-tokens - Device tokens for the Android app (pass 2, final code)

Scope: `git diff 3f4cd737e512c0e2af1234a57ca5fc24528c93ad..0e5d6b3b169dd1a9aa17aa294b0a72217469e976` on branch `main` (base = merge-base with origin/main after the rebase, supplied by the parent). Task commits: 286d9e6 (implementation), 74c6239 (test-writer pass 1 tests), 1e51c46 (fix TW-1), 0e5d6b3 (review report only). Production code is unchanged since 1e51c46. Uncommitted at measurement time: one added test in `DeviceTokenEdgeCaseTests.kt`. There are no production changes.

Requirements:
- `tasks/p0-auth-tokens.md` acceptance criteria and recorded assumptions
- SPEC.md §4.1 (Android: refresh token + 15-min access token; devices listed and revocable)
- docs/ARCHITECTURE.md "Coverage", docs/QUALITY.md, AGENTS.md project notes (no secrets in logs, fictitious test data)
- `reviews/p0-auth-tokens/tests.md` pass 1 (finding TW-1) and `reviews/p0-auth-tokens/fixes.md` (its fix)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/DeviceTokenEdgeCaseTests.kt` (one test added in this pass, uncommitted)

`DeviceTokenUnitTests.kt` and `DeviceTokenWebTests.kt` are unchanged in this pass. The fixer adjusted them in 1e51c46 (see fixes.md), and I checked those adjustments as part of verifying the TW-1 fix.

## Tests added or updated

Count: 1 (`a header that skips CSRF never lets the session cookie write, and another scheme still needs the CSRF token`)

How the TW-1 fix is covered (CSRF is now skipped for every `Authorization: Bearer` header, valid or not):
- Original failure (an invalid token on a write answered 403 instead of 401): `a revoked access token answers 401 on state-changing requests too` and `an expired access token answers 401 on state-changing requests, so the app knows to refresh`. Both failed in pass 1 and both pass now.
- Invalid bearer token plus session cookie on a write gives 401: the `DeviceTokenWebTests` test `a session cookie still needs the CSRF token, and an invalid bearer token is not rescued by a cookie`.
- Matcher contract (`withBearerHeader` matches exactly what `hasBearerHeader` accepts, including the empty `Bearer ` header and nothing else): the `DeviceTokenUnitTests` header-parsing tests.
- New in this pass: the security corollary of the fix. A header that turns CSRF off (an empty `Bearer `) must never let the session cookie perform a write: 401, and the device is not revoked. A non-bearer scheme (`Basic`) next to the cookie keeps CSRF on: 403, and the device is not revoked. This guards against a future change that would exempt more headers from CSRF, or let the cookie authenticate next to a bearer header. That combination would be a CSRF bypass.

## Requirement coverage

Command F: `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.auth.*' --tests '*OpenApiContractTest'` (166 tests: 165 in `core.auth` + 1 contract test, 0 failures, 0 errors, 0 skipped).
Command C: `cd server && ./gradlew check jacocoTestReport` (783 tests, 0 failures, 0 errors, 0 skipped).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 token endpoint returns a 15-min access token and an opaque refresh token, hashed at rest | `token issues a 15-minute access token and a refresh token, stored only as hashes`, `the stored hashes are the SHA-256 of the issued and of the rotated tokens`, `a device's access token expires exactly 15 minutes and its refresh token exactly 90 days after issue`, `a token response carries the bearer type and both tokens` | F, C | Pass |
| AC1 credentials as for login + deviceName; shared throttle (assumption) | `token rejects wrong credentials like the login and shares its throttle`, `a recovery code logs a device in once and is consumed`, `both or neither second factor is a 400 and consumes no recovery code`, `a recovery code is not consumed by a token request with a wrong password`, `a successful token request resets the shared throttle`, `malformed or oversized token requests are 400 problems`, `the device name is trimmed and a name of exactly 100 characters is accepted` | F, C | Pass |
| AC1 refresh rotates; reusing an old refresh token revokes the device | `a refresh rotates the token, and reusing the old one revokes the device`, `the access token expires after 15 minutes and a refresh gives a new pair`, `concurrent refreshes with the same token give at most one new pair and are treated as a reuse`, `rotating a device replaces both hashes and restarts both lifetimes`, `a refresh just before the 90 days succeeds and restarts the lifetime`, `a refresh token not used for 90 days expires`, `an expired refresh is answered 401 and deletes the device` | F, C | Pass |
| AC2 bearer access on /api/** alongside the session cookie; no CSRF for bearer | `bearer access works on API endpoints without a CSRF token`, `the bearer scheme is case-insensitive and surrounding blanks are ignored`, `the bearer principal is the account owner`, `an empty or expired bearer token is not rescued by a valid session cookie`, `another authorization scheme leaves the session cookie in charge`, `the token endpoints need no CSRF token even next to a session cookie`, `the web app lists and revokes devices with its session`, unit tests on header parsing, `withBearerHeader` and `BearerAuthenticationFilter` | F, C | Pass |
| AC2 + TW-1: CSRF still protects cookie writes; a header that skips CSRF never lets the cookie write | `a session cookie still needs the CSRF token, and an invalid bearer token is not rescued by a cookie`, `revoking with a session cookie needs the CSRF token`, `a header that skips CSRF never lets the session cookie write, and another scheme still needs the CSRF token` (new) | F, C | Pass |
| AC3 list and revoke devices; revoked tokens -> 401 (writes included, TW-1) | `devices are listed and a revoked device's tokens answer 401`, `a revoked access token answers 401 on state-changing requests too`, `an expired access token answers 401 on state-changing requests, so the app knows to refresh`, `device management needs a login`, `a device can revoke itself, and an id that is not a UUID is a 400`, `the device list shows when a device was created and last refreshed`, `an expired device not yet cleaned up is not listed`, `deleting the account deletes its devices` | F, C | Pass |
| Assumption: expired devices are deleted lazily; retired tokens are cleaned up | `an expired device is deleted by the next token request and its retired tokens go with it`, `retired tokens older than the refresh lifetime are cleaned up, newer ones are kept` | F, C | Pass |
| Project rule: never log secrets | `issuing, refreshing, reusing and revoking never log a password, token or token hash`, `token request, refresh request, token response and device tokens hide their secrets` | F, C | Pass |
| AC4 `./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` passes (Postgres via Testcontainers) | whole `core.auth` package (165 tests; the DeviceToken web and edge-case tests run against Testcontainers Postgres) | F | Pass |
| AC5 `api/openapi.yaml` regenerated; `OpenApiContractTest` passes without the flag | `OpenApiContractTest` (UPDATE_OPENAPI not set); `git status` shows `api/openapi.yaml` unchanged after the run | F, C | Pass |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.auth.*' --tests '*OpenApiContractTest'`

Result:

Pass. BUILD SUCCESSFUL. 166 tests (DeviceTokenEdgeCaseTests 27, DeviceTokenUnitTests 10, DeviceTokenWebTests 9, OpenApiContractTest 1, the rest pre-existing `core.auth` tests), 0 failures. ktlintCheck passed for main and test sources.

## Full test execution

Command:

`cd server && ./gradlew check jacocoTestReport`

Result:

Pass. BUILD SUCCESSFUL. 783 tests, 0 failures, 0 errors, 0 skipped (counted from `server/build/test-results/test/*.xml`, all written by this run).

## .taskcheck

Result:

Pass. `sh scripts/check.sh` (server, web and android lint + unit tests) printed `== all checks passed` and took 41 s (14:40:18 to 14:40:59 +02:00).

## Bugs found

None in this pass. TW-1 from pass 1 (an invalid bearer token answered 403 instead of 401 on writes) is fixed in 1e51c46. Its two regression tests pass, and the new test above shows that the fix does not let a session cookie write without CSRF.

## Coverage

Status: verified (fresh, passing full-suite run on the final production code).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: 221/223 (99.1%) executable lines. Method: I took the added/modified line numbers from `git diff -U0 3f4cd73..HEAD -- server/src/main/kotlin` and intersected them with the per-line entries (`line nr=...`) in the JaCoCo XML. A line counts as covered if `ci > 0`. Removed and non-executable lines are not in the denominator. The two uncovered changed lines are `AuthDevice.kt:81,83`. Changed-branch coverage was not measured separately. For information only: the JaCoCo branch counters located on changed lines show 85/86.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: p0-auth-tokens; base 3f4cd737e512c0e2af1234a57ca5fc24528c93ad; HEAD 0e5d6b3b169dd1a9aa17aa294b0a72217469e976; task commits 286d9e6, 74c6239, 1e51c46, 0e5d6b3 as supplied by the parent. The scope matches `git diff --name-only 3f4cd73..HEAD -- server/src/main` and the task's `touches` (core/auth plus its migration).
- Source/test snapshot identifier (including uncommitted inputs): HEAD 0e5d6b3 with no production changes; `DeviceTokenEdgeCaseTests.kt` (modified, uncommitted) sha256 e5a0ea3480826f91378df9e8db05b4531883a3f9746946e92c96526d8aeea44e, `DeviceTokenUnitTests.kt` 123b0c5ad7defda91c3c203275232296ad0d5819b0f563010200d8c1a92f5b48, `DeviceTokenWebTests.kt` 5fa0e68f5ae6404f484c001b02641d845eaa18083a3a5cba2d24878fcdfa469c. I took the hashes at 14:37:17 before the runs and checked them again (`sha256sum -c`, all OK) after the coverage run.
- Exact included files (`server/src/main/kotlin/ch/sonensei/cockpit/core/auth/`): `api/AuthController.kt`, `api/DeviceTokenController.kt`, `domain/AuthDevice.kt`, `domain/DeviceTokenService.kt`, `domain/LoginService.kt`, `infra/AuthDeviceRepository.kt`, `infra/BearerAuthenticationFilter.kt`, `infra/SecurityConfiguration.kt`, `infra/SessionAuthenticationFilter.kt`. All 9 are present in the report. Excluded: `server/src/main/resources/db/migration/core/V202610201200__core_auth_device.sql` (SQL, not instrumentable by JaCoCo; Flyway applies it in every Postgres test), plus `api/openapi.yaml` and `web/src/app/api/generated/**` (generated). There are no renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin (default tool version of Gradle 9.8.1, OpenJDK 21); `./gradlew check jacocoTestReport`; working dir `server/`; full server test suite (783 tests). The test task reran because the previous invocation had used a filter.
- Run start/completion time and exit status: 14:38:46 to 14:39:50 +02:00, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/jacoco/test.exec` (14:39:49.79) sha256 0af0c760d89fb56fb382ed67299e1838038bc62e26c22deed0cf9a85a4df2eaf; `server/build/reports/jacoco/test/jacocoTestReport.xml` (14:39:50.71) sha256 4e3e0dc0b325188e6bdaa8fc62f304796641b6abdf89132cc4c9fe49a2595c41.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The exec, the XML and all 65 JUnit result files were written by this run, and the input hashes were unchanged afterwards.
- Final run after latest test additions and production fixes: yes. It covers the TW-1 fix (1e51c46) and the new test.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. Statements = JaCoCo INSTRUCTION, Functions = METHOD.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `core/auth/api/AuthController.kt` | 323/325 (99.4%) | 32/32 (100%) | 16/16 (100%) | 45/45 (100%) |
| `core/auth/api/DeviceTokenController.kt` | 423/425 (99.5%) | 30/30 (100%) | 36/36 (100%) | 73/73 (100%) |
| `core/auth/domain/AuthDevice.kt` | 167/173 (96.5%) | 4/4 (100%) | 22/24 (91.7%) | 32/34 (94.1%) |
| `core/auth/domain/DeviceTokenService.kt` | 396/399 (99.2%) | 17/18 (94.4%) | 26/26 (100%) | 67/67 (100%) |
| `core/auth/domain/LoginService.kt` | 650/662 (98.2%) | 50/52 (96.2%) | 41/44 (93.2%) | 88/88 (100%) |
| `core/auth/infra/AuthDeviceRepository.kt` | N/A (0/0, interface) | N/A | N/A | N/A |
| `core/auth/infra/BearerAuthenticationFilter.kt` | 134/134 (100%) | 24/24 (100%) | 7/7 (100%) | 19/19 (100%) |
| `core/auth/infra/SecurityConfiguration.kt` | 258/260 (99.2%) | 2/4 (50.0%) | 10/10 (100%) | 36/36 (100%) |
| `core/auth/infra/SessionAuthenticationFilter.kt` | 99/101 (98.0%) | 12/12 (100%) | 5/6 (83.3%) | 23/24 (95.8%) |
| **Aggregate (summed counters over the 9 files; the repository interface contributes 0/0; the SQL migration and generated files are excluded)** | 2450/2479 (98.8%) | 171/176 (97.2%) | 163/169 (96.4%) | 383/386 (99.2%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `domain/AuthDevice.kt:81,83` | Getters of `AuthDeviceRetiredToken.device` / `tokenHash`. Kotlin never reads them; JPQL reads the columns. | Non-material. Retired-token behavior is tested through the reuse and cleanup tests. |
| `domain/DeviceTokenService.kt:110` (1 branch, `createdAt ?: lastSeenAt`) | Defensive fallback. Auditing always sets `createdAt` once the row is persisted. | Non-material. `createdAt` itself is asserted in the device-list test. |
| `infra/SecurityConfiguration.kt:103` (2 branches) | `CsrfCookieFilter` with no CSRF token attribute (pre-existing code, unchanged by the task) | Non-material, outside the task |
| `domain/LoginService.kt:122` (2 branches), `:168,175,176` | `?: return null` when neither second factor is given (the controllers reject this earlier, and that is tested as a 400); synthetic data-class accessors | Non-material |
| `infra/SessionAuthenticationFilter.kt:57`, `api/AuthController.kt:148`, `api/DeviceTokenController.kt:187` | `getCredentials()` of the session authentication; Kotlin default-parameter synthetic code | Non-material |

`BearerAuthenticationFilter.kt`, the file the TW-1 fix changed, has 100% coverage on every metric.

## Unable to test

- Concurrent refreshes: the test runs four threads on one refresh token, starting them together with a barrier. It shows the row-lock outcome (one grant, then reuse revocation) but cannot force a specific interleaving. It passed in this pass's two runs (F, C).
- No browser or Android client test of the bearer flow. That is out of scope for this server task; the tests use MockMvc against the real Spring Security chain and Testcontainers Postgres. No follow-up is needed for this task.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. The TW-1 fix is covered by its regression tests, the adjusted web and unit tests, and one new test showing that skipping CSRF for bearer headers never lets a session cookie write. The focused command, the full server check with coverage, and `.taskcheck` all pass.
