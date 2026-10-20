# Test Report

Date: 2026-10-20

Task: and-api-client - Android API client and auth plumbing

Scope: base c3da5a46ddc3bad27316de552b8c6c487dc62e7f .. HEAD d705eeb752fe7ef0e67583dc87daf3ed69f55e6b on branch main (task commits 898e80c, 60f375f, 9ac1205, 09bb66b, d705eeb, given by the parent worker; `git merge-base HEAD origin/main` = c3da5a4), plus the uncommitted test changes listed below. There are no uncommitted production changes. This run re-checks the final code after the fixes for TW-1 and CR-1..CR-4 (`reviews/and-api-client/fixes.md`). It replaces the earlier report, which described a pre-fix revision.

Requirements:
- `tasks/and-api-client.md` acceptance criteria and recorded assumptions: refresh 400/401 = logout; network errors and 5xx keep the tokens; a 401 on a retried request is final; cleartext only for localhost/127.0.0.1/::1/10.0.2.2; tokens kept in memory.
- `reviews/and-api-client/review.md` CR-1..CR-4 and `fixes.md`. These cover tokens bound to their server origin, compare-and-set token updates, and a corrupt DataStore counting as not configured.
- `SPEC.md` §3.3 (access + rotating refresh token). `api/openapi.yaml` `POST /api/v1/auth/token/refresh` and its `TokenResponse` (date-time and uuid fields).
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md` project notes.

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/core/network/AuthApiTokenRefresherTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/core/network/TokenAuthenticatorTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/core/network/TokenAuthenticatorUnitTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/core/network/ServerUrlTest.kt`

## Tests added or updated

Count: 10 added in this run. No existing test was changed. The only edit to existing code is in `AuthApiTokenRefresherTest`: the device id literal in the `tokenBody` helper is now a `DEVICE_ID` constant, with the same value. The package `ch.sonensei.cockpit.core.network.*` now has 60 tests.

- AuthApiTokenRefresherTest (+3, MockWebServer), for a 200 answer whose body decodes as JSON but has a bad field value:
  - `a success body with an invalid device id is a failed refresh, not a crash`: Pass. This is `IllegalArgumentException` from `UUID.fromString`.
  - `a success body with an invalid expiry date is a failed refresh, not a crash`: **FAILS, TW-2**.
  - `a success body with an unknown token type is a failed refresh, not a crash`: Pass.
- TokenAuthenticatorTest (+1, MockWebServer, end to end): `a refresh answer with an invalid date fails like a server error instead of crashing the call`. **FAILS, TW-2**.
- TokenAuthenticatorUnitTest (+3), for CR-3 compare-and-set:
  - `a rejected refresh after a user logout meanwhile gives up and does not log out a second time`. This is the `Rejected` + `logoutIfCurrent == false` + `retryWithStored == null` path.
  - `a failed refresh during a login meanwhile leaves the new login alone`.
  - `logoutIfCurrent only logs out the expected tokens`.
- ServerUrlTest (+3), for CR-2:
  - `a stored URL that no longer parses counts as another server and logs out`. This is the `toHttpUrlOrNull() == null` branch.
  - `a server change while logged out stores the URL without a logout event`. This is the `isLoggedIn == false` branch.
  - `a request to the new server after a server change carries no token`. This runs through the real `ServerUrlInterceptor(repository)`, `AccessTokenInterceptor` and `TokenAuthenticator`. The new server sees no `Authorization` header, and no refresh is attempted.

These were already in the suite and were re-run on the final code. All pass:
- TW-1: `a malformed success body ...`, `a success body missing required fields ...`, `a malformed refresh answer fails like a server error ...`.
- CR-2: `moving to another server logs out with SERVER_CHANGED`, `setting the first URL or one on the same origin keeps the tokens`, `a server change waits for a running refresh, which still goes to the previous server`.
- CR-3: `a user logout during a successful refresh stays logged out`, `a login during a successful refresh keeps the new login and retries with it`, `a rejected stale refresh does not log out a login that happened meanwhile`, `replace only swaps the tokens it expects`.
- CR-4: `an unreadable settings file counts as not configured`, `the production settings store replaces a corrupt file so the URL can be set again`.

