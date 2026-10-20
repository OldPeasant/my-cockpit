# Test Report

Date: 2026-10-20

Task: and-api-client - Android API client and auth plumbing

Scope: `git diff 28f878ac126095c2119a7b3fe124acb3b578324d..dd33e2fc79a22ecf774839aaad7f95a596911477` on branch main. The base is `git merge-base HEAD origin/main`, as given by the parent worker. Task commits are 1709c62f..dd33e2fc (10 commits). HEAD is dd33e2fc79a22ecf774839aaad7f95a596911477. The working tree was clean before and after every run, so nothing uncommitted is included. This is round 3 and it covers the final production code: the round-2 fixes for CR-5/TW-3, CR-6 and CR-7 are in 08c90955, and no production code changed after that. This report replaces the stale round-2 report, which described an earlier revision and an older base.

Requirements:
- `tasks/and-api-client.md`: acceptance criteria 1-4 and the recorded assumptions. These say that a refresh answered 400/401 logs out; network errors and 5xx keep the tokens; a 401 on a request that was already retried is final; cleartext is allowed only for localhost, 127.0.0.1, ::1 and 10.0.2.2; tokens are kept in memory; tokens are bound to the server origin.
- `docs/ARCHITECTURE.md` "Coverage", the `AGENTS.md` project notes, and `reviews/and-api-client/fixes.md` (CR-5/TW-3, CR-6, CR-7) and `review.md` (round 3, no findings).

## Test files changed

- None in this run. This run checked the final code against the existing tests and added nothing, because no meaningful gap remained (see Coverage).
- The round-3 tests a previous test-writer run added are already committed in dd33e2fc, and this report covers them:
  - `android/app/src/test/java/ch/sonensei/cockpit/core/network/TokenAuthenticatorUnitTest.kt`: `a 401 waiting for a refresh that fails gives up, and a later 401 refreshes again`, `a 401 waiting for a failed refresh refreshes itself when the tokens changed meanwhile` (CR-5/TW-3).
  - `android/app/src/test/java/ch/sonensei/cockpit/core/network/ServerUrlTest.kt`: `settings the production store cannot read (not only a corrupt file) count as not configured` (new). `a server change during a running refresh does not block the calling thread` was made stricter (CR-6): it now proves the change waits for the lock on a thread other than the caller's.

## Tests added or updated

Count: 0 in this run. The 3 new tests and 1 stricter test from dd33e2fc are listed above. No test was removed, weakened or disabled.

## Requirement coverage

