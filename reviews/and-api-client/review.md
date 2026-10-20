# Code Review

Date: 2026-10-20

Task: and-api-client - Android API client and auth plumbing

Scope: `git diff 28f878ac..8515b269` (28f878ac = `git merge-base HEAD origin/main`; task commits 1709c62f..8515b269). This is the final review round. The reviewed revision is 8515b2698a4d47a19359ba4d0781a7fc1dc52ec9. The round-3 review covered 28b0d42d, which has the same task code as 08c90955 in the current (rebased) history. `git diff 08c90955 HEAD --stat` shows that since then only `ServerUrlTest.kt`, `TokenAuthenticatorUnitTest.kt`, `reviews/and-api-client/review.md` and `reviews/and-api-client/tests.md` changed. No production code changed. I re-read the whole task change against acceptance criteria 1-4 and gave the new tests in dd33e2fc a close review. The working tree was clean when the review started.

Requirements:
- `tasks/and-api-client.md`: acceptance criteria 1-4 and the recorded assumptions
- `AGENTS.md` project notes, `docs/ARCHITECTURE.md`
- Earlier rounds: `reviews/and-api-client/fixes.md`, the round-3 `review.md`

Git range:
- Branch: main
- HEAD: 8515b269
- Base/start: 28f878ac126095c2119a7b3fe124acb3b578324d
- Commits reviewed: 11 (1709c62f..8515b269)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## New tests in dd33e2fc

- **`TokenAuthenticatorUnitTest`: `a 401 waiting for a refresh that fails gives up, and a later 401 refreshes again`**
  - `awaitBlocked(second)` waits until the second thread is BLOCKED on `session.refreshLock`. Before line 44 (`TokenAuthenticator.kt`) no other monitor is taken, so that thread has already read `failedRefreshes` (line 43) before the refresh fails. The test therefore pins down the "waiter of the failed refresh" interleaving and does not depend on timing.
  - The test fails if the shared-failure guard (line 47) is removed, because `refreshedWith` would then hold two entries before the later call.
  - It checks the follow-up as well: a 401 after the failure refreshes again. `finish` is already open, so that call does not wait.
- **`TokenAuthenticatorUnitTest`: `a 401 waiting for a failed refresh refreshes itself when the tokens changed meanwhile`**
  - The refresher swaps in a pair with the same access token and a rotated refresh token before it answers `Failed`. The waiter then sees `current != failedFrom` and refreshes with `rotated-refresh`.
  - The test fails if the guard checks only the counter and not `failedFrom`. This covers the second half of the CR-5 fix.
  - The thread results are not asserted. This is acceptable because `refreshedWith` carries the behavior under test.
- **`ServerUrlTest`: `settings the production store cannot read ... count as not configured`**
  - A directory used as the settings file raises an `IOException` that `ReplaceFileCorruptionHandler` does not handle. `readablePrefs()` (`ServerUrl.kt:102-103`) turns it into empty settings, and the flow completes. `toList()` therefore ends with `[null]`.
  - If the `catch` regresses, the test fails with the exception and does not hang.
- **`ServerUrlTest`: main-safe server change, tightened**
  - Before this change, the test could pass while `setServerUrl` was only suspended in the DataStore read. It now waits until exactly one thread is BLOCKED inside `setServerUrl`, and it asserts that this thread is not the caller's single executor thread (identified through the thread factory).
  - An implementation that takes the lock on the caller's thread would fail this test. The assertion is stronger than before, not weaker.
  - `blockedThreadsIn` scans all JVM threads. That is safe here: the build configures no parallel test execution, and the only other user (line 264) releases its threads before the test ends.
- No existing assertion was removed or loosened. The old `aThreadIsBlockedIn` now delegates to `blockedThreadsIn` and behaves the same.

## Acceptance criteria

1. **Build-time generation.** `android/app/build.gradle.kts` applies `org.openapi.generator` 7.26.0. Its `kotlin` generator with the `jvm-retrofit2` library, `kotlinx_serialization` and coroutines reads `../api/openapi.yaml` and writes to `build/generated/openapi`. That folder is added as a source directory, and every compile and ksp task depends on generation. `git ls-files` shows no generated Android sources are tracked. Met.
2. **Interceptor and Authenticator.**
   - `AccessTokenInterceptor` adds the bearer token.
   - `TokenAuthenticator` refreshes once (`priorResponse` guard).
   - Concurrent 401s share one refresh for Success, Rejected and Failed.
   - A rejected refresh logs out through `logoutIfCurrent`.
   - MockWebServer tests cover each case (`TokenAuthenticatorTest`: 13 tests).
   - Met.
3. **Server URL.**
   - It is kept in DataStore (`ServerUrlRepository`) and validated as https.
   - http is allowed only for `CLEARTEXT_HOSTS`, which matches `network_security_config.xml` (`NetworkSecurityConfigTest`).
   - The URL is applied per request by `ServerUrlInterceptor`.
   - Met.
4. **Test run.** I ran `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.core.network.*' --rerun`, and it passed. The JUnit XML lists 69 tests, 0 failures, 0 errors, 0 skipped:

   | Test class | Tests |
   | --- | --- |
   | `AuthApiTokenRefresherTest` | 11 |
   | `NetworkModuleGraphTest` | 2 |
   | `NetworkSecurityConfigTest` | 2 |
   | `ServerUrlTest` | 22 |
   | `TokenAuthenticatorTest` | 13 |
   | `TokenAuthenticatorUnitTest` | 19 |

   The suite covers token injection, a single refresh for concurrent 401s and logout on refresh failure. Met.

Also checked, with no findings:
- No tokens are logged; `AuthTokens.toString()` is redacted.
- The refresh client has neither the authenticator nor the token interceptor.
- A server change logs out under the refresh lock, off the caller's thread.
- No secrets are committed.
- Build-file changes are limited to the plugin line and the version-catalog entry.
- The INTERNET permission and the network security config are recorded as assumptions.

The earlier findings CR-1..CR-7, TW-1..TW-3 are still fixed in the unchanged production code.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. Revision 8515b269 meets acceptance criteria 1-4. The round-3 tests are deterministic, they detect the regressions they target, and they do not weaken any earlier assertion.
