# Test Report

Date: 2026-10-20

Task: and-api-client - Android API client and auth plumbing

Scope: base 75b80f438c06233efd23a12c3a6a3383e7b25a84 .. HEAD 0f649555f061dfbafdbd818a73ef00fc50482f56 (task commits 486eb58cb12041f74c3726791401e42029284e3e, 0f649555f061dfbafdbd818a73ef00fc50482f56, given by the parent worker), branch main, plus the uncommitted test files listed below (no uncommitted production changes).

Requirements:
- `tasks/and-api-client.md` acceptance criteria and recorded assumptions (refresh 400/401 = logout, network errors/5xx keep tokens, a 401 on a retried request is final, cleartext hosts localhost/127.0.0.1/::1/10.0.2.2)
- `SPEC.md` §3.3 (access + rotating refresh token), `api/openapi.yaml` `POST /api/v1/auth/token/refresh` (200 tokens, 400 missing token, 401 unknown/expired/rotated/revoked)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md` project notes

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/core/network/TokenAuthenticatorTest.kt` (extended)
- `android/app/src/test/java/ch/sonensei/cockpit/core/network/ServerUrlTest.kt` (extended)
- `android/app/src/test/java/ch/sonensei/cockpit/core/network/TokenAuthenticatorUnitTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/core/network/AuthApiTokenRefresherTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/core/network/NetworkModuleGraphTest.kt` (new)

## Tests added or updated

Count: 29 added (12 existing tests unchanged; 41 tests in `ch.sonensei.cockpit.core.network.*` now)

- TokenAuthenticatorTest (+4, MockWebServer): `a refreshed token that is rejected too is not refreshed again`, `an Authorization header set by the caller is not overwritten`, `a network error during refresh fails the request and keeps the tokens`, `a malformed refresh answer fails like a server error instead of crashing the call` (FAILS, TW-1)
- TokenAuthenticatorUnitTest (10, direct `authenticate` calls with a fake refresher): refresh with stored refresh token; no bearer / Basic auth -> final; already-retried request -> final without refresh; request sent with an older token -> retried with the stored one, no refresh (other-thread path); logged out meanwhile -> no refresh; Rejected -> logout + `REFRESH_REJECTED` emitted; Failed -> tokens kept, no logout emitted; a second 401 blocks on the lock during a running refresh and reuses its token (thread state checked, no sleeps); `AuthSession.logout(USER)`; `AuthTokens.toString()` redacts the tokens
- AuthApiTokenRefresherTest (8, MockWebServer): 200 -> Success, request is `POST /api/v1/auth/token/refresh` with `{"refreshToken":...}` and no Authorization header; 400/401 -> Rejected; 403/429/500/503 -> Failed; 204 without body -> Failed; unreachable server -> Failed; no configured server -> Failed; malformed 200 body -> Failed (FAILS, TW-1); 200 body missing required fields -> Failed (FAILS, TW-1)
- ServerUrlTest (+5): cleartext host matching is exact and case-insensitive (`LOCALHOST` ok; `localhost.example.org`, `10.0.2.3`, `127.0.0.2`, `my-localhost` -> HTTPS_REQUIRED); port/path kept, blank/`https://`/invalid port -> MALFORMED; the stored URL survives reopening the DataStore file, can be replaced, an invalid URL leaves the previous one; a stored URL that no longer validates counts as not configured; query and encoded path kept when rewriting to the server
- NetworkModuleGraphTest (2, Robolectric + Hilt `HiltTestApplication`): the production graph provides OkHttpClient (with `TokenAuthenticator`), Auth/Events/Settings/Setup APIs, one shared `TokenStore`; an injected `AuthApi` uses the DataStore server URL (with path prefix), the stored token, refreshes through the `@RefreshClient` (no Authorization header on the refresh call) and retries successfully

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: openapi-generator generates Kotlin Retrofit2 + kotlinx.serialization client from `../api/openapi.yaml` at build time, not committed | All network tests compile against and call the generated `AuthApi`/`EventsApi`/`SettingsApi`/`SetupApi`; `git ls-files android \| grep generated/openapi` -> 0 | `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.core.network.*'` (openApiGenerate runs before compile) | Pass (build-time generation observed; not committed) |
| AC2: interceptor adds the access token | `adds the access token to requests`, `sends no Authorization header when logged out`, `an Authorization header set by the caller is not overwritten`, `an injected API uses the stored server URL, token and refresh client` | same | Pass |
| AC2: refresh on 401 once | `refreshes once on 401 and retries with the new token`, `a refreshed token that is rejected too is not refreshed again`, `a 401 for an already retried request is final and does not refresh again`, `a 401 for a request without a bearer token is final` | same | Pass |
| AC2: concurrent requests share one refresh (MockWebServer) | `concurrent 401s share one refresh`, `a 401 arriving while another refresh runs waits for it and reuses its token`, `a request sent with an older token is retried with the stored one without refreshing` | same | Pass |
| AC2: refresh failure logs out (rejected refresh) | `a rejected refresh logs out`, `a rejected refresh logs out with REFRESH_REJECTED`, `400 and 401 mean the refresh token was rejected` | same | Pass |
| Assumption: network errors / 5xx keep tokens and just fail the request | `a server error during refresh keeps the tokens`, `a network error during refresh fails the request and keeps the tokens`, `a failed refresh keeps the tokens and does not log out`, `other errors keep the session as a failed refresh`, `a success without a body is a failed refresh`, `an unreachable server is a failed refresh`, `no configured server is a failed refresh` | same | Pass |
| Same, for a malformed refresh answer (server failure, must not crash) | `a malformed success body is a failed refresh, not a crash`, `a success body missing required fields is a failed refresh, not a crash`, `a malformed refresh answer fails like a server error instead of crashing the call` | same | Fail (TW-1) |
| AC3: server base URL configurable (DataStore) | `stores a valid URL in DataStore and ignores invalid ones`, `the stored URL survives reopening the DataStore and can be replaced`, `a stored URL that no longer validates counts as not configured`, `requests go to the configured server below its path`, `keeps the query and encoded path when pointing a request at the server`, `fails requests while no server is configured`, `an injected API uses the stored server URL, token and refresh client` | same | Pass |
| AC3: validated as https, http only for localhost/emulator | `accepts https and normalizes the trailing slash`, `allows http only for localhost and the emulator host`, `cleartext host matching is exact and case-insensitive`, `keeps port and path and rejects blank input`, `rejects malformed URLs` | same | Pass |
| AC3 (platform side): `network_security_config.xml` permits cleartext only for the same hosts | none | - | Not run: Android's NetworkSecurityPolicy is not enforced for OkHttp sockets under Robolectric; needs an instrumented/device test |
| AC4: `testDebugUnitTest --tests 'ch.sonensei.cockpit.core.network.*'` passes | all 41 | same | Fail: 38 passed, 3 failed (TW-1) |
| Hilt NetworkModule graph | `the graph provides the APIs and shares one token store`, `an injected API uses the stored server URL, token and refresh client` | same | Pass |

