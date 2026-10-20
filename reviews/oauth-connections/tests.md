# Test Report

Date: 2026-10-20

Task: oauth-connections - OAuth connections for Google and Microsoft

Scope: base `f04232ba2f16cc2ebe3a23b7314ee3594d289736` .. HEAD `dd2a7a6274e77a6fcc51dffece486a8a6ab97aa6` on `main`
(task commits `ee544db`, `3000ab3`, `bf52f98`, `c53d042`, `dd2a7a6`, given by the parent and matching
`git log f04232b..HEAD`). This is the second pass, on the final revision. Since the first pass (written at the content of
`c53d042`), `dd2a7a6` changed `OAuthTokenService` (new `exclusive` per-connection lock helper; `accessToken` uses it) and
`OAuthConnectionService` (disconnect, and a reconnect of an existing connection, run under that lock; persisting moved
into a private `store` helper), and added two concurrency regression tests to `OAuthEdgeCaseWebTests` (fix for CR-1, see
`reviews/oauth-connections/fixes.md`). Production scope: `server/src/main/kotlin/ch/sonensei/cockpit/core/oauth/**` and
`server/src/main/resources/db/migration/core/V202610201300__core_oauth.sql`. The working tree was clean at measurement
time (no uncommitted inputs).

Requirements:
- `tasks/oauth-connections.md` acceptance criteria and its ASSUMPTION notes
- `SPEC.md` §7.1 (OAuth apps, one connection per provider account), §4.1 (secrets at rest), §14 backup note on
  "reconnect required"