Focused command (F): `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.core.network.*' ktlintCheck --rerun-tasks`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: openapi-generator builds Kotlin (Retrofit2 + kotlinx.serialization) from `../api/openapi.yaml` at build time, not committed | All suites compile against `generated.apis.AuthApi/EventsApi/SettingsApi/SetupApi`; `NetworkModuleGraphTest.the graph provides the APIs and shares one token store`; `AuthApiTokenRefresherTest` (kotlinx decoding of `TokenResponse`) | F (the generator ran during the build). Also: `find android/app/build/generated/openapi/src/main/kotlin -name '*.kt' \| wc -l` -> 48; `git ls-files android \| grep -ci generated` -> 0; `git check-ignore -v android/app/build/generated/openapi/x` -> `android/.gitignore:2:build/` | Pass |
| AC2/AC4: the interceptor adds the access token (token injection) | `TokenAuthenticatorTest.adds the access token to requests`, `sends no Authorization header when logged out`, `an Authorization header set by the caller is not overwritten` (MockWebServer) | F | Pass |
| AC2: the Authenticator refreshes once on 401 | `TokenAuthenticatorTest.refreshes once on 401 and retries with the new token`, `a refreshed token that is rejected too is not refreshed again`; `TokenAuthenticatorUnitTest.refreshes with the stored refresh token...`, `a 401 for an already retried request is final...`, `a 401 for a request without a bearer token is final` | F | Pass |
| AC2/AC4: concurrent requests share one refresh (MockWebServer) | `TokenAuthenticatorTest.concurrent 401s share one refresh` (8 parallel requests, 1 refresh call), `concurrent 401s with a rejected refresh refresh once and log out once`, `concurrent 401s with a failing refresh refresh once, keep the tokens and a later request refreshes again`; `TokenAuthenticatorUnitTest.a 401 arriving while another refresh runs waits for it and reuses its token`, `a 401 waiting for a refresh that fails gives up, and a later 401 refreshes again`, `a 401 waiting for a failed refresh refreshes itself when the tokens changed meanwhile` | F | Pass |
| AC2/AC4: a refresh failure logs out | `TokenAuthenticatorTest.a rejected refresh logs out`; `TokenAuthenticatorUnitTest.a rejected refresh logs out with REFRESH_REJECTED`, plus race tests for a logout or login during the refresh (`a rejected stale refresh does not log out a login that happened meanwhile`, `a rejected refresh after a user logout meanwhile...`, `logoutIfCurrent only logs out the expected tokens`); `AuthApiTokenRefresherTest.400 and 401 mean the refresh token was rejected` | F | Pass |
| Assumption: 5xx, network errors and malformed answers keep the tokens and fail the request | `TokenAuthenticatorTest.a server error during refresh keeps the tokens`, `a network error during refresh...`, `a malformed refresh answer...`, `a refresh answer with an invalid date...`; `AuthApiTokenRefresherTest` (other errors, no body, unreachable, no server, malformed/missing fields/invalid id/invalid date/unknown token type) | F | Pass |
| AC3: the server base URL is configurable (DataStore) | `ServerUrlTest.stores a valid URL in DataStore and ignores invalid ones`, `the stored URL survives reopening the DataStore and can be replaced`, `requests go to the configured server below its path`, `keeps the query and encoded path...`, `fails requests while no server is configured`, the unreadable/corrupt store tests; `NetworkModuleGraphTest.an injected API uses the stored server URL, token and refresh client` | F | Pass |
| AC3: https is required; http only for localhost/emulator | `ServerUrlTest.allows http only for localhost and the emulator host`, `cleartext host matching is exact and case-insensitive`, `rejects malformed URLs`, `keeps port and path and rejects blank input`; `NetworkSecurityConfigTest` (2 tests) | F | Pass |
| Assumption plus CR-6/CR-7: tokens are bound to the server origin; a server change is main-safe; logout when the previous server is unknown | `ServerUrlTest.moving to another server logs out with SERVER_CHANGED`, `a server change waits for a running refresh, which still goes to the previous server`, `a request to the new server after a server change carries no token`, `setting a URL on the same origin keeps the tokens`, `a stored URL that no longer parses counts as another server and logs out`, `a server change while logged out stores the URL without a logout event`, `setting a URL while logged in without a readable previous URL logs out`, `a server change during a running refresh does not block the calling thread` | F | Pass |
| AC4: the named test command passes | All 69 tests in `ch.sonensei.cockpit.core.network.*` | F | Pass (69 tests, 0 failures, 0 errors, 0 skipped) |
| Cleartext really allowed or blocked on a device (the platform enforcing `network_security_config.xml`) | None at runtime; `NetworkSecurityConfigTest` only checks that the file and the validator agree | - | Not run: no emulator or device here, and Robolectric does not enforce the network security config |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.core.network.*' ktlintCheck --rerun-tasks`

Result:

Pass. Exit 0, 2026-10-20T14:50:44Z to 14:51:39Z. JUnit XML: ServerUrlTest 22, TokenAuthenticatorUnitTest 19, TokenAuthenticatorTest 13, AuthApiTokenRefresherTest 11, NetworkModuleGraphTest 2, NetworkSecurityConfigTest 2. Total 69, with 0 failures, 0 errors and 0 skipped. ktlintCheck passed.

## Full test execution

Command:

`cd android && ./gradlew createDebugUnitTestCoverageReport --rerun-tasks` (all Android debug unit tests, with coverage)

Result:

Pass. Exit 0, 2026-10-20T14:51:45Z to 14:53:11Z.

## .taskcheck

Result:

Pass on the second run. `sh scripts/check.sh` ran from 2026-10-20T14:57:31Z to 15:01:07Z (about 3.5 min) and printed `== all checks passed`.

The first run (14:53:37Z to 14:57:23Z) failed in the server part, in `ch.sonensei.cockpit.core.persistence.ClockConfigurationTests.the application clock is the system clock in UTC` (`2026-10-20T14:56:50.279456Z should be >= 2026-10-20T14:56:50.279456123Z`). This test is outside this task's scope: the task changes nothing under `server/`, and the test was last changed in a2de2988 (p0-db-conventions). It is flaky. The application clock truncates to microseconds, and the test compares the result with a nanosecond `Instant.now()` taken just before, so it fails whenever both reads fall within the same microsecond. Reported to the parent for a separate follow-up. It does not affect this task.

## Bugs found

None in this task's production code. No test fails.