## Focused test execution

Command:

`cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.core.network.*'`

Result:

Fail - ktlint clean; 41 tests, 38 passed, 3 failed (all three are TW-1).

## Full test execution

Command:

`cd android && ./gradlew --continue ktlintCheck testDebugUnitTest lintDebug`

Result:

Fail - ktlintCheck pass, lintDebug pass, testDebugUnitTest 87 tests, 84 passed, 3 failed (the same TW-1 tests; no other test affected).

## .taskcheck

Result:

Fail - `sh scripts/check.sh`: TFP gate tests, server `./gradlew check` and web `npm run check` (275 tests) passed; android step failed only on the 3 TW-1 tests.

## Bugs found

### A malformed refresh response throws out of the OkHttp authenticator and crashes the dispatcher thread

Finding ID:

`TW-1`

Test:

`AuthApiTokenRefresherTest.a malformed success body is a failed refresh, not a crash`, `AuthApiTokenRefresherTest.a success body missing required fields is a failed refresh, not a crash`, `TokenAuthenticatorTest.a malformed refresh answer fails like a server error instead of crashing the call`

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/core/network/TokenRefresher.kt:34-38` (`AuthApiTokenRefresher.refresh` only catches `IOException`)

Requirement:

AC2 "a refresh failure logs out" with the recorded assumption "network errors and 5xx keep the tokens and just fail the request"; `RefreshResult.Failed` is documented as "the server could not be reached or failed". A 200 with an unparseable or incomplete body (captive portal, proxy, server bug) is a server failure.

Expected behavior:

`refresh` returns `RefreshResult.Failed`; the authenticator returns null, the original call completes with the 401, and the tokens are kept.

Actual behavior:

The kotlinx.serialization converter throws `JsonDecodingException` / `MissingFieldException` (not `IOException`), which propagates out of `refresh` and `TokenAuthenticator.authenticate`. For suspend Retrofit calls (enqueue), OkHttp fails the call with `IOException: canceled due to ...JsonDecodingException` and then rethrows the original exception on the "OkHttp Dispatcher" thread (`Exception in thread "OkHttp Dispatcher" kotlinx.serialization.json.internal.JsonDecodingException` in the test's stderr). On Android an uncaught exception on any thread kills the app process.

Severity:

High (whole-app crash triggered by a network answer outside the app's control).

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

### Observation (not a defect against a stated requirement)

`TokenAuthenticator` replaces a caller-supplied `Authorization: Bearer <other>` header with the stored access token on a 401 (sent token differs from the stored one, so it takes the "already refreshed by another request" path). No current caller sets its own bearer token, and the task does not define this case; no test was added. Recorded for the parent to decide.

## Coverage

Status: partial-failed (diagnostic). The report is fresh and covers the final HEAD + current tests, but 3 tests fail (TW-1), so it is not a passing validation result. The fixer's change to `TokenRefresher.kt` makes it stale; rerun after the fix.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: all 7 Kotlin production files are new in this task (`git diff --numstat` shows only added lines), so changed-line coverage equals the whole-file LINE counters below (124/125). Changed-branch coverage not separately measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-api-client; base 75b80f438c06233efd23a12c3a6a3383e7b25a84; HEAD 0f649555f061dfbafdbd818a73ef00fc50482f56; commits 486eb58, 0f64955 and production file list supplied by the parent worker, checked against `git diff --numstat 75b80f4..HEAD -- android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 0f64955 (no uncommitted production changes) plus test sha256: AuthApiTokenRefresherTest 4705e48b7a84..., NetworkModuleGraphTest 55fbf7ad880a..., ServerUrlTest ab55360d03d4..., TokenAuthenticatorTest 4c6d97fcb1e4..., TokenAuthenticatorUnitTest e7df171f8eb9...; production and test hashes taken before the run and verified unchanged after it (`sha256sum -c`).
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/core/network/{AccessTokenInterceptor,AuthSession,NetworkModule,ServerUrl,TokenAuthenticator,TokenRefresher,TokenStore}.kt`. Excluded: `android/app/build.gradle.kts`, `android/app/openapi-generator-ignore`, `android/gradle/libs.versions.toml` (build configuration, not instrumented); `AndroidManifest.xml`, `res/xml/network_security_config.xml` (resources, no bytecode); Hilt/KSP generated `*_Factory.java` (generated); generated OpenAPI client under `build/generated/openapi` (generated); `api/README.md`, `docs/ARCHITECTURE.md` (docs). No renamed or deleted files; no scoped file missing from the report.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (AGP-bundled version); `./gradlew -I /home/eric/work/my-cockpit-agents/agent-2/.tmp/ignore-test-failures.init.gradle testDebugUnitTest createDebugUnitTestCoverageReport --rerun` in `android/`; full debug unit test suite (87 tests). The init script (outside the repo config, Git-ignored `.tmp/`) only sets `ignoreFailures = true` on Test tasks so the report is produced despite the 3 TW-1 failures; no project configuration was changed.
- Run start/completion time and exit status: started 2026-10-20T13:15:48Z, report written 2026-10-20T13:16:37Z; Gradle exit 0 (BUILD SUCCESSFUL due to ignoreFailures) with 87 tests, 3 failed.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` sha256 c5718e89980630b59f232f980bd8f09644cccb6f05ec3ed30d6b535c9509d09a, JaCoCo session `dell26-f41c8851`.
- Evidence outputs are fresh and inputs stayed unchanged: yes (tests executed in this run, report mtime inside the run window, snapshot hashes verified after the run).
- Final run after latest test additions and production fixes: yes for the latest tests; no production fix exists yet (TW-1 open), so a new run is needed after the fix.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. Statements = JaCoCo INSTRUCTION.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/core/network/AccessTokenInterceptor.kt` | 70/70 (100%) | 12/12 (100%) | 4/4 (100%) | 9/9 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/core/network/AuthSession.kt` | 58/58 (100%) | 2/2 (100%) | 5/5 (100%) | 11/11 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/core/network/NetworkModule.kt` | 144/147 (98.0%) | 4/4 (100%) | 12/13 (92.3%) | 21/22 (95.5%) |
| `android/app/src/main/java/ch/sonensei/cockpit/core/network/ServerUrl.kt` | 279/285 (97.9%) | 26/28 (92.9%) | 17/19 (89.5%) | 38/38 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/core/network/TokenAuthenticator.kt` | 104/104 (100%) | 12/12 (100%) | 2/2 (100%) | 19/19 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/core/network/TokenRefresher.kt` | 87/87 (100%) | 8/8 (100%) | 5/5 (100%) | 15/15 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/core/network/TokenStore.kt` | 31/31 (100%) | 0/0 (N/A) | 8/8 (100%) | 11/11 (100%) |
| Aggregate (summed counters, 7 files; excluded files listed above) | 773/782 (98.8%) | 64/66 (97.0%) | 53/56 (94.6%) | 124/125 (99.2%) |

