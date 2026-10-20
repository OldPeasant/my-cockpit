# Fix Report

Date: 2026-10-20

Task: oauth-connections - OAuth connections for Google and Microsoft

Scope: CR-1 from `reviews/oauth-connections/review.md`. The parent worker accepted CR-2 and CR-3 as they are and told me not to fix them.

## Fixed findings

### CR-1 - Token refresh is not coordinated with disconnect and reconnect

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/oauth/domain/OAuthTokenService.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/core/oauth/domain/OAuthConnectionService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/oauth/OAuthEdgeCaseWebTests.kt`

Problem:
`refresh` runs under a per-connection lock, but `disconnect` and `complete` did not take that lock. A disconnect could call `forget` while a refresh was still running. The refresh then put a new access token back into the cache for the deleted connection. A reconnect during a refresh could also have its new refresh token overwritten by the token that `recordRefresh` rotated from the old chain.

Fix:
- `OAuthTokenService` has a new `internal fun <T> exclusive(connectionId, block)`. It runs the block under the same `ReentrantLock` per connection that `accessToken` uses. `accessToken` now calls `exclusive` too, so its behavior is unchanged.
- `disconnect` runs the delete transaction and `tokens.forget(id)` inside `exclusive(id)`. Revocation stays outside the lock.
- `complete` first looks up the id of the existing connection for the provider account. If there is one, it runs the persist transaction, `forget` and `remember` inside `exclusive(existingId)`. A new connection has no refresh in flight, so it is stored without the lock. The persist logic moved unchanged into a private `store(...)` helper.
- I also removed one `!!` in `disconnect`. The compiler flagged it as unnecessary once the code moved into the lambda.

Regression tests (in `OAuthEdgeCaseWebTests`, using WireMock with a 1 s fixed delay on the refresh response; each test waits until the refresh request has reached WireMock before it acts):
- `a disconnect during a refresh waits for it and leaves no cached token behind`: after the disconnect, `accessToken(id)` throws `OAuthConnectionNotFoundException`.
- `a reconnect during a refresh keeps the new refresh token, not the one rotated from the old chain`: the stored refresh token decrypts to the reconnect's token, and the cached access token is the reconnect's.

To check that the tests catch the bug, I temporarily made `exclusive` a plain `block()` call with no lock and ran `./gradlew test --tests 'ch.sonensei.cockpit.core.oauth.OAuthEdgeCaseWebTests'`. Both new tests failed: the disconnect test got no exception, and the reconnect test found the rotated token stored. The two existing concurrency tests failed as well, plus the log test, which fails as a side effect because WireMock's verification output includes request bodies. With the lock restored, all of them pass.

Validation:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.oauth.*'` - Pass (60 tests, 0 failures, 0 skipped)

## Not fixed

### CR-2 - ID token claims are used without checking `iss` and `aud`

Status:
Out of scope

Reason:
The parent worker accepted this finding as it is and told me not to fix it.

### CR-3 - Microsoft connections report only the Graph scopes as granted

Status:
Out of scope

Reason:
The parent worker accepted this finding as it is and told me not to fix it.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.oauth.*'` - Pass: OAuthConfigurationTests 5, OAuthTokenProviderTests 5, OAuthBoundaryWebTests 5, OAuthHttpClientTests 11, OAuthEdgeCaseWebTests 19, OAuthFlowWebTests 10, OAuthConnectionsWebTests 5. All passed.
- Negative check (lock temporarily removed, then restored): `./gradlew test --tests 'ch.sonensei.cockpit.core.oauth.OAuthEdgeCaseWebTests'` - failed as expected, which shows the regression tests catch the bug

Full test suite:
- Not run. The parent asked for the focused OAuth run only.

Lint/type-check/build:
- `ktlintCheck` - Pass. Two compiler warnings about unnecessary `!!` remain (`OAuthConnectionService.list`, `OAuthTokenService.refresh`). Both were there before this fix.

`.taskcheck`:
- Not run. The parent worker runs it.

Remaining unresolved findings:
0 (CR-2 and CR-3 were accepted by the parent and are not to be fixed)

## Final status

All validated findings fixed and validation passed.
