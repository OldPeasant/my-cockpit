# Test Report

Date: 2026-10-20

Task: and-api-client - Android API client and auth plumbing

Scope: `git diff 84979c21..HEAD` on branch main. Base 84979c21 (the current main base, given by the parent worker), HEAD d0edbce20440da638f5e4cc7460a2660a7217a29 (merge of `origin/wip/and-api-client`). Task commits 26ee3d5a, f8fcbcfc, 160b16bf, f5cbbe4a, 8de98cfe, 183d35ce, 748e7b62. The test changes listed below are uncommitted. There are no uncommitted production changes. This is the second round, on the final code after fixes TW-1, TW-2 and CR-1..CR-4. It replaces the earlier report, which described a pre-fix revision.

Requirements:
- `tasks/and-api-client.md` acceptance criteria 1-4 and the recorded assumptions. Those are: refresh 400/401 = logout; network errors and 5xx keep the tokens; a 401 on a retried request is final; cleartext only for localhost, 127.0.0.1, ::1 and 10.0.2.2; tokens kept in memory; tokens bound to the server origin.
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md` project notes, `reviews/and-api-client/fixes.md`.

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/core/network/TokenAuthenticatorTest.kt` (1 test added)
- `android/app/src/test/java/ch/sonensei/cockpit/core/network/ServerUrlTest.kt` (2 malformed cases added to an existing test)
- `android/app/src/test/java/ch/sonensei/cockpit/core/network/NetworkSecurityConfigTest.kt` (new, 2 tests)

## Tests added or updated

Count: 4. Three tests are new and one existing test was extended. No test was removed, weakened or disabled.

- `TokenAuthenticatorTest.concurrent 401s with a rejected refresh refresh once and log out once`. It runs against MockWebServer: 8 parallel requests, and the refresh endpoint answers 401 after 300 ms. Checked: all requests get 401, there is exactly 1 refresh call, the tokens are cleared, and exactly one `REFRESH_REJECTED` logout event is emitted.
- `NetworkSecurityConfigTest.cleartext is denied by default and allowed exactly for the validator's cleartext hosts`. Checked: the base config forbids cleartext, and the cleartext domains (no subdomains) equal `ServerUrlValidator.CLEARTEXT_HOSTS`. The platform enforces this file at runtime but Robolectric does not, so the two lists are checked against each other.
- `NetworkSecurityConfigTest.the manifest uses the network security config and may access the network`.
- `ServerUrlTest.rejects malformed URLs`: added `https://user@example.org` and `https://:pw@example.org`. This covers the two userinfo branches left untested in the first coverage run of this round.

## Requirement coverage

Focused command (F): `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.core.network.*' ktlintCheck`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: openapi-generator builds Kotlin (Retrofit2 + kotlinx.serialization) from `../api/openapi.yaml` at build time, not committed | Compile-time use of `generated.apis.AuthApi/EventsApi/SettingsApi/SetupApi` in all suites; `NetworkModuleGraphTest.the graph provides the APIs and shares one token store`; `AuthApiTokenRefresherTest` (kotlinx decode of `TokenResponse`) | F. Also: `find android/app/build/generated/openapi/src/main/kotlin -name '*.kt' \| wc -l` -> 48; `git ls-files android \| grep -i generated` -> none; `git check-ignore -v android/app/build/generated/openapi/x` -> `android/.gitignore:2:build/` | Pass |
| AC2: interceptor adds the access token | `TokenAuthenticatorTest.adds the access token to requests`, `sends no Authorization header when logged out`, `an Authorization header set by the caller is not overwritten` | F | Pass |
| AC2: Authenticator refreshes once on 401 | `TokenAuthenticatorTest.refreshes once on 401 and retries with the new token`, `a refreshed token that is rejected too is not refreshed again`; `TokenAuthenticatorUnitTest.a 401 for an already retried request is final...`, `a 401 for a request without a bearer token is final` | F | Pass |
| AC2: concurrent requests share one refresh (MockWebServer) | `TokenAuthenticatorTest.concurrent 401s share one refresh` (8 parallel, 1 refresh), `concurrent 401s with a rejected refresh refresh once and log out once` (new); `TokenAuthenticatorUnitTest.a 401 arriving while another refresh runs waits for it and reuses its token` | F | Pass (see TW-3 for the failed-refresh case) |
| AC2: a refresh failure logs out | `TokenAuthenticatorTest.a rejected refresh logs out`; `TokenAuthenticatorUnitTest.a rejected refresh logs out with REFRESH_REJECTED`, plus race tests (a logout or login during the refresh); `AuthApiTokenRefresherTest.400 and 401 mean the refresh token was rejected`. By assumption, network errors, 5xx and malformed answers keep the tokens: `a server error during refresh keeps the tokens`, `a network error during refresh...`, `a malformed refresh answer...`, `a refresh answer with an invalid date...` | F | Pass |
| AC3: server base URL configurable (DataStore) | `ServerUrlTest.stores a valid URL in DataStore and ignores invalid ones`, `the stored URL survives reopening the DataStore and can be replaced`, `requests go to the configured server below its path`, `keeps the query and encoded path...`, corrupt-file tests; `NetworkModuleGraphTest.an injected API uses the stored server URL, token and refresh client` | F | Pass |
| AC3: https required, http only for localhost/emulator | `ServerUrlTest.allows http only for localhost and the emulator host`, `cleartext host matching is exact and case-insensitive`, `rejects malformed URLs`; `NetworkSecurityConfigTest` (new) | F | Pass |
| Assumption: tokens bound to the server origin (CR fixes) | `ServerUrlTest.moving to another server logs out with SERVER_CHANGED`, `a server change waits for a running refresh...`, `a request to the new server after a server change carries no token`, `setting the first URL or one on the same origin keeps the tokens` | F | Pass |
| AC4: the named test command passes | all 63 tests in `ch.sonensei.cockpit.core.network.*` | F | Pass (63 tests, 0 failures, 0 errors, 0 skipped) |
| Cleartext really allowed or blocked on a device or emulator (platform enforcement of `network_security_config.xml`) | none at runtime; only static consistency in `NetworkSecurityConfigTest` | - | Not run: no emulator or device here, and Robolectric does not enforce network security config |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.core.network.*' ktlintCheck`

