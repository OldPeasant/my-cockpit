# Test Report

Date: 2026-10-20

Task: oauth-connections - OAuth connections for Google and Microsoft

Scope: base `45613885d7ee8e7f0f3d7c0ac9b3470170a6b73b` (claim commit) .. HEAD
`809a5538e17c27a8b3650ab2f037ee50ee50a4bb` on `main`. The task commits are `a6a5850b` (OAuth connect flow, token
provider, connection API, tests; re-applied from `wip/oauth-connections` onto current main) and `809a5538`
(regenerated `api/openapi.yaml` and the web API client). The parent supplied both, and they match
`git log 45613885..HEAD`. The working tree was clean (no uncommitted inputs).

This is a fresh pass for the re-applied revision. The previous report covered the pre-squash revision `dd2a7a6`. In
`git diff dd2a7a6 HEAD`, the OAuth production code, OAuth tests, `docs/oauth-setup.md` and the OAuth migration are
byte-identical. That diff touches only three migrations of other tasks that reached main in the meantime
(`core/V202610201400__core_items_link.sql`, `core/V202610201500__core_search_document.sql`,
`gtd/V202610201311__gtd_model.sql`). The OAuth tests apply them through Flyway, and the tests passed with them.

Production scope (`git diff --name-only 45613885..HEAD -- server/src/main web/src`):
`server/src/main/kotlin/ch/sonensei/cockpit/core/oauth/{api,domain,infra}/*.kt` (11 files),
`server/src/main/resources/db/migration/core/V202610201300__core_oauth.sql`, `application.properties`,
`application-dev.properties`, and generated files under `web/src/app/api/generated/`.