Note: TokenRefresher.kt shows 100% although TW-1 is a real defect there: the missing behavior is an absent catch branch, which coverage cannot reveal.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `NetworkModule.kt:64` (3 instructions, 1 method) | Constructor of the abstract `NetworkBindings` Hilt module; never instantiated (`@Binds` only) | Non-material; not reachable |
| `ServerUrl.kt:27` (3 instructions) | Generated data-class member of `ServerUrlValidation.Invalid` | Non-material generated code |
| `ServerUrl.kt:41` (2 instructions) | `ServerUrlValidator` object accessor/initializer path not separately hit | Non-material |
| `ServerUrl.kt:48` (2 of 8 branches) | Short-circuit combinations of the user/password/query/fragment check (e.g. password without user) | Non-material; each rejected component is tested in `rejects malformed URLs` |

## Unable to test

- `network_security_config.xml` enforcement (cleartext blocked for other hosts by the platform): Robolectric does not apply NetworkSecurityPolicy to OkHttp sockets. Needs an instrumented test on a device/emulator; follow-up optional (validator side is covered).
- Build-time generation from a clean checkout was observed only through the normal incremental build (openApiGenerate runs before compile); a CI-style clean build was not run separately.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion (TW-1, High: a malformed refresh response crashes the app instead of failing as `RefreshResult.Failed`). All other acceptance-criteria behavior passed; coverage must be re-measured after the fix.