## Requirement coverage

C1 = `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.core.network.*'`. openApiGenerate runs before compile. Result: 60 tests, 58 passed, 2 failed.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: openapi-generator generates a Kotlin Retrofit2 + kotlinx.serialization client from `../api/openapi.yaml` at build time, not committed | All network tests compile against the generated `AuthApi`/`EventsApi`/`SettingsApi`/`SetupApi`. The C1 log shows `openApiGenerate` "Cleaned up output directory ... Successfully generated code to .../build/generated/openapi". `git ls-files android` contains no generated sources. | C1 | Pass |
| AC2: the interceptor adds the access token | `adds the access token to requests`, `sends no Authorization header when logged out`, `an Authorization header set by the caller is not overwritten`, `an injected API uses the stored server URL, token and refresh client` | C1 | Pass |
| AC2: refresh on 401 once | `refreshes once on 401 and retries with the new token`, `a refreshed token that is rejected too is not refreshed again`, `a 401 for an already retried request is final and does not refresh again`, `a 401 for a request without a bearer token is final` | C1 | Pass |
| AC2: concurrent requests share one refresh (MockWebServer) | `concurrent 401s share one refresh`, `a 401 arriving while another refresh runs waits for it and reuses its token`, `a request sent with an older token is retried with the stored one without refreshing` | C1 | Pass |
| AC2: a refresh failure (rejected) logs out | `a rejected refresh logs out`, `a rejected refresh logs out with REFRESH_REJECTED`, `400 and 401 mean the refresh token was rejected`, `logoutIfCurrent only logs out the expected tokens` | C1 | Pass |
| Assumption: network errors and 5xx keep the tokens and just fail the request | `a server error during refresh keeps the tokens`, `a network error during refresh fails the request and keeps the tokens`, `a failed refresh keeps the tokens and does not log out`, `other errors keep the session as a failed refresh`, `a success without a body is a failed refresh`, `an unreachable server is a failed refresh`, `no configured server is a failed refresh` | C1 | Pass |
| TW-1/CR-1: a malformed refresh answer is a failed refresh, not a crash | `a malformed success body ...`, `a success body missing required fields ...`, `a malformed refresh answer fails like a server error ...`, `a success body with an invalid device id ...`, `a success body with an unknown token type ...` | C1 | Pass |
| Same requirement, for a 200 answer with an unparseable date-time field | `a success body with an invalid expiry date is a failed refresh, not a crash`, `a refresh answer with an invalid date fails like a server error instead of crashing the call` | C1 | **Fail (TW-2)** |
| CR-2: tokens are only sent to the server that issued them (an origin change logs out under `refreshLock`) | `moving to another server logs out with SERVER_CHANGED`, `setting the first URL or one on the same origin keeps the tokens`, `a server change waits for a running refresh, which still goes to the previous server`, `a stored URL that no longer parses counts as another server and logs out`, `a server change while logged out stores the URL without a logout event`, `a request to the new server after a server change carries no token` | C1 | Pass |
| CR-3: a refresh result does not overwrite a logout or login that happens during it | `a user logout during a successful refresh stays logged out`, `a login during a successful refresh keeps the new login and retries with it`, `a rejected stale refresh does not log out a login that happened meanwhile`, `a rejected refresh after a user logout meanwhile gives up and does not log out a second time`, `a failed refresh during a login meanwhile leaves the new login alone`, `replace only swaps the tokens it expects` | C1 | Pass |
| CR-4: a corrupt server-config DataStore counts as not configured and can be overwritten | `an unreadable settings file counts as not configured`, `the production settings store replaces a corrupt file so the URL can be set again` | C1 | Pass |
| AC3: the server base URL is configurable (DataStore) | `stores a valid URL in DataStore and ignores invalid ones`, `the stored URL survives reopening the DataStore and can be replaced`, `a stored URL that no longer validates counts as not configured`, `requests go to the configured server below its path`, `keeps the query and encoded path when pointing a request at the server`, `fails requests while no server is configured`, `an injected API uses the stored server URL, token and refresh client` | C1 | Pass |
| AC3: validated as https, with http only for localhost/emulator | `accepts https and normalizes the trailing slash`, `allows http only for localhost and the emulator host`, `cleartext host matching is exact and case-insensitive`, `keeps port and path and rejects blank input`, `rejects malformed URLs` | C1 | Pass |
| AC3 (platform side): `network_security_config.xml` allows cleartext only for the same hosts | none | - | Not run. Robolectric does not apply NetworkSecurityPolicy to OkHttp sockets; this needs an instrumented test on a device. |
| AC4: `testDebugUnitTest --tests 'ch.sonensei.cockpit.core.network.*'` passes | all 60 | C1 | **Fail**: 58 passed, 2 failed (TW-2) |
| Hilt NetworkModule graph | `the graph provides the APIs and shares one token store`, `an injected API uses the stored server URL, token and refresh client` | C1 | Pass |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.core.network.*'`

