# Code Review

Date: 2026-10-20

Task: and-api-client - Android API client and auth plumbing

Scope: `git diff 75b80f4..HEAD` (task commits 486eb58, 0f64955, 8b3ff66), committed changes only

Requirements:
- `tasks/and-api-client.md` acceptance criteria 1-4 and the five recorded assumptions (`python tasks.py verify and-api-client`, `python tasks.py assumptions`)
- `SPEC.md` §4.1 (Android access/refresh tokens), §4.2 (API conventions), §9.2 (Android: Hilt, Retrofit from OpenAPI, server URL setting)
- `docs/ARCHITECTURE.md` (stack table, lint, conventions), `AGENTS.md` project notes, `api/README.md`
- `reviews/and-api-client/tests.md` (TW-1)

Git range:
- Branch: main
- HEAD: 8b3ff66
- Base/start: 75b80f438c06233efd23a12c3a6a3383e7b25a84
- Commits reviewed: 3

Summary: 0 P0, 1 P1, 3 P2, 0 P3

Checked without findings: generation is build-time only (`git ls-files android | grep -c generated/openapi` -> 0; output in `app/build/generated/openapi`); `:app:openApiGenerate` is UP-TO-DATE on a second run, and `./gradlew :app:openApiGenerate --rerun :app:ktlintCheck :app:lintDebug :app:compileReleaseKotlin` -> BUILD SUCCESSFUL with no implicit-dependency problems; the only Gradle deprecation (`Configuration.setVisible`) comes from AGP's `BasePlugin`, not from this change; generated `Serializer` uses `ignoreUnknownKeys = true`; generated paths are relative (`api/v1/...`), so the path-prefix rewrite in `ServerUrlInterceptor` works; the refresh call uses a separate client without the access-token interceptor and authenticator (no recursion, no cycle in the Hilt graph); the authenticator serializes refreshes under one lock in a `@Singleton`; nothing is logged and `AuthTokens.toString()` is redacted; the stack table and `api/README.md` are updated.

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | Malformed refresh response crashes the app (confirms TW-1); AC4 suite fails**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/core/network/TokenRefresher.kt:34-39`
    - Category: Correctness
    - Problem: `AuthApiTokenRefresher.refresh` only catches `IOException`. A 200 whose body cannot be decoded throws a kotlinx.serialization exception out of `TokenAuthenticator.authenticate`. Confirmed at HEAD: `./gradlew :app:testDebugUnitTest --tests 'ch.sonensei.cockpit.core.network.*'` -> 41 tests, 3 failed (the three TW-1 tests).
    - Why it matters: OkHttp rethrows the exception on its dispatcher thread, which kills the app process. AC4 cannot be verified while the suite fails.
    - Suggested fix: as in TW-1. Map decoding exceptions (`SerializationException`, `IllegalArgumentException`) to `RefreshResult.Failed`.

## P2 Medium

- [ ] **CR-2 | Tokens are not bound to the server they were issued by; changing the server URL sends them to the new host**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/core/network/ServerUrl.kt:70-74`, `AccessTokenInterceptor.kt:17`, `TokenAuthenticator.kt:36`
    - Category: Security
    - Problem: `ServerUrlRepository.setServerUrl` stores a new URL and leaves the `TokenStore` alone. `ServerUrlInterceptor` sends every request to whatever URL is stored at the moment, and `AccessTokenInterceptor` attaches the stored access token. If the new host answers 401, `TokenAuthenticator` sends the long-lived refresh token to that host as well.
    - Why it matters: a mistyped or malicious server URL, or simply moving to another server, leaks the device's refresh token (SPEC §4.1, a long-lived credential) to a third party. `and-login` only touches `auth/`, so this belongs to the network plumbing built here.
    - Suggested fix: bind the tokens to the server origin. Either clear the tokens or log out (`AuthSession.logout`) when the stored URL changes origin, or store the origin together with the tokens and attach or refresh only when the request's host matches it. Add a test for it.

- [ ] **CR-3 | The authenticator overwrites a logout or login that happens during a refresh**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/core/network/TokenAuthenticator.kt:38` and `:42`
    - Category: Correctness
    - Problem: after a refresh finishes, `tokenStore.save(result.tokens)` and `session.logout(REFRESH_REJECTED)` run without checking whether the stored tokens are still the ones the refresh started from. `AuthSession.logout(USER)` and a login's `TokenStore.save` do not take the authenticator's lock. Two consequences follow. A user logout during a successful refresh brings the tokens back (the device stays logged in after logout). A rejected stale refresh clears the tokens a fresh login has just saved.
    - Why it matters: "logout clears tokens" (SPEC §4.1; and-login builds on `AuthSession`) is not reliable while a request is refreshing. No test covers this interleaving.
    - Suggested fix: make the update conditional, e.g. `TokenStore.replaceIf(expected = current, new)` / `clearIf(expected = current)` (compare-and-set under the store's own lock), and log out only when the clear took effect. Add a unit test with a blocking fake refresher and a logout or save in between.

- [ ] **CR-4 | A corrupt server-config DataStore breaks every request and the settings save, with no way to recover**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/core/network/NetworkModule.kt:82`; `ServerUrl.kt:67`
    - Category: Correctness (resilience)
    - Problem: `PreferenceDataStoreFactory.create` has no `corruptionHandler`, and `serverUrl` does not handle the `IOException`/`CorruptionException` that `dataStore.data` throws. With an unreadable `server_config` file, every API call fails. `setServerUrl` fails too, because `edit` reads the file first. A UI collecting `serverUrl` without `catch` crashes.
    - Why it matters: stored state that is read back must not break startup or a save (review checklist). Here only reinstalling or clearing the app data recovers.
    - Suggested fix: pass `corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }`, and on `IOException` let `serverUrl` emit `emptyPreferences()` (null, i.e. "not configured"). Add a test with a corrupt file.

## P3 Low

None.

## Review conclusion

Material issues found; fix before completing the task.
