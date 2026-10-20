# Code Review

Date: 2026-10-20

Task: and-api-client - Android API client and auth plumbing

Scope: `git diff da92d052..28b0d42d -- android api docs` (da92d052 = `git merge-base HEAD origin/main`; task commits 26ee3d5a..c44b15b0, latest main merged in 28b0d42d). This is the third review round. The reviewed revision is 28b0d42d. The focus is the round-2 fixes in c44b15b0 (CR-5, CR-6, CR-7) plus a9650e58, with the whole task change re-checked against the acceptance criteria. The merge commit 28b0d42d only touches `tasks/*.md` (checked with `git diff c44b15b0 28b0d42d --stat`). The working tree was clean when the review started.

Requirements:
- `tasks/and-api-client.md`: acceptance criteria 1-4 and the recorded assumptions
- `tasks/and-login.md` (the consumer of `setServerUrl` and `TokenStore`, used to judge CR-7)
- `SPEC.md` §4.1 and §9.2, `docs/ARCHITECTURE.md`, `AGENTS.md` project notes
- Earlier rounds: `reviews/and-api-client/fixes.md`, the round-2 `review.md`

Git range:
- Branch: main
- HEAD: 28b0d42d
- Base/start: da92d0527f5bb04b8cee5f31debd6af7f8f55e85
- Commits reviewed: 9 task commits + 2 merge commits (d0edbce2, 28b0d42d)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Earlier findings re-checked

- **CR-5 (= TW-3): fixed.** See `TokenAuthenticator.kt:29-33, 43, 47, 59-63`.
  - Both `failedRefreshes++` and `failedFrom = current` are written under `session.refreshLock`. The counter is `@Volatile`, so the read before the lock (line 43) sees every committed failure. `failedFrom` is read and written only under the lock.
  - The interleavings work out as follows:
    - Waiter A reads the counter, then a refresh from pair T fails while A waits. A finds `current == T == failedFrom` with a changed counter and gives up. This is the intended shared failure.
    - A newer 401 reads the counter after the failure, sees no change, and refreshes again. This matches the documented behavior.
    - After the failure, a successful refresh or a new login replaces T. A's `current.accessToken != sentToken`, so A retries with the stored token, unaffected by the guard.
    - A second failure from T while A waits still matches `failedFrom`. A failure from another pair cannot reach line 47 for A, because A's sent token equals `current.accessToken`.
  - A request can give up only because of a failure that finished after its own 401 reached the authenticator. That failed refresh was then running, or was queued ahead of it, for the same pair. So a request is never refused because of an unrelated, older failure.
  - Success, Rejected and the CAS paths are unchanged.
  - The MockWebServer test (8 synchronous calls, slow 503 refresh, then a working refresh) checks exactly 1 and then 2 refresh calls. `fixes.md` reports that it fails with the guard disabled (8 calls).
- **CR-6: fixed.** See `ServerUrl.kt:90-94`. The `synchronized` block runs inside `withContext(Dispatchers.IO)`, so the caller's thread suspends instead of blocking.
  - If the caller is cancelled while it waits, the logout still happens when the lock is acquired. `withContext` then throws on exit, so the new URL is not stored. The result is logged out with the old URL, which is the safe direction.
  - The test `a server change during a running refresh does not block the calling thread` shows that a single-thread caller dispatcher stays free while the change waits.
- **CR-7: fixed, and it does not log out wrongly on first setup.** See `ServerUrl.kt:88`.
  - The logout now also runs when `previous == null`, but only if `session.isLoggedIn`.
  - On a first setup there are no tokens. `ServerUrlInterceptor` throws `No server URL configured` without a stored URL (`ServerUrl.kt:127`), so a login request cannot even be sent before the URL is stored. The flow `and-login` describes (server URL + credentials on one login screen) therefore has to store the URL before it gets tokens, and CR-7 then does nothing.
  - The only way to be logged in without a stored URL is unreadable or replaced settings, or tokens that were restored without their settings. Logging out is the correct hardening there.
  - The KDoc (`ServerUrl.kt:79-80`) states this contract for `and-login`.
  - The test change (the first URL is now set before the login) matches the real flow and is not a weakened assertion: the same-origin and invalid-URL checks are kept, and a new test covers both null-previous cases (corrupt file, empty store).

## Acceptance criteria

1. Build-time generation: `android/app/build.gradle.kts` generates Retrofit2 + kotlinx.serialization sources from `../api/openapi.yaml` into the build directory. No generated sources are tracked (unchanged since round 2, already confirmed there).
2. Interceptor and Authenticator: `AccessTokenInterceptor` adds the bearer token. `TokenAuthenticator` refreshes once per 401 (`priorResponse` guard), and concurrent 401s share one refresh for Success, Rejected and now also Failed. A rejected refresh logs out (`logoutIfCurrent`). MockWebServer tests cover each case.
3. Server URL: stored in DataStore and validated (https only, http only for `CLEARTEXT_HOSTS`, and the list matches `network_security_config.xml`, checked by `NetworkSecurityConfigTest`). The URL is rewritten per request.
4. Tests: `core.network.*` covers token injection, single refresh for concurrent 401s (success, rejected, failed), and logout on refresh failure. I did not run Gradle in this round, to avoid contention with the parallel test-writer. The pass results come from `fixes.md` and the test-writer, and the parent worker must record its own run with `tasks.py verify`.

Checked without findings: no logging of tokens (`AuthTokens.toString()` is redacted), the refresh client has no authenticator or token interceptor, and the docs (stack table, `api/README.md`) are updated. No secrets are committed.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. CR-5, CR-6 and CR-7 are correctly fixed in 28b0d42d without introducing races or a wrongful logout on first setup. The earlier findings CR-1..CR-4, TW-1 and TW-2 are still fixed.