Result:

Fail. ktlintCheck passed after one formatting fix in a new test. Tests: 60 completed, 2 failed, both TW-2.

## Full test execution

Command:

`cd android && ./gradlew --continue ktlintCheck lintDebug testDebugUnitTest`

Result:

Fail. ktlintCheck passed and lintDebug passed. testDebugUnitTest: 106 tests completed, 2 failed. These are the same two TW-2 tests; no other test failed.

## .taskcheck

Result:

Fail. Command: `sh scripts/check.sh` (log in `.tmp/aac-tw/taskcheck.log`, Git-ignored). The TFP gate tests passed. The server step `./gradlew check` failed: 950 tests, 1 failed, `ClockConfigurationTests.the application clock is the system clock in UTC` with "2026-10-20T13:46:02.876981Z should be >= 2026-10-20T13:46:02.876981235Z". That is the timing flake in `server/` that `fixes.md` already reported; this task does not touch `server/`. The script stops at the first failing step, so the web and android steps did not run in this check run. The android step (`ktlintCheck testDebugUnitTest lintDebug`) would fail on TW-2 anyway, as the full Android run above shows.

## Bugs found

### A refresh answer with an unparseable date-time crashes the OkHttp dispatcher thread (TW-1 fix incomplete)

Finding ID:

`TW-2`

Test:

