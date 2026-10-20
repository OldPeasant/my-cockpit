# Code Review

Date: 2026-10-20

Task: and-api-client - Android API client and auth plumbing

Scope: `git diff 84979c21..d0edbce2` (base 84979c21 = main at claim; task commits 26ee3d5a..748e7b62, merged in d0edbce2). This is the second review round. The reviewed revision is d0edbce20440da638f5e4cc7460a2660a7217a29. Only committed production code was reviewed. An uncommitted test change from the parallel test-writer (`TokenAuthenticatorTest.kt`) is outside this scope.

Requirements:
- `tasks/and-api-client.md`: acceptance criteria 1-4 and the six recorded assumptions
- `SPEC.md` §4.1 (Android refresh/access tokens) and §9.2 (Android client, server URL setting)
- `docs/ARCHITECTURE.md` (stack table, conventions) and `AGENTS.md` project notes
- `api/README.md`
- Earlier round: `reviews/and-api-client/fixes.md` (TW-1, TW-2, CR-1..CR-4)

Git range:
- Branch: main
- HEAD: d0edbce20440da638f5e4cc7460a2660a7217a29
- Base/start: 84979c21
- Commits reviewed: 7 task commits + 1 merge commit

Summary: 0 P0, 0 P1, 1 P2, 2 P3

## Earlier findings re-checked

- CR-1 / TW-1 / TW-2: fixed. `TokenRefresher.kt:37-49` maps `IOException`, `SerializationException`, `IllegalArgumentException` and `DateTimeException` to `Failed`. Nothing escapes to OkHttp's thread for the decoding failures the tests cover (malformed body, missing fields, bad UUID, bad date, unknown enum).
- CR-2: fixed. `ServerUrl.kt:79-91` logs out with `SERVER_CHANGED` under `AuthSession.refreshLock` before it stores a URL with a different origin. The logout runs outside the DataStore edit, so there is no deadlock with `ServerUrlInterceptor`'s read. `ServerUrlInterceptor` runs before `AccessTokenInterceptor`, so a request that reads the new URL cannot carry the old tokens. The remaining window (an in-flight 401 from the old server retried with a newly issued access token) is recorded as an assumption.
- CR-3: fixed. `TokenStore.replace` is a synchronized compare-and-set. `TokenAuthenticator` applies a refresh result, or logs out via `logoutIfCurrent`, only if the tokens are unchanged. Unit tests cover each interleaving.
- CR-4: fixed. `serverConfigDataStore` uses `ReplaceFileCorruptionHandler`, and `readablePrefs()` maps an `IOException` to empty preferences.

Checked without findings:
- Generation happens at build time only (`git ls-files android | grep -c generated/openapi` -> 0). Generated sources are excluded from ktlint, and the generated `ApiClient` is ignored.
- Cleartext: `ServerUrlValidator.CLEARTEXT_HOSTS` matches `network_security_config.xml`, and the base config denies cleartext. URLs with user info, a query or a fragment are rejected.
- The refresh uses a separate client without the token interceptor or authenticator, so there is no recursion. A caller-set `Authorization` header is kept.
- Nothing is logged, and `AuthTokens.toString()` is redacted.
- The stack table in `docs/ARCHITECTURE.md` and `api/README.md` are updated. The INTERNET permission is recorded as an assumption.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-5 | Concurrent 401s with a failing refresh repeat the refresh once per waiting request, one after another under the lock**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/core/network/TokenAuthenticator.kt:33-47`
    - Category: Correctness (resilience)
    - Problem: requests that get a 401 while a refresh runs wait on `session.refreshLock`. When that refresh returns `RefreshResult.Failed` (5xx, timeout, unreachable server, undecodable body), the tokens stay the same. Each waiting thread then finds `current.accessToken == sentToken` and starts its own refresh. N concurrent 401s therefore cause N refresh calls, one at a time. Each holds the lock for up to OkHttp's default connect/read timeouts (10 s each). Single-flight holds only for the `Success` and `Rejected` outcomes. No committed test covers concurrent 401s with a failing refresh. The test-writer's uncommitted `DIAG` test probes this case.
    - Why it matters: the refresh is not shared when the server is unhealthy, so a burst of N calls becomes N refresh calls. The burst fails only after about N times the timeout instead of one, and `setServerUrl` (which takes the same lock) is blocked for that whole time. Criterion 2 asks that concurrent requests share one refresh.
    - Suggested fix: record the outcome of the last refresh for the token pair it started from. For example, keep `lastFailed: AuthTokens?` (or a refresh generation counter) under the lock. A waiter whose `current` equals the pair that just failed then returns null without refreshing again. Clear the marker on the next successful save or replace. Add a MockWebServer test: 8 concurrent 401s, a slow 503 refresh, exactly 1 refresh call, tokens kept.

## P3 Low

- [ ] **CR-6 | `setServerUrl` can block the caller's thread for a whole refresh**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/core/network/ServerUrl.kt:84-86`
    - Category: Quality
    - Problem: the suspend function enters `synchronized(session.refreshLock)` on the caller's dispatcher. While a refresh holds the lock (a network call, see CR-5), the calling thread blocks instead of suspending. The settings screen will most likely call it from `Dispatchers.Main` (viewModelScope).
    - Why it matters: the UI freezes, with a risk of an ANR, when the user changes the server while a refresh hangs. Suspend functions are expected to be safe to call from the main thread.
    - Suggested fix: run the locked section in `withContext(Dispatchers.IO)`, or document that it is not main-safe. Fixing CR-5 shortens how long it can block.

- [ ] **CR-7 | No previous URL (unreadable settings) keeps the tokens on a server change**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/core/network/ServerUrl.kt:82-83`
    - Category: Security (hardening)
    - Problem: `previous` is null both when no URL was ever stored and when `readablePrefs()` turned an `IOException` into empty preferences (CR-4 path). In both cases no logout happens. If the read fails while the user is logged in and a different server is then saved, the tokens are kept and sent to the new host. This also applies once `and-login` persists tokens across a corrupt-file reset.
    - Why it matters: binding tokens to their issuing server (the CR-2 fix) depends on reading the previous URL. Being logged in without a known server origin is an anomaly, and it should not let the tokens go to the new host.
    - Suggested fix: when `previous` is null and `session.isLoggedIn`, log out as well (`SERVER_CHANGED`). This costs nothing in the normal first-setup case, where there are no tokens yet.

## Review conclusion

Material issues found; fix before completing the task. CR-5 is a P2 resilience gap in criterion 2's single-flight refresh. CR-6 and CR-7 are low-priority hardening. The earlier findings CR-1..CR-4, TW-1 and TW-2 are confirmed fixed.