Result:

Pass. BUILD SUCCESSFUL. JUnit XML: TokenAuthenticatorTest 12, TokenAuthenticatorUnitTest 17, AuthApiTokenRefresherTest 11, ServerUrlTest 19, NetworkModuleGraphTest 2, NetworkSecurityConfigTest 2. Total 63, with 0 failures, 0 errors and 0 skipped. ktlintCheck passed on the new and changed test files.

## Full test execution

Command:

`cd android && ./gradlew createDebugUnitTestCoverageReport --rerun-tasks` (all Android debug unit tests, with coverage)

Result:

Pass (BUILD SUCCESSFUL, 2026-10-20T14:26:06Z to 14:27:54Z).

## .taskcheck

Result:

Pass. `sh scripts/check.sh` (server, web, android: lint + unit tests) printed `== all checks passed`. It ran from 2026-10-20T14:27:59Z to 14:30:14Z (about 2 min 15 s).

## Bugs found

No requirement test fails. One finding is for the parent worker to decide on. It depends on how the criterion is read, so no failing test was committed for it.

### Concurrent 401s with a failing (5xx/network) refresh each run their own refresh, one after another

Finding ID:

`TW-3`

Test:

No committed test. A diagnostic run reproduced it. Setup: the `TokenAuthenticatorTest` fixture, 8 parallel `getCurrentUser()` calls with tokens `old-access/old-refresh`, and a refresh endpoint that answers 503 after 300 ms. Observed: `refreshCalls=8`, all 401. The diagnostic test was removed again.

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/core/network/TokenAuthenticator.kt:33-47`

Requirement:

AC2: "concurrent requests share one refresh". The recorded assumption only says that a failed refresh keeps the tokens and fails the request.

Expected behavior:

This is ambiguous. If "share one refresh" also covers the failure case, the requests that queued behind a failed refresh should fail without starting their own refresh.

Actual behavior:

After a `RefreshResult.Failed`, the stored tokens are unchanged. So each waiting thread finds `current.accessToken == sentToken` and calls the refresh endpoint again while holding the lock. N concurrent requests cause N serial refresh calls, and the last caller waits about N x the refresh latency. With OkHttp's default 10 s timeouts and an unreachable server, a burst of requests can stall for a very long time.

Severity:

Low. Success and rejection are handled correctly; this is only a load and latency problem when the server fails.

Fix required:

`Parent decision: either fix it (e.g. let callers that waited on a failed refresh started for the same token give up) or record the behavior as an assumption.`

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: equal to whole-file coverage for the Kotlin files. All 7 Kotlin files in scope were added by this task (`git diff --name-only --diff-filter=A 84979c21..HEAD -- android/app/src/main`), so every executable line is a changed line.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-api-client. Base 84979c21 and HEAD d0edbce20440da638f5e4cc7460a2660a7217a29, as given by the parent. Task commits 26ee3d5a..748e7b62 (merged in d0edbce2). File scope from `git diff --name-only 84979c21..HEAD -- android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): sha256 over `sha256sum` of `android/app/src/{main,test}/java/ch/sonensei/cockpit/core/network/*.kt` = `2d9e8eee83c391598558fbf5d0116650d73bf97a5a693429c93ed1b70ad8ea32`. It was taken before and after the run and was identical both times. It includes the uncommitted test changes.
- Exact included files: `AccessTokenInterceptor.kt`, `AuthSession.kt`, `NetworkModule.kt`, `ServerUrl.kt`, `TokenAuthenticator.kt`, `TokenRefresher.kt`, `TokenStore.kt` under `android/app/src/main/java/ch/sonensei/cockpit/core/network/`. All 7 are present in the report. Excluded: `AndroidManifest.xml` and `res/xml/network_security_config.xml`, which are not executable code (checked by `NetworkSecurityConfigTest` instead). Generated `core/network/generated/**` was excluded because it is generated output. No files were renamed or deleted.
- Provider/version, command, working directory, and test selection: JaCoCo 0.8.15 via AGP `enableUnitTestCoverage`. Command `./gradlew createDebugUnitTestCoverageReport --rerun-tasks`, run in `android/`, on all debug unit tests.
- Run start/completion time and exit status: 2026-10-20T14:26:06Z to 14:27:54Z, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 `6132248a92860489b123adaa18cbb8549ca27d07ef8ca050955d5ae41206b809`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. `--rerun-tasks` re-executed all 43 tasks, and the snapshot hash was identical before and after.
- Final run after latest test additions and production fixes: yes. No production code changed after this run.

