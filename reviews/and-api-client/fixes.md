# Fix Report

Date: 2026-10-20

Task: and-api-client - Android API client and auth plumbing

Scope: current task. Round 2 covers CR-5 (= TW-3), CR-6 and CR-7 from `reviews/and-api-client/review.md` (code-reviewer, round 2) and `reviews/and-api-client/tests.md`, on HEAD a9650e58. The round-1 findings (TW-1, TW-2, CR-1 to CR-4) are summarized under "Round 1 history". The round-2 changes are uncommitted in the working tree, in `android/app/src/main/java/ch/sonensei/cockpit/core/network` and `android/app/src/test/java/ch/sonensei/cockpit/core/network` only.

## Fixed findings

### CR-5 / TW-3 - Concurrent 401s with a failing refresh repeat the refresh once per waiting request

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/core/network/TokenAuthenticator.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/core/network/TokenAuthenticatorTest.kt`

Problem:
When the shared refresh returned `RefreshResult.Failed` (5xx, timeout, network error, undecodable body), the tokens stayed the same. Each request waiting on `session.refreshLock` then started its own refresh, one after another: 8 concurrent 401s with a 503 refresh made 8 refresh calls.

Fix:
- `TokenAuthenticator` now keeps a `@Volatile failedRefreshes` counter and `failedFrom` (the tokens the last failed refresh started from). Both are written under the refresh lock.
- Each call to `authenticate` reads the counter before it waits for the lock.
- Inside the lock, a waiter returns null (gives up with its 401) when the counter changed while it waited and the stored tokens are still the pair the failed refresh started from.
- A 401 that reaches the authenticator after the failure sees the new counter value, so it may refresh again.
- Success, rejection (logout) and the compare-and-set handling of concurrent logins and logouts are unchanged.

Validation:
- New test `TokenAuthenticatorTest.concurrent 401s with a failing refresh refresh once, keep the tokens and a later request refreshes again` - Pass.
  - Setup: 8 synchronous OkHttp calls on 8 threads. Synchronous calls avoid OkHttp's 5-per-host limit for queued calls, which would otherwise send 3 of the requests only after the failure. The 503 refresh answers only after all 8 requests got their 401, plus 300 ms.
  - Phase 1 result: all 8 return 401, exactly 1 refresh call, tokens kept, no logout event.
  - Phase 2: a later request with a working refresh gets 200, the refresh count goes to 2, and the new tokens are stored.
- Negative check: with the new guard temporarily disabled, the test fails with `expected:<1> but was:<8>`. The guard was restored afterwards.
- The network tests passed 3 times in a row with `--rerun-tasks`.

### CR-6 - `setServerUrl` can block the caller's thread for a whole refresh

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/core/network/ServerUrl.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/core/network/ServerUrlTest.kt`

Problem:
The suspend function entered `synchronized(session.refreshLock)` on the caller's dispatcher. While a refresh held the lock, a main-thread caller was blocked.

Fix:
- The locked logout now runs in `withContext(Dispatchers.IO)`.
- No dispatcher is injected. `ServerUrlRepository` has an `@Inject` constructor, and the package has no injected-dispatcher pattern to follow. `Dispatchers.IO` is already used directly in `NetworkModule`.
- The KDoc now says the function is main-safe.

Validation:
- New test `ServerUrlTest.a server change during a running refresh does not block the calling thread` - Pass. It holds the refresh lock and starts `setServerUrl` on a single-thread dispatcher. Another coroutine on that same dispatcher still runs. After the lock is released, the change completes and the tokens are cleared.
- Updated test `ServerUrlTest.a server change waits for a running refresh, which still goes to the previous server` - Pass.
  - Before: it expected the calling thread itself to be `BLOCKED`. That was true only of the old implementation.
  - Now: it waits until some thread is `BLOCKED` inside `setServerUrl` (the IO thread), and it asserts that the tokens are still present at that moment.
  - The behavior it checks is unchanged: the server change waits for the refresh, the refresh still uses the previous server, and afterwards the tokens are cleared and the new URL is stored.

