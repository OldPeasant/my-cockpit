# Fix Report

Date: 2026-10-20

Task: and-api-client - Android API client and auth plumbing

Scope: current task; findings TW-1 (`reviews/and-api-client/tests.md`) and CR-1, CR-2, CR-3, CR-4 (`reviews/and-api-client/review.md`), reviewed at HEAD ecfd091 (base 75b80f438c06233efd23a12c3a6a3383e7b25a84). The changes are uncommitted in the working tree.

## Fixed findings

### TW-1 / CR-1 - Malformed refresh response crashes the app

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/core/network/TokenRefresher.kt`

Problem:
`AuthApiTokenRefresher.refresh` caught only `IOException`. A 200 answer whose body could not be decoded threw a kotlinx.serialization exception (`JsonDecodingException`, `MissingFieldException`) out of `TokenAuthenticator.authenticate`. That exception reached OkHttp's dispatcher thread, which kills the app process.

Fix:
`SerializationException` and `IllegalArgumentException` are now mapped to `RefreshResult.Failed`, the result for a server failure: tokens are kept, there is no logout, and the original call completes with its 401. The three TW-1 tests were not changed.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.core.network.*'` - Pass. `AuthApiTokenRefresherTest`: 8/8, including `a malformed success body is a failed refresh, not a crash` and `a success body missing required fields is a failed refresh, not a crash`. `TokenAuthenticatorTest`: 10/10, including `a malformed refresh answer fails like a server error instead of crashing the call`.

### CR-2 - Tokens are not bound to the server they were issued by

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/core/network/ServerUrl.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/core/network/AuthSession.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/core/network/TokenAuthenticator.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/core/network/ServerUrlTest.kt`

Problem:
Changing the server URL kept the tokens. The access token, and on a 401 the refresh token, went to the new host.

Fix:
- `ServerUrlRepository` now depends on `AuthSession`. When a valid new URL has a different origin (scheme, host or port) than the stored one, `setServerUrl` logs out with the new reason `LogoutReason.SERVER_CHANGED` before it stores the new URL. A stored URL that no longer parses also counts as a different origin.
- Setting the first URL, the same URL again, or a URL on the same origin keeps the tokens. An invalid URL is not stored and changes nothing.
- The logout runs under `AuthSession.refreshLock`. `TokenAuthenticator` now uses this lock instead of a private one.
- Effect of the lock: a refresh that is already running finishes against the previous server, and only then are the tokens cleared. A request or refresh that reads the new URL finds no tokens.
- Change to the earlier, interrupted fixer run: it called `session.logout` inside `dataStore.edit`. I moved the logout out of the DataStore transaction, so the refresh lock is never taken inside an edit transform. Holding it there could block or deadlock against the refresh path, which reads the same DataStore in `ServerUrlInterceptor`. The order is now: read the previous URL, log out under the lock, then `edit`.

Validation:
- `ServerUrlTest.moving to another server logs out with SERVER_CHANGED` - Pass. It covers another host, https to http, a port change, and http to https on the same host and port.
- `ServerUrlTest.setting the first URL or one on the same origin keeps the tokens` - Pass. It covers the first URL, the same URL, host case and default port with a path, and an invalid URL.
- `ServerUrlTest.a server change waits for a running refresh, which still goes to the previous server` (new) - Pass. While a refresh is blocked, the server change waits on the lock (thread state `BLOCKED`). The refresh reads the previous URL. Afterwards the tokens are cleared and the new URL is stored.

### CR-3 - The authenticator overwrites a logout or login that happens during a refresh

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/core/network/TokenStore.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/core/network/AuthSession.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/core/network/TokenAuthenticator.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/core/network/TokenAuthenticatorUnitTest.kt`

Problem:
After a refresh, `save` or `logout(REFRESH_REJECTED)` ran unconditionally. A user logout during a successful refresh brought the tokens back. A rejected stale refresh cleared the tokens of a login that had just happened.

Fix:
- `TokenStore.replace(expected, new)` is a compare-and-set; `new = null` clears. In `InMemoryTokenStore`, `save`, `clear` and `replace` are `@Synchronized`.
- `AuthSession.logoutIfCurrent(tokens, reason)` clears the tokens and emits only if they are still the expected ones.
- The authenticator now applies a refresh result only if the stored tokens are still the ones the refresh started from. Otherwise it retries with whatever is stored now, or gives up (null) if that is nothing.
- `InMemoryTokenStore` is the only `TokenStore` implementation in the repository. No test fakes needed the new method.

Validation:
- `TokenAuthenticatorUnitTest` - Pass, 4 tests:
  - `a user logout during a successful refresh stays logged out`
  - `a login during a successful refresh keeps the new login and retries with it`
  - `a rejected stale refresh does not log out a login that happened meanwhile`
  - `replace only swaps the tokens it expects`
- In each interleaving test, the fake refresher runs the logout or login on another thread before it answers.

### CR-4 - A corrupt server-config DataStore breaks every request and the settings save

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/core/network/NetworkModule.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/core/network/ServerUrl.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/core/network/ServerUrlTest.kt`

Problem:
The store had no corruption handler, and an `IOException` from `dataStore.data` was not handled. With an unreadable file, every request, the save, and any collector failed.

Fix:
- The new `serverConfigDataStore(...)`, used by `NetworkModule`, creates the store with `ReplaceFileCorruptionHandler { emptyPreferences() }`.
- `ServerUrlRepository` reads through `readablePrefs()`, which emits `emptyPreferences()` on `IOException`. The URL then counts as "not configured". `setServerUrl` uses the same read.

Validation:
- `ServerUrlTest.an unreadable settings file counts as not configured` - Pass.
- `ServerUrlTest.the production settings store replaces a corrupt file so the URL can be set again` - Pass.

## Not fixed

None.

Not part of any finding and not changed, noted for the parent worker: `TokenAuthenticator` retries a request on the host the request was originally sent to. Suppose a request to the old server is in flight, the user changes the server, logs in on the new one, and only then the old server answers 401. The retry would then carry the new server's (short-lived) access token to the old, previously trusted server. The refresh token is not affected. Closing this would mean checking the request origin in the authenticator against the configured server. That is outside these findings.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.core.network.*'`. ktlintFormat fixed one wrapped line in `ServerUrl.kt`. The first run's ktlintCheck report reflected the code before formatting.
- Rerun of `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.core.network.*'` - Pass. 50 tests, 0 failures: ServerUrlTest 16, TokenAuthenticatorUnitTest 14, TokenAuthenticatorTest 10, AuthApiTokenRefresherTest 8, NetworkModuleGraphTest 2.

Full test suite:
- `cd android && ./gradlew ktlintCheck lintDebug testDebugUnitTest` - BUILD SUCCESSFUL, 96 tests, 0 failures, 0 errors.

Lint/type-check/build:
- ktlintCheck and lintDebug pass, as part of the command above.

`.taskcheck`:
- First run of `sh scripts/check.sh`: Fail. 1 of 870 server tests failed: `ClockConfigurationTests.the application clock is the system clock in UTC`. Its assertion was `2026-10-20T13:33:37.167663Z should be >= 2026-10-20T13:33:37.167663031Z`. That is a timing flake in `server/`: a microsecond-truncated clock is compared with a nanosecond `Instant.now()`. This task does not touch the server code.
- Second run: Pass, "== all checks passed" (web: 321 tests passed).

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