- TW-3 from round 2 (concurrent 401s each refreshed again after a failed refresh) is fixed in 08c90955 (CR-5/TW-3). These tests now cover it and pass: `TokenAuthenticatorTest.concurrent 401s with a failing refresh refresh once, ...` (MockWebServer, 8 parallel requests, exactly 1 refresh call) and the two waiter unit tests.
- Out of scope, for the parent: the flaky server test `ClockConfigurationTests` described under .taskcheck. It is a test defect, not a production defect, and it is not owned by this task.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: equal to whole-file coverage. All 7 Kotlin files in scope were added by this task (`git diff --name-status 28f878ac..HEAD -- android/app/src/main` shows `A`), so every executable line is a changed line.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-api-client. Base 28f878ac126095c2119a7b3fe124acb3b578324d and HEAD dd33e2fc79a22ecf774839aaad7f95a596911477, as given by the parent. Task commits 1709c62f..dd33e2fc. File scope from `git diff --name-status 28f878ac..HEAD -- android/app/src/main`, which matches the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): commit dd33e2fc with a clean working tree (`git status --short` was empty before and after all runs). There are no uncommitted inputs.
- Exact included files: `AccessTokenInterceptor.kt`, `AuthSession.kt`, `NetworkModule.kt`, `ServerUrl.kt`, `TokenAuthenticator.kt`, `TokenRefresher.kt`, `TokenStore.kt` under `android/app/src/main/java/ch/sonensei/cockpit/core/network/`, all 7 present in the report. Excluded: `AndroidManifest.xml` (modified) and `res/xml/network_security_config.xml` (added), because they are not executable code (`NetworkSecurityConfigTest` checks them instead). Also excluded: generated `core/network/generated/**`, because it is build output. No files were renamed or deleted.
- Provider/version, command, working directory, and test selection: JaCoCo 0.8.15 via AGP `enableUnitTestCoverage`. Command `./gradlew createDebugUnitTestCoverageReport --rerun-tasks`, run in `android/`, on all debug unit tests.
- Run start/completion time and exit status: 2026-10-20T14:51:45Z to 14:53:11Z, exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 `d669e67ef1ec7cee85fec16f26dce3cabf0066645b1941d6d709d60199ae2f91`, JaCoCo session `dell26-2fa82c07`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. `--rerun-tasks` re-executed all tasks, the report timestamp matches the run, and HEAD and the clean tree were unchanged afterwards.
- Final run after latest test additions and production fixes: yes. This run measured the final code (fixes in 08c90955) together with the latest tests (dd33e2fc).

Values below use covered/total (percentage) from the JaCoCo XML counters. Statements = INSTRUCTION, Functions = METHOD.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `core/network/AccessTokenInterceptor.kt` | 70/70 (100%) | 12/12 (100%) | 4/4 (100%) | 9/9 (100%) |
| `core/network/AuthSession.kt` | 87/87 (100%) | 4/4 (100%) | 7/7 (100%) | 16/16 (100%) |
| `core/network/NetworkModule.kt` | 171/174 (98.3%) | 4/4 (100%) | 15/16 (93.8%) | 28/29 (96.6%) |
| `core/network/ServerUrl.kt` | 431/436 (98.9%) | 43/44 (97.7%) | 22/23 (95.7%) | 50/50 (100%) |
| `core/network/TokenAuthenticator.kt` | 155/155 (100%) | 22/22 (100%) | 3/3 (100%) | 24/24 (100%) |
| `core/network/TokenRefresher.kt` | 99/99 (100%) | 8/8 (100%) | 5/5 (100%) | 21/21 (100%) |
| `core/network/TokenStore.kt` | 43/43 (100%) | 2/2 (100%) | 9/9 (100%) | 14/14 (100%) |
| **Aggregate (summed counters; all 7 files measured, none missing)** | 1056/1064 (99.2%) | 95/96 (99.0%) | 65/67 (97.0%) | 162/163 (99.4%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ServerUrl.kt:103` (1 branch, 2 instructions: rethrow of an exception from DataStore that is not an IOException) | Defensive. Only IOException (an unreadable or corrupt file) is meant to be swallowed; the IOException branch is covered by the unreadable/corrupt store tests | Left uncovered: making DataStore throw a non-IO exception would mean mocking the behavior under test. Non-material |
| `ServerUrl.kt:31` (`ServerUrlValidation.Invalid.getReason`, the getter the compiler generates for the data class property) | None; the tests compare `Invalid(reason)` with `equals` | Generated member. Non-material |
| `NetworkModule.kt:81` (constructor of the abstract `NetworkBindings` Hilt module) | None; Hilt never instantiates it | Non-material |

All of `TokenAuthenticator.kt` is covered, including the CR-5 shared-failure branch (`failedRefreshes != failuresSeen && current == failedFrom`, both outcomes): 22/22 branches.

## Unable to test

- The platform enforcing the cleartext rules on a real device or emulator. There is no emulator here, and Robolectric does not apply `network_security_config.xml`. A static test checks that the file matches `CLEARTEXT_HOSTS`. Whether Android matches the IPv6 literal `::1` as written in a `domain` element was not verified. Follow-up is optional and can wait for an emulator/E2E task.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. All 4 acceptance criteria have passing tests. The named command passes (69 tests), and so do ktlintCheck and `.taskcheck` (on the second run; the first failed on a flaky server test outside this task). Coverage of the task's production files is 99.4% of lines (162/163) and 99.0% of branches (95/96). It includes the final fixes CR-5/TW-3, CR-6 and CR-7. No new tests were needed in this run.