Values below use covered/total (percentage) from the JaCoCo XML counters. Statements = INSTRUCTION, Functions = METHOD.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `core/network/AccessTokenInterceptor.kt` | 70/70 (100%) | 12/12 (100%) | 4/4 (100%) | 9/9 (100%) |
| `core/network/AuthSession.kt` | 87/87 (100%) | 4/4 (100%) | 7/7 (100%) | 16/16 (100%) |
| `core/network/NetworkModule.kt` | 171/174 (98.3%) | 4/4 (100%) | 15/16 (93.8%) | 28/29 (96.6%) |
| `core/network/ServerUrl.kt` | 375/403 (93.1%) | 42/44 (95.5%) | 20/22 (90.9%) | 48/48 (100%) |
| `core/network/TokenAuthenticator.kt` | 127/127 (100%) | 18/18 (100%) | 3/3 (100%) | 19/19 (100%) |
| `core/network/TokenRefresher.kt` | 99/99 (100%) | 8/8 (100%) | 5/5 (100%) | 21/21 (100%) |
| `core/network/TokenStore.kt` | 43/43 (100%) | 2/2 (100%) | 9/9 (100%) | 14/14 (100%) |
| **Aggregate (summed counters, 7/7 files measured, none missing)** | 972/1003 (96.9%) | 90/92 (97.8%) | 63/66 (95.5%) | 155/156 (99.4%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ServerUrl.kt:50` (2 userinfo branches; missed in the first run of this round) | URL with credentials must be rejected | Added `https://user@example.org` and `https://:pw@example.org` to `rejects malformed URLs`; now covered |
| `ServerUrl.kt:95` (2 branches: rethrow of a non-IOException from DataStore) | Defensive. Only IOException (a corrupt or unreadable file) is meant to be swallowed | Left uncovered: there is no realistic way to make DataStore throw a non-IO exception without mocking the core behavior |
| `ServerUrl.kt:29` and 2 methods (members the compiler generates for the `Invalid` data class) | None | Generated code; non-material |
| `NetworkModule.kt:81` (constructor of the abstract `NetworkBindings` Hilt module) | None; Hilt never instantiates it | Non-material |

## Unable to test

- Platform enforcement of cleartext rules on a real device or emulator: there is no emulator here and Robolectric does not apply `network_security_config.xml`. The static test checks the file against `CLEARTEXT_HOSTS`. Whether Android matches the IPv6 literal `::1` as written in the `<domain>` element was not verified. Follow-up is optional and can wait for an emulator/E2E task.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. All 4 acceptance criteria have passing tests, the focused command and `.taskcheck` pass, and coverage of the task's production files is 99.4% of lines and 97.8% of branches. One low-severity finding needs a parent decision: TW-3, where concurrent 401s each refresh again after a failed (5xx/network) refresh. The behavior is ambiguous against "concurrent requests share one refresh", so no failing test was committed for it.