- `AuthApiTokenRefresherTest.a success body with an invalid expiry date is a failed refresh, not a crash`
- `TokenAuthenticatorTest.a refresh answer with an invalid date fails like a server error instead of crashing the call`

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/core/network/TokenRefresher.kt:35-45`. `AuthApiTokenRefresher.refresh` catches only `IOException`, `SerializationException` and `IllegalArgumentException`.

Requirement:

The requirement is the same as TW-1/CR-1. AC2 says "a refresh failure logs out". The assumption says "network errors and 5xx keep the tokens and just fail the request". `RefreshResult.Failed` is documented as "the server could not be reached or failed". A 200 whose body cannot be decoded is a server failure, and it must not throw out of the authenticator (see the comment at `TokenRefresher.kt:41`).

Expected behavior:

`refresh` returns `RefreshResult.Failed`. The original call completes with its 401, and the tokens are kept.

Actual behavior:

`TokenResponse.accessTokenExpiresAt` and `refreshTokenExpiresAt` are decoded by the generated `OffsetDateTimeAdapter` (`OffsetDateTime.parse`). For a value such as `"tomorrow"` it throws `java.time.format.DateTimeParseException`. That is a `DateTimeException`/`RuntimeException`; it is not an `IllegalArgumentException` or `SerializationException`, and kotlinx.serialization does not wrap it. The exception propagates out of `refresh` and `TokenAuthenticator.authenticate`. In the end-to-end test, the suspend call fails with `IOException: canceled due to ...DateTimeParseException`, and stderr shows `Exception in thread "OkHttp Dispatcher" java.time.format.DateTimeParseException: Text 'soon' could not be parsed at index 0`. On Android that uncaught exception kills the app process.

The `uuid` field (`deviceId`) is already safe: its `IllegalArgumentException` is caught (test passes). An unknown `tokenType` raises a `SerializationException` and is safe too (test passes).

Severity:

High. A server, proxy or version-skew answer outside the app's control crashes the whole app.

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

### Observations (not defects against a stated requirement)

- Corrupt or unreadable settings while logged in: `setServerUrl` then reads `previous == null` (`readablePrefs` gives empty prefs). The new URL is stored without a logout, so tokens issued by the unknown previous server stay. The same holds by design for "the first URL keeps the tokens". In practice this is unlikely: tokens are in memory only, and a login needs a readable configured URL. However, it is less strict than the "a stored URL that no longer parses counts as another origin" rule. No test was added; the parent should decide.
- The in-flight retry to a previous server carrying the new access token is already recorded as an assumption, so no test was added.

## Coverage

Status: partial-failed (diagnostic). The report is fresh and covers the final HEAD d705eeb plus the current tests. Two tests fail (TW-2), so this is not a passing validation result. A TW-2 fix in `TokenRefresher.kt` will make it stale, so coverage must be re-measured after the fix.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: all 7 Kotlin production files were added in this task (`git diff --numstat c3da5a4..HEAD` shows only added lines, status `A`). Changed-line coverage therefore equals the whole-file LINE counters below (153/154). Changed-branch coverage was not measured separately.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-api-client. Base c3da5a46ddc3bad27316de552b8c6c487dc62e7f (equal to `git merge-base HEAD origin/main`, and an ancestor of HEAD). HEAD d705eeb752fe7ef0e67583dc87daf3ed69f55e6b. Commits 898e80c, 60f375f, 9ac1205, 09bb66b and d705eeb, supplied by the parent. The file list comes from `git diff --name-status c3da5a4..HEAD -- android/app/src/main`, checked against the task `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD d705eeb, with no uncommitted production changes. Test sha256 values: AuthApiTokenRefresherTest 8a387fb582a6..., NetworkModuleGraphTest 55fbf7ad880a..., ServerUrlTest 91a6d5e0982f..., TokenAuthenticatorTest 0963e9d0a07e..., TokenAuthenticatorUnitTest b5d5e60b93e1.... Production sha256 values: TokenRefresher 67187a948809..., TokenAuthenticator 35f7cca1ce94..., ServerUrl db2e7d34d8d1..., AuthSession 4f70121480cd..., TokenStore fb2df7528d19..., NetworkModule 63ec474b59de..., AccessTokenInterceptor d154ee214283.... The list is in `.tmp/aac-tw/snapshot.sha256`. It was taken before the coverage run, and `sha256sum -c` confirmed it unchanged after the run.
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/core/network/{AccessTokenInterceptor,AuthSession,NetworkModule,ServerUrl,TokenAuthenticator,TokenRefresher,TokenStore}.kt`. No renamed or deleted files. No scoped file is missing from the report. Excluded files:
  - `android/app/build.gradle.kts`, `android/app/openapi-generator-ignore`, `android/gradle/libs.versions.toml`: build configuration.
  - `AndroidManifest.xml`, `res/xml/network_security_config.xml`: resources with no bytecode.
  - Hilt/KSP `*_Factory.java`: generated. `InMemoryTokenStore_Factory` shows 0/5 instructions and is left out of the aggregate.
  - The generated OpenAPI client under `build/generated/openapi`: generated.
  - `api/README.md`, `docs/ARCHITECTURE.md`: documentation.
- Provider/version, command, working directory, and test selection: JaCoCo through AGP `enableUnitTestCoverage` and the Gradle `jacoco` plugin. The tool version is not pinned in the build and not printed in the report, so it is the Gradle/AGP default. Working directory `android/`. Command: `./gradlew -I /home/eric/work/my-cockpit-agents/agent-2/.tmp/ignore-test-failures.init.gradle testDebugUnitTest createDebugUnitTestCoverageReport --rerun`. Test selection: the full debug unit suite (106 tests). The init script is outside the project configuration, in Git-ignored `.tmp/`. It only sets `ignoreFailures = true` on Test tasks, so the report is produced despite the 2 TW-2 failures.
- Run start/completion time and exit status: started 2026-10-20T13:42:43Z and completed 2026-10-20T13:43:17Z. Gradle exited 0 (BUILD SUCCESSFUL only because of ignoreFailures). 106 tests ran and 2 failed.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 6a222cb63aabcdeaf20a645dc5c792b50b03d51276b9219069cd2b38bc89982a, JaCoCo session `dell26-ced5e36e`. The file is dated 2026-10-20 15:43:17 +0200.
- Evidence outputs are fresh and inputs stayed unchanged: yes. `--rerun` executed the tests, the report time falls inside the run window, and the snapshot was verified after the run.
- Final run after latest test additions and production fixes: this run follows the latest test additions and the fixes for TW-1 and CR-1..CR-4. TW-2 is not fixed yet, so a new run is needed after its fix.

Values below are covered/total (percentage). Zero-denominator or unsupported metrics are N/A. Statements are JaCoCo INSTRUCTION counters.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/core/network/AccessTokenInterceptor.kt` | 70/70 (100%) | 12/12 (100%) | 4/4 (100%) | 9/9 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/core/network/AuthSession.kt` | 87/87 (100%) | 4/4 (100%) | 7/7 (100%) | 16/16 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/core/network/NetworkModule.kt` | 171/174 (98.3%) | 4/4 (100%) | 15/16 (93.8%) | 28/29 (96.6%) |
| `android/app/src/main/java/ch/sonensei/cockpit/core/network/ServerUrl.kt` | 372/403 (92.3%) | 40/44 (90.9%) | 19/22 (86.4%) | 48/48 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/core/network/TokenAuthenticator.kt` | 127/127 (100%) | 18/18 (100%) | 3/3 (100%) | 19/19 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/core/network/TokenRefresher.kt` | 95/95 (100%) | 8/8 (100%) | 5/5 (100%) | 19/19 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/core/network/TokenStore.kt` | 43/43 (100%) | 2/2 (100%) | 9/9 (100%) | 14/14 (100%) |
| Aggregate (summed counters over these 7 files; exclusions listed above; no file unmeasured) | 965/999 (96.6%) | 88/92 (95.7%) | 62/66 (93.9%) | 153/154 (99.4%) |

`TokenRefresher.kt` shows 100% although TW-2 is a real defect there. The missing behavior is an absent catch, which line and branch coverage cannot reveal.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `TokenRefresher.kt:35-45` (no uncovered lines) | A refresh answer that cannot be decoded must be `Failed`, not a crash | Tests added. Finding TW-2. |
| `NetworkModule.kt:81-83` (3 instructions, 1 method) | Constructor of the abstract `NetworkBindings` Hilt module. It is only used through `@Binds` and never instantiated. | Non-material, not reachable |
| `ServerUrl.kt:95` (25 instructions, 2 branches) | The `catch` lambda of `readablePrefs`: the rethrow of a non-`IOException`, plus its coroutine state-machine resume path. The `IOException` -> empty-prefs path is covered by `an unreadable settings file counts as not configured`. | Non-material defensive rethrow. A non-IO DataStore failure is not something the task handles. |
| `ServerUrl.kt:50` (1 instruction, 2 of 8 branches) | Short-circuit combinations of the user/password/query/fragment check, e.g. a password without a user | Non-material. Each rejected component is tested in `rejects malformed URLs`. |
| `ServerUrl.kt:29` (3 instructions) | Generated data-class member of `ServerUrlValidation.Invalid` | Non-material generated code |
| `ServerUrl.kt:43` (2 instructions) | Static initializer path of the `ServerUrlValidator` object | Non-material |

## Unable to test

- Enforcement of `network_security_config.xml` (the platform blocking cleartext to other hosts). Robolectric does not apply NetworkSecurityPolicy to OkHttp sockets. This needs an instrumented test on a device or emulator; follow-up is optional because the validator side is covered.
- Build-time generation from a clean checkout was observed only in the normal build, where `openApiGenerate` cleans its output directory and regenerates on every run. A separate CI-style clean clone was not built.

## Summary

Tests were added, and they found a production defect that must go to the `fixer` before the task can be completed. TW-2 (High): a refresh answer whose date-time field cannot be parsed throws `DateTimeParseException` out of the authenticator and crashes OkHttp's dispatcher thread. TW-1's catch list misses this case. All other acceptance criteria pass, including the CR-1..CR-4 fixes and the new tests for their remaining branches. Coverage must be measured again after the TW-2 fix.