- `reviews/oauth-connections/review.md` CR-1 and `reviews/oauth-connections/fixes.md` (the behavior changed in this pass)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`

## Test files changed

None in this pass. The test files of the first pass (`OAuthBoundaryWebTests.kt`, `@DirtiesContext` on
`OAuthWebTestBase.kt`) and the fixer's two regression tests in `OAuthEdgeCaseWebTests.kt` are already committed in
`c53d042` and `dd2a7a6`.

## Tests added or updated

Count: 0 in this pass (OAuth package: 60 tests in 7 classes at `dd2a7a6`).

Review of the changed behavior in `dd2a7a6`:

- Disconnect during a refresh: `a disconnect during a refresh waits for it and leaves no cached token behind`. The refresh
  is held in flight by a 1 s WireMock delay, and the test waits until the request has reached WireMock, so it holds the
  lock before the disconnect starts. This makes the ordering deterministic, not timing-dependent. It asserts the refresh
  completes and `accessToken(id)` then throws `OAuthConnectionNotFoundException` (no re-cached token).
- Reconnect during a refresh: `a reconnect during a refresh keeps the new refresh token, not the one rotated from the old
  chain`. It asserts the reconnect returns the same connection id, the stored refresh token decrypts to the reconnect's
  token and the cached access token is the reconnect's.
- The fixer showed that both tests fail with the lock removed (`fixes.md`, negative check).
- `existingId == null` path (first connect, no lock): every connect test, for example `OAuthFlowWebTests`
  `callback exchanges the code with the PKCE verifier ...`.
- `store` returns null inside the lock (reconnect of a revoked connection without a refresh token):
  `OAuthEdgeCaseWebTests` `a reconnect without a refresh token cannot repair a revoked connection`. Null without a
  connection: the `NO_REFRESH_TOKEN` test of a first connect.
- Disconnect 404/428/412 now throw inside the lock: `OAuthConnectionsWebTests`
  `disconnect needs If-Match, revokes at Google and removes the connection` (428, 412, 204, 404 afterwards) and
  `OAuthBoundaryWebTests` `the API shows a revoked connection ... with a new ETag` (412, then 204). In those tests the
  later requests and token calls on the same connection go through, so a lock released after an exception is
  implicitly checked. `withLock` releases it in a `finally` block.
- `accessToken` through `exclusive`: the existing concurrent-refresh tests (`concurrently(...)` in
  `OAuthEdgeCaseWebTests`) and all `OAuthTokenProviderTests`.

JaCoCo shows every line and branch added by `dd2a7a6` as covered (see Coverage). I found no gap in the new behavior that
needs another test, so I added none.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| `/start` with authorization code + PKCE, combined scopes | `OAuthFlowWebTests`: `start redirects to the provider with PKCE, state and the combined scopes`, `start asks Microsoft for Graph and Outlook mail scopes with offline access`, `callback exchanges the code with the PKCE verifier ...`; `OAuthEdgeCaseWebTests`: `every start uses a fresh state and PKCE challenge ...`; `OAuthBoundaryWebTests`: `Google is asked for offline access ...` | `cd server && ./gradlew ktlintCheck test --rerun --tests 'ch.sonensei.cockpit.core.oauth.*' jacocoTestReport` | Pass |
| State bound to the session; state mismatch rejected | `OAuthFlowWebTests`: `a state that was never issued ...`, `a state from another browser session ...`, `a state is rejected at the other provider, after expiry and on replay`; `OAuthEdgeCaseWebTests`: `a callback without a session cookie ...`, `empty state or code parameters ...`; `OAuthBoundaryWebTests`: `a flow is accepted just before its 10 minute lifetime ends ...`, `the callback of an unknown provider is not found` | same | Pass |
| `/start` input validation (session, provider, purpose, unconfigured app) | `OAuthFlowWebTests`: `start needs a browser session, a known provider and a purpose`; `OAuthConfigurationTests`: `starting a flow for an unconfigured provider is a conflict ...` | same | Pass |
| Refresh tokens encrypted | `OAuthFlowWebTests`: `callback exchanges the code ... stores the refresh token encrypted`; `OAuthEdgeCaseWebTests`: `the stored refresh token decrypts to the provider's token`, Microsoft rotation test, `a reconnect during a refresh keeps the new refresh token ...`; `OAuthTokenProviderTests`: `a rotated refresh token replaces the stored one ...` | same | Pass |
| Access tokens refreshed before expiry | `OAuthTokenProviderTests`: `the access token of the callback is reused until shortly before it expires, then refreshed`; `OAuthEdgeCaseWebTests`: `a short-lived token is refreshed after half its lifetime`, `a refresh with expires_in as a string ...`, concurrent refresh tests | same | Pass |
| `OAuthTokenProvider.accessToken(connectionId)` for modules; `invalid_grant` -> RECONNECT_REQUIRED | `OAuthTokenProviderTests` (all 5); `OAuthEdgeCaseWebTests`: `a reconnect without a refresh token cannot repair a revoked connection`, `a refresh response without an access token ...`; `OAuthBoundaryWebTests`: `a connection without a stored refresh token ...` | same | Pass |
| Revoke/disconnect endpoint with If-Match | `OAuthConnectionsWebTests`: `disconnect needs If-Match, revokes at Google and removes the connection`, `a failing revocation still disconnects, Microsoft is not called`; `OAuthEdgeCaseWebTests`: `disconnecting a revoked connection makes no revocation call ...`, `a disconnect during a refresh waits for it and leaves no cached token behind`; `OAuthBoundaryWebTests`: `the API shows a revoked connection ... with a new ETag` | same | Pass |
| CR-1 fix: disconnect/reconnect serialized with an in-flight refresh | `OAuthEdgeCaseWebTests`: `a disconnect during a refresh waits for it and leaves no cached token behind`, `a reconnect during a refresh keeps the new refresh token, not the one rotated from the old chain` | same | Pass |
| Connection list with status | `OAuthConnectionsWebTests`: `the list shows each connection with its status and never a token`, `providers tells which OAuth apps are configured`; `OAuthBoundaryWebTests`: `the API shows a revoked connection as reconnect required ...` | same | Pass |
| Tests with WireMock as the authorization server (state mismatch rejected, refresh flow) | all `OAuth*WebTests` classes (WireMock in `OAuthWebTestBase`), `OAuthHttpClientTests` | same | Pass |
| `./gradlew test --tests 'ch.sonensei.cockpit.core.oauth.*'` passes (Postgres via Testcontainers) | 7 classes, 60 tests (`PostgresTestBase`) | same | Pass (60 tests, 0 failures, 0 errors, 0 skipped) |
| `docs/oauth-setup.md` explains app registration and env vars | none (documentation) | none | Not testable (content review belongs to the code-reviewer) |
| `api/openapi.yaml` regenerated, `OpenApiContractTest` passes | `OpenApiContractTest` (existing) | `sh scripts/check.sh` (full server `check`) | Pass (part of the 865 server tests) |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --rerun --tests 'ch.sonensei.cockpit.core.oauth.*' jacocoTestReport`

Result:

Pass: BUILD SUCCESSFUL, ktlint clean, 60 tests, 0 failures, 0 errors, 0 skipped. Per class: OAuthBoundaryWebTests 5,
OAuthConfigurationTests 5, OAuthConnectionsWebTests 5, OAuthEdgeCaseWebTests 19, OAuthFlowWebTests 10,
OAuthHttpClientTests 11, OAuthTokenProviderTests 5.

## Full test execution

Command:

`sh scripts/check.sh` (the project's `.taskcheck`: tfp gate tests, server ktlint + all tests, web lint + tests + build,
android ktlint + unit tests + lint), run from the repo root, 2026-10-20T13:20:54Z to 13:23:17Z

Result:

Pass: exit 0, "== all checks passed". Server: 865 tests, 0 failures, 0 errors (test-result XML of this run,
including `OpenApiContractTest` and all 7 OAuth classes). Web: 275 tests passed, build complete. Android passed. The OAuth
context did not exhaust Postgres connections again (TW-1 from the first pass stays fixed).

## .taskcheck

Result:

Pass (same run as above)

## Bugs found

No production defects found in this pass.

First-pass finding, for reference: `TW-1`, the OAuth test context exhausted Postgres connections in the full server
suite. It was fixed in test code (`@DirtiesContext` on `OAuthWebTestBase`, committed in `c53d042`). No production fix
needed. The full suite passes at `dd2a7a6`.

Observation (not a defect under the current assumptions; unchanged since the first pass): a connection that is
`CONNECTED` but has no refresh token (only possible through direct DB edits or a future restore without secrets) makes
`accessToken` throw `OAuthReconnectRequiredException` while the API still shows status `CONNECTED`. SPEC §14 expects
restored accounts to show "reconnect required", so the backup/restore task should set that status on restore. Covered by
`a connection without a stored refresh token needs a reconnect and makes no token request`.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not separately computed. Every file in scope was added by the task (no pre-existing lines), so it
equals whole-file line coverage. For the `dd2a7a6` delta in particular, I checked the source-mapped JaCoCo lines of
`OAuthTokenService.kt` 39-53 and `OAuthConnectionService.kt` 125-167 and 185-201: all covered, no missed branches.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `oauth-connections`; base
  `f04232ba2f16cc2ebe3a23b7314ee3594d289736`; HEAD `dd2a7a6274e77a6fcc51dffece486a8a6ab97aa6`; commits `ee544db`,
  `3000ab3`, `bf52f98`, `c53d042`, `dd2a7a6` and the production scope came from the parent and match
  `git log f04232b..HEAD` (all `core/oauth` files are new in the task).
- Source/test snapshot identifier (including uncommitted inputs): HEAD `dd2a7a6`, clean working tree. sha256 over the
  sha256 list of `server/src/main/kotlin/ch/sonensei/cockpit/core/oauth/*/*.kt` and
  `server/src/test/kotlin/ch/sonensei/cockpit/core/oauth/*.kt`:
  `0fcc70a3ec0229c13ecc903a67e6b22273a238ed053e6bbc3c10146cf4f5eda8`, the same before and after the run.
- Exact included files: `api/OAuthController.kt`, `domain/OAuthAuthorization.kt`, `domain/OAuthConnection.kt`,
  `domain/OAuthConnectionService.kt`, `domain/OAuthExceptions.kt`, `domain/OAuthScopes.kt`,
  `domain/OAuthTokenProvider.kt`, `domain/OAuthTokenService.kt`, `infra/OAuthHttpClient.kt`, `infra/OAuthProperties.kt`
  (under `server/src/main/kotlin/ch/sonensei/cockpit/core/oauth/`). Excluded: `infra/OAuthRepositories.kt` (Spring Data
  interfaces, no bytecode, zero counters; exercised through the Testcontainers tests), `V202610201300__core_oauth.sql`
  (SQL, cannot be instrumented; Flyway applies it in every Postgres test), and the `infra/Extensions.kt` entry in the
  report (JaCoCo SMAP entry of the inlined jackson-module-kotlin `readValue`, not a task source file, zero counters).
  `application*.properties` is configuration. No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin (Gradle
  9.8.1, the plugin's default tool version, not overridden in `build.gradle.kts`);
  `./gradlew ktlintCheck test --rerun --tests 'ch.sonensei.cockpit.core.oauth.*' jacocoTestReport` in `server/`.
  Selection: only the 7 OAuth test classes, so all hits come from the task's tests.
- Run start/completion time and exit status: started 2026-10-20T13:19:10Z, test classes ran 13:19:23Z to 13:19:50Z,
  report written 13:19:53Z; BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`
  sha256 `1bf299f26a467acab3e2bb6551fbd004cddf9b4c17da229332fbaff05aecfdf8`.
- Evidence outputs are fresh and inputs stayed unchanged: `--rerun` forced the test task, and the report timestamp is
  after the run start. The test-result XML timestamps of that run cover exactly the 7 OAuth classes, and the snapshot
  hash was the same before and after. Note: a separate Gradle test run in this clone (started by another process at
  about 13:20:02Z, `test --tests ch.sonensei.cockpit.core.oauth.* --tests *OpenApiContractTest`) started after this
  report was written. It truncated `server/build/jacoco/test.exec` but did not rewrite the XML report: the hash was
  unchanged after it and after `scripts/check.sh`. The numbers below come from the XML report of my run. I waited for
  that run to finish before I started `scripts/check.sh`.
- Final run after latest test additions and production fixes: yes. It ran on `dd2a7a6`, which includes the CR-1 fix,
  and no tests were added after it.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. "Statements" means JaCoCo
instructions.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `api/OAuthController.kt` | 376/376 (100.0%) | 8/8 (100.0%) | 24/24 (100.0%) | 72/72 (100.0%) |
| `domain/OAuthAuthorization.kt` | 54/57 (94.7%) | 2/2 (100.0%) | 8/9 (88.9%) | 15/16 (93.8%) |
| `domain/OAuthConnection.kt` | 246/250 (98.4%) | 1/2 (50.0%) | 27/28 (96.4%) | 39/40 (97.5%) |
| `domain/OAuthConnectionService.kt` | 971/975 (99.6%) | 73/76 (96.1%) | 35/36 (97.2%) | 127/128 (99.2%) |
| `domain/OAuthExceptions.kt` | 129/129 (100.0%) | N/A | 20/20 (100.0%) | 24/24 (100.0%) |
| `domain/OAuthScopes.kt` | 187/187 (100.0%) | 8/8 (100.0%) | 4/4 (100.0%) | 29/29 (100.0%) |
| `domain/OAuthTokenProvider.kt` | 125/137 (91.2%) | 1/2 (50.0%) | 19/20 (95.0%) | 31/31 (100.0%) |
| `domain/OAuthTokenService.kt` | 506/506 (100.0%) | 26/26 (100.0%) | 28/28 (100.0%) | 52/52 (100.0%) |
| `infra/OAuthHttpClient.kt` | 664/672 (98.8%) | 70/70 (100.0%) | 12/16 (75.0%) | 92/92 (100.0%) |
| `infra/OAuthProperties.kt` | 123/136 (90.4%) | 10/10 (100.0%) | 13/16 (81.2%) | 20/20 (100.0%) |
| **Aggregate (summed counters, 10 files)** | 3381/3425 (98.7%) | 199/204 (97.5%) | 190/201 (94.5%) | 501/504 (99.4%) |

The aggregate excludes `infra/OAuthRepositories.kt` (no bytecode), the SQL migration (cannot be instrumented) and the
`Extensions.kt` SMAP entry. No scoped file is missing from the report. Compared with the first pass (3298/3342
instructions, 193/198 branches, 184/195 methods, 492/495 lines), the counters grew only by the `dd2a7a6` code, and all of
it is covered.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `domain/OAuthTokenService.kt:39-53` (`exclusive`, `accessToken` through it) | CR-1 lock shared by refresh, disconnect and reconnect | Covered (26/26 branches, 28/28 methods); regression tests from `dd2a7a6` plus the concurrent-refresh tests |
| `domain/OAuthConnectionService.kt:125-167, 185-201` (reconnect under the lock, `store`, disconnect under the lock) | CR-1: no re-cached token after a disconnect, no overwrite of a newer grant | Covered, no missed lines or branches; tests listed under "Tests added or updated" |
| `domain/OAuthConnectionService.kt:112` (1 branch of `!error.isNullOrEmpty() \|\| code.isNullOrEmpty()`: empty `error` with a code) | Denied consent handling | Justified gap: empty and non-empty error, with and without a code, are already tested. The remaining combination (an empty `error=` plus a code) behaves like a normal callback |
| `domain/OAuthConnectionService.kt:217, 255, 257` | Companion init / `label` of MICROSOFT in the 409 message | Justified gap: message text of the unconfigured-provider conflict; the Google variant is tested |
| `domain/OAuthTokenProvider.kt:66` (null branch of `checkNotNull(createdAt)`), `:78` | Defensive check; members generated for the data class | Not reachable for persisted entities; no test |
| `domain/OAuthConnection.kt:67, 90` | Unused `subject` getter / one branch of the `scopeList` filter lambda | Trivial; the subject is asserted through SQL |
| `domain/OAuthAuthorization.kt:27` | `stateHash` getter (the lookup goes through the repository query) | Trivial |
| `infra/OAuthHttpClient.kt:165-169` | Companion constants (methods counter only; lines 100%) | Trivial |
| `infra/OAuthProperties.kt:15-17, 21-22` | Kotlin default-argument constructors / property getters | Trivial; binding is exercised by the Spring context and `OAuthConfigurationTests` |

## Unable to test

- `docs/oauth-setup.md` content (registering the Google and Azure apps, env vars): documentation that automated tests
  here cannot check; left to the code-reviewer.
- Real Google/Microsoft endpoints: intentionally not called (WireMock only). The live connection is the human task in
  SPEC §15 step 7.
- The first pass listed "a refresh racing a disconnect" as untestable. `dd2a7a6` fixed it, and it is now covered
  deterministically by the two regression tests: WireMock holds the refresh in flight, and the test waits until the
  request has arrived before it acts.
- Same-account first connects racing each other (two callbacks for an account with no existing connection run `store`
  without the lock): outside CR-1, which is about refresh vs. disconnect/reconnect. The unique (provider, subject)
  constraint guards the DB. Not tested; no follow-up needed for this task.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. The CR-1 fix in `dd2a7a6` (per-connection
lock shared by refresh, disconnect and reconnect) is covered by the fixer's two deterministic regression tests and by the
existing disconnect/reconnect/refresh tests. JaCoCo shows all of its lines and branches as covered. No tests were added in
this pass. `./gradlew ktlintCheck test --rerun --tests 'ch.sonensei.cockpit.core.oauth.*' jacocoTestReport` passes
(60 tests), and `sh scripts/check.sh` passes (server 865 tests, web 275 tests, android).