Requirements:
- `tasks/oauth-connections.md` acceptance criteria 1-5 and its ASSUMPTION notes
- `SPEC.md` §7.1 (OAuth apps, one connection per provider account), §4.1 (secrets at rest), §4.2 (ETag/If-Match)
- `reviews/oauth-connections/review.md` (CR-1) and `reviews/oauth-connections/fixes.md`
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`

## Test files changed

None in this pass. All OAuth tests already exist in `a6a5850b`, in
`server/src/test/kotlin/ch/sonensei/cockpit/core/oauth/` (`OAuthBoundaryWebTests.kt`, `OAuthConfigurationTests.kt`,
`OAuthConnectionsWebTests.kt`, `OAuthEdgeCaseWebTests.kt`, `OAuthFlowWebTests.kt`, `OAuthHttpClientTests.kt`,
`OAuthTokenProviderTests.kt`, `OAuthWebTestBase.kt`).

## Tests added or updated

Count: 0 (OAuth package: 60 tests in 7 classes; plus the existing `OpenApiContractTest`).

I checked the existing tests against each criterion the parent named. Each one has at least one meaningful, passing test
(see the table below):
- state mismatch rejected: an unknown state, a foreign session, the wrong provider, expiry, replay, no session cookie,
  empty parameters;
- refresh flow: refresh before expiry, refresh at half the lifetime, `expires_in` as a string, rotation, `invalid_grant`,
  other errors, concurrent refreshes;
- PKCE: the S256 challenge on `/start`, the verifier sent on the code exchange, a fresh verifier per start;
- encrypted refresh tokens: the stored bytes are not the plaintext and decrypt to the provider token;
- disconnect/revoke: If-Match 428/412, Google revocation, a failing revocation, no Microsoft call, the event, races with
  a refresh;
- connection list: status, never a token, the RECONNECT_REQUIRED ETag;
- `OAuthTokenProvider`: Graph and MAIL resources, not found, reconnect required.

The code under test is identical to the code the earlier passes covered, and the coverage counters are identical too. I
found no real gap, so I added no tests.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 `/start` with authorization code + PKCE, combined scopes | `OAuthFlowWebTests`: `start redirects to the provider with PKCE, state and the combined scopes`, `start asks Microsoft for Graph and Outlook mail scopes with offline access`, `callback exchanges the code with the PKCE verifier and stores the refresh token encrypted`, `Microsoft redeems the code for Graph only`; `OAuthEdgeCaseWebTests`: `every start uses a fresh state and PKCE challenge and cleans up expired flows`; `OAuthBoundaryWebTests`: `Google is asked for offline access with a consent prompt so it issues a refresh token` | F (below) | Pass |
| AC1/AC3 state bound to the session; state mismatch rejected | `OAuthFlowWebTests`: `a state that was never issued is rejected without a token request`, `a state from another browser session is rejected and consumed`, `a state is rejected at the other provider, after expiry and on replay`; `OAuthEdgeCaseWebTests`: `a callback without a session cookie is rejected and consumes the flow`, `empty state or code parameters are treated as missing`; `OAuthBoundaryWebTests`: `a flow is accepted just before its 10 minute lifetime ends and rejected exactly at it`, `the callback of an unknown provider is not found` | F | Pass |
| AC1 `/start` input validation and unconfigured apps | `OAuthFlowWebTests`: `start needs a browser session, a known provider and a purpose`; `OAuthConfigurationTests`: `starting a flow for an unconfigured provider is a conflict and stores nothing`, `a provider counts as configured only with both client id and secret` | F | Pass |
| AC1 refresh tokens encrypted | `OAuthFlowWebTests`: `callback exchanges the code ... stores the refresh token encrypted`; `OAuthEdgeCaseWebTests`: `the stored refresh token decrypts to the provider's token`, `a reconnect during a refresh keeps the new refresh token, not the one rotated from the old chain`; `OAuthTokenProviderTests`: `a rotated refresh token replaces the stored one without changing the version` | F | Pass |
| AC1/AC3 access tokens refreshed before expiry (refresh flow) | `OAuthTokenProviderTests`: `the access token of the callback is reused until shortly before it expires, then refreshed`; `OAuthEdgeCaseWebTests`: `a short-lived token is refreshed after half its lifetime`, `a refresh with expires_in as a string uses that lifetime`, `concurrent requests for an expired token refresh it once`, `concurrent Microsoft refreshes for Graph and mail are serialized so the second uses the rotated token`, `reconnecting with a new refresh token makes the next refresh use it` | F | Pass |
| AC2 `OAuthTokenProvider.accessToken(connectionId)` (+ MAIL resource); refresh errors | `OAuthTokenProviderTests` (all 5: reuse/refresh, rotation, `a revoked refresh token marks the connection reconnect required`, `other provider errors keep the connection and record the error`, `an unknown connection is reported as not found`); `OAuthEdgeCaseWebTests`: `a reconnect without a refresh token cannot repair a revoked connection`, `a refresh response without an access token or with a non-JSON error leaves the connection usable`; `OAuthBoundaryWebTests`: `a connection without a stored refresh token needs a reconnect and makes no token request` | F | Pass |
| AC2 revoke/disconnect endpoint with If-Match | `OAuthConnectionsWebTests`: `disconnect needs If-Match, revokes at Google and removes the connection`, `a failing revocation still disconnects, Microsoft is not called`; `OAuthEdgeCaseWebTests`: `disconnecting a revoked connection makes no revocation call and drops cached tokens`, `a disconnect during a refresh waits for it and leaves no cached token behind`; `OAuthBoundaryWebTests`: `the API shows a revoked connection as reconnect required with a new ETag` | F | Pass |
| AC2 connection list with status | `OAuthConnectionsWebTests`: `the list shows each connection with its status and never a token`, `providers tells which OAuth apps are configured`, `every OAuth endpoint needs a login`; `OAuthBoundaryWebTests`: `the API shows a revoked connection as reconnect required with a new ETag` | F | Pass |
| No secrets in logs/API (AGENTS.md, SPEC §4.1) | `OAuthEdgeCaseWebTests`: `no token, code, verifier, client secret or session token reaches the logs or the API`; `OAuthHttpClientTests`: `exception messages never contain tokens, response bodies or the client secret`; `OAuthConfigurationTests`: `toString never shows the client secret or tokens` | F | Pass |
| AC3 tests with WireMock as the authorization server | all `OAuth*WebTests` classes (WireMock in `OAuthWebTestBase`), `OAuthHttpClientTests` | F | Pass |
| AC3 `docs/oauth-setup.md` explains app registration and env vars | none (documentation) | none | Not testable (content review belongs to the code-reviewer) |
| AC4 `./gradlew test --tests 'ch.sonensei.cockpit.core.oauth.*'` passes (Postgres via Testcontainers) | 7 classes, 60 tests | F | Pass: 60 tests, 0 failures, 0 errors, 0 skipped |
| AC5 `api/openapi.yaml` regenerated; `OpenApiContractTest` passes without the flag | `ch.sonensei.cockpit.core.web.OpenApiContractTest` | F (run without `UPDATE_OPENAPI`) | Pass: 1 test, 0 failures |