### CR-7 - No previous URL (unreadable settings) keeps the tokens on a server change

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/core/network/ServerUrl.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/core/network/ServerUrlTest.kt`

Problem:
`previous == null` is the result both when no URL was ever stored and when the settings could not be read. In either case the old code skipped the logout, so tokens of an unknown server could go to the new host.

Fix:
`setServerUrl` now runs the logout check also when `previous == null`. The logout itself still happens only when `session.isLoggedIn`. In the normal first setup there are no tokens yet, so nothing changes and no event is emitted.

Validation:
- New test `ServerUrlTest.setting a URL while logged in without a readable previous URL logs out` - Pass. It covers two cases:
  - a corrupt settings file, using the production `serverConfigDataStore` that replaces the file;
  - an empty store.
  In both cases the tokens are cleared, `SERVER_CHANGED` is emitted (2 events in total), and the new URL is stored.
- Test changed: `setting the first URL or one on the same origin keeps the tokens` is renamed to `setting a URL on the same origin keeps the tokens`.
  - Why: it saved tokens before the first URL was set and expected them to be kept. CR-7 makes exactly that case log out, so the old expectation contradicts the accepted finding.
  - Change: the test now sets the first URL before the login, as the app's setup flow does.
  - Unchanged: the same-URL, same-origin (host case, default port, path) and invalid-URL assertions. `a server change while logged out stores the URL without a logout event` still covers the logged-out path.

## Not fixed

None.

## Round 1 history (already fixed, unchanged in round 2)

- TW-1 / CR-1: a malformed refresh body crashed the OkHttp thread. `AuthApiTokenRefresher` now maps `SerializationException` and `IllegalArgumentException` to `RefreshResult.Failed`.
- CR-2: tokens were not bound to their server. A cross-origin `setServerUrl` now logs out with `SERVER_CHANGED` under `AuthSession.refreshLock`, before the new URL is stored.
- CR-3: a refresh overwrote a logout or login that happened during it. Fixed with `TokenStore.replace` (compare-and-set) and `AuthSession.logoutIfCurrent`.
- CR-4: a corrupt server-config DataStore broke every request. Fixed with a `ReplaceFileCorruptionHandler` in `serverConfigDataStore`, and reads now treat an `IOException` as empty settings.
- TW-2: an invalid date-time in a refresh answer crashed the OkHttp thread. `java.time.DateTimeException` is now mapped to `RefreshResult.Failed`.
- Round-1 validation: `cd android && ./gradlew ktlintCheck lintDebug testDebugUnitTest` - Pass (106 tests). `sh scripts/check.sh` - Pass on the second run. The first run hit a timing flake in the server `ClockConfigurationTests`, in code this task does not touch.
- Still noted from round 1, not a finding and not changed: the authenticator retries on the host the request was originally sent to. A 401 from the old server that arrives after a server change and a new login could get the new access token. The refresh token is not affected.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.core.network.*'` - Pass. 66 tests, 0 failures:
  - TokenAuthenticatorTest 13
  - TokenAuthenticatorUnitTest 17
  - ServerUrlTest 21
  - AuthApiTokenRefresherTest 11
  - NetworkModuleGraphTest 2
  - NetworkSecurityConfigTest 2
- ktlintCheck passed after ktlintFormat.
- `./gradlew testDebugUnitTest --rerun-tasks --tests 'ch.sonensei.cockpit.core.network.*'`, 3 times in a row - Pass.

Full test suite:
- `cd android && ./gradlew ktlintCheck lintDebug testDebugUnitTest` - BUILD SUCCESSFUL, 112 tests, 0 failures, 0 errors.

Lint/type-check/build:
- ktlintCheck and lintDebug pass, as part of the command above.

`.taskcheck`:
- Not run in this fixer round. Left to the parent worker.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