F = `cd server && ./gradlew ktlintCheck test --rerun --tests 'ch.sonensei.cockpit.core.oauth.*' --tests '*OpenApiContractTest' jacocoTestReport`

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --rerun --tests 'ch.sonensei.cockpit.core.oauth.*' --tests '*OpenApiContractTest' jacocoTestReport`
(started 2026-10-20T14:02:22Z, `UPDATE_OPENAPI` not set)

Result:

Pass: BUILD SUCCESSFUL in 38s, ktlint clean, 61 tests, 0 failures, 0 errors, 0 skipped. Per class
(test-result XML of this run, timestamps 14:02:27Z to 14:02:58Z): OAuthBoundaryWebTests 5, OAuthConfigurationTests 5,
OAuthConnectionsWebTests 5, OAuthEdgeCaseWebTests 19, OAuthFlowWebTests 10, OAuthHttpClientTests 11,
OAuthTokenProviderTests 5, OpenApiContractTest 1.

## Full test execution

Command:

`sh scripts/check.sh` (the project's `.taskcheck`: TFP gate tests, server `./gradlew check`, web lint + tests + build,
Android ktlint + unit tests + lint), 2026-10-20T14:03:25Z to 14:07:17Z

Result:

Pass: "== all checks passed" (the script runs with `set -eu`, so it prints that only on success). Server: 1061 tests in
90 classes, 0 failures, 0 errors, 0 skipped (test-result XML of this run). Web: 22 files, 321 tests passed. Android
passed.

## .taskcheck

Result:

Pass (same run as above)

## Bugs found

No production defects found.

Earlier finding, for reference: `TW-1` (first pass): the OAuth test context exhausted Postgres connections in the full
server suite. It was fixed in test code (`@DirtiesContext` on `OAuthWebTestBase`) and is still fixed: the full suite of
1061 tests passes. Disposition: resolved, no production change.

Observation (not a defect under the current assumptions; unchanged): a connection that is `CONNECTED` but has no stored
refresh token can only arise from direct DB edits or a future restore without secrets. For such a connection,
`accessToken` throws `OAuthReconnectRequiredException`, but the API still shows `CONNECTED`. SPEC §14 expects restored
accounts to show "reconnect required", so the backup/restore task should set that status. The current behavior is
covered by `a connection without a stored refresh token needs a reconnect and makes no token request`. Disposition:
informational, for the backup/restore task.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not separately computed. Every Kotlin file in scope was added by the task (`git diff
45613885..HEAD` shows them as new, with no pre-existing lines), so changed-line coverage equals whole-file line coverage.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `oauth-connections`; base
  `45613885d7ee8e7f0f3d7c0ac9b3470170a6b73b`; HEAD `809a5538e17c27a8b3650ab2f037ee50ee50a4bb`; commits `a6a5850b`
  and `809a5538` came from the parent and match `git log 45613885..HEAD`; file scope from
  `git diff --name-only 45613885..HEAD -- server/src/main web/src`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `809a5538`, clean working tree. Per-file sha256 of
  the 11 `core/oauth` production files and 8 `core/oauth` test files was saved before the run and checked
  (`sha256sum -c`) after it: unchanged. For example `OAuthConnectionService.kt` `aa1ac73f...`, `OAuthTokenService.kt`
  `9c4603f9...`, `OAuthEdgeCaseWebTests.kt` `7458975a...`.
- Exact included files (under `server/src/main/kotlin/ch/sonensei/cockpit/core/oauth/`): `api/OAuthController.kt`,
  `domain/OAuthAuthorization.kt`, `domain/OAuthConnection.kt`, `domain/OAuthConnectionService.kt`,
  `domain/OAuthExceptions.kt`, `domain/OAuthScopes.kt`, `domain/OAuthTokenProvider.kt`, `domain/OAuthTokenService.kt`,
  `infra/OAuthHttpClient.kt`, `infra/OAuthProperties.kt`. Excluded:
  - `infra/OAuthRepositories.kt`: Spring Data interfaces, present in the report with zero counters, exercised through the
    Testcontainers tests.
  - `V202610201300__core_oauth.sql`: SQL cannot be instrumented; Flyway applies it in every Postgres test.
  - The `infra/Extensions.kt` report entry: the JaCoCo SMAP entry of the inlined jackson-module-kotlin `readValue`, not
    a task source file, zero counters.
  - `application*.properties`: configuration, exercised by the Spring context.
  - `web/src/app/api/generated/**`: generated client, guarded by `OpenApiContractTest` and the web build in
    `scripts/check.sh`.

  No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin (Gradle
  9.8.1, the plugin's default tool version); the focused command above, run in `server/`. Selection: the 7 OAuth classes
  plus `OpenApiContractTest`, so the hits come from the task's tests (plus the context startup of the contract test).
- Run start/completion time and exit status: started 2026-10-20T14:02:22Z, report written 14:03:02Z, BUILD SUCCESSFUL
  (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`,
  sha256 `4f6c2c5b63a2466ecf860f50c253d919b65bdd54c715536549c9f7e2d567a157`, mtime 14:03:02Z.
- Evidence outputs are fresh and inputs stayed unchanged: `--rerun` forced the test task, and the report mtime is after
  the run start. No other Gradle test run was active (`pgrep` showed only idle daemons). The source snapshot was
  unchanged after the run, and the report hash was still the same after `scripts/check.sh`, which does not run
  `jacocoTestReport`.
- Final run after latest test additions and production fixes: yes. It ran on HEAD `809a5538`, which includes the CR-1
  fix, and no tests or production code changed afterwards.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. "Statements" means JaCoCo
instructions; "Functions" means JaCoCo methods.

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

The aggregate excludes `infra/OAuthRepositories.kt` (zero counters), the SQL migration (cannot be instrumented), the
`Extensions.kt` SMAP entry, configuration and generated web files. No scoped Kotlin file is missing from the report. The
counters match the previous pass at `dd2a7a6` exactly, which is consistent with the code being byte-identical.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `domain/OAuthConnectionService.kt:112` (2 missed branches of `!error.isNullOrEmpty() \|\| code.isNullOrEmpty()`: an empty, non-null `error=` value) | Denied-consent handling | Justified gap: a non-empty error, a missing code and an empty code are tested. An empty `error=` together with a code behaves like a normal callback. (The previous report counted 1 missed branch here; JaCoCo shows 2, and the file total of 3 is unchanged.) |
| `domain/OAuthConnectionService.kt:217, 255, 257` | Companion init / MICROSOFT `label` in the unconfigured-provider 409 message | Justified gap: message text only; the Google variant is tested |
| `domain/OAuthTokenProvider.kt:66` (null branch of `checkNotNull(createdAt)`), `:78` | Defensive check; members generated for the data class | Not reachable for persisted entities; no test |
| `domain/OAuthConnection.kt:67, 90` | Unused `subject` getter / one branch of the `scopeList` filter lambda | Trivial; the subject is asserted through SQL |
| `domain/OAuthAuthorization.kt:27` | `stateHash` getter (the lookup goes through the repository query) | Trivial |
| `infra/OAuthHttpClient.kt:165-169` | Companion constants (method counter only; lines 100%) | Trivial |
| `infra/OAuthProperties.kt:15-17, 21-22` | Kotlin default-argument constructors / property getters | Trivial; binding is exercised by the Spring context and `OAuthConfigurationTests` |

## Unable to test

- `docs/oauth-setup.md` content (registering the Google and Azure apps, env vars): automated tests here cannot check
  documentation; left to the code-reviewer.
- Real Google/Microsoft endpoints: intentionally not called (WireMock only). The live connection is the human task in
  SPEC §15 step 7.
- The generated web client (`web/src/app/api/generated/**`): no behavior to unit-test. It compiles in the web build of
  `scripts/check.sh`, which passed.
- Same-account first connects racing each other (two callbacks for an account with no existing connection): outside
  CR-1. The unique (provider, subject) constraint guards the DB. Not tested; no follow-up needed for this task.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. The re-applied revision `809a5538` has OAuth
code and tests byte-identical to the previously tested `dd2a7a6`. Each acceptance criterion the parent named has passing
tests: state mismatch rejected, refresh flow, PKCE, encrypted refresh tokens, disconnect/revoke, connection list,
`OAuthTokenProvider`. No tests were added. The focused run passes (61 tests, including `OpenApiContractTest` without
`UPDATE_OPENAPI`), and `sh scripts/check.sh` passes (server 1061 tests, web 321 tests, Android). Whole-file JaCoCo
coverage of the 10 instrumented task files: 3381/3425 instructions (98.7%), 199/204 branches (97.5%), 501/504 lines
(99.4%).
