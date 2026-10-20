# Code Review

Date: 2026-10-20

Task: p0-auth-tokens - Device tokens for the Android app (review pass 2, final code)

Scope: committed task changes `git diff 3f4cd73..dbe082a` (excluding `tasks/`): production code in `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/` (DeviceTokenController, AuthDevice, DeviceTokenService, the LoginService refactor, AuthDeviceRepository, BearerAuthenticationFilter, SecurityConfiguration, SessionAuthenticationFilter, AuthController), migration `server/src/main/resources/db/migration/core/V202610201200__core_auth_device.sql`, tests `DeviceToken{Unit,Web,EdgeCase}Tests.kt`, `api/openapi.yaml`, `web/src/app/api/generated/`, and the reports `reviews/p0-auth-tokens/tests.md` and `fixes.md`. Pass 2 concentrates on what changed since pass 1: the rebase onto 3f4cd73 (with `api/openapi.yaml` and the generated client regenerated after a conflict) and the new test in dbe082a. The full production code was reviewed again for merge effects.

Requirements:
- `tasks/p0-auth-tokens.md` acceptance criteria and recorded assumptions (opaque access tokens, 90-day refresh lifetime, revoke = row delete, DELETE without If-Match, token endpoints share the login throttle and are CSRF-exempt, any bearer header disables cookie authentication, no passkey on the token endpoint)
- SPEC.md §4.1 and §4.2
- docs/ARCHITECTURE.md, docs/adr/0001-web-sessions-in-database.md, api/README.md, docs/QUALITY.md
- AGENTS.md project notes (module packages, migration naming, no secrets in logs, generated OpenAPI/clients, fictitious test data)

Git range:
- Branch: main
- HEAD: dbe082adb28178a55df8b1efb0e92beae0611fca
- Base/start: 3f4cd73 (merge-base with origin/main after the rebase)
- Commits reviewed: 5 (286d9e6 implementation, 74c6239 test-writer pass 1 tests, 1e51c46 TW-1 fix, 0e5d6b3 review report pass 1, dbe082a test-writer pass 2)
- Uncommitted changes: none (`git status --short` was empty before and after the commands below)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Commands run

- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*' --tests '*OpenApiContractTest'` -> BUILD SUCCESSFUL. 19 result files, 166 tests, 0 failures, 0 errors, 0 skipped (Postgres via Testcontainers). The new test `a header that skips CSRF never lets the session cookie write, and another scheme still needs the CSRF token` ran and passed. `OpenApiContractTest` passed without `UPDATE_OPENAPI`, and `api/openapi.yaml` was unchanged afterwards.
- `cd web && npm run api:check` -> `src/app/api/generated is up to date.`
- `git range-diff 49c711e..06ad48e 3f4cd73..1e51c46`: commits 2 and 3 are identical. Commit 1 differs only in `api/openapi.yaml` and the generated client, where it is now interleaved with the upstream Events/SSE additions. A byte comparison of the `server/src` diffs before and after the rebase differs only in the `SecurityConfiguration.kt` blob ids and hunk offsets. Upstream (220044c, p0-events-sse) had added `DispatcherType.ASYNC` to the permitted dispatcher types.

## Rebase and regeneration consistency

- `api/openapi.yaml` (diff against base): adds the four operations (`issueDeviceToken`, `refreshDeviceToken`, `listDevices`, `revokeDevice`) and the schemas `TokenRequest`, `RefreshRequest`, `TokenResponse` and `DeviceResponse`. The upstream `/api/v1/events` path and the `ChangeEvent` schema are kept. The Auth tag description changed to "Web login sessions and device tokens". The tag now comes after Events, which is the generator's output order, and the contract test confirms the file matches the running application. Nothing was merged by hand: the file is exactly what the contract test generates.
- `web/src/app/api/generated/`: `fn/auth/` contains the four new functions next to login/logout/getCurrentUser. `fn/events/stream-events.ts` and `models/change-event.ts` from upstream are present. `functions.ts`, `index.ts` and `models.ts` export both sets. `api:check` confirms there is no drift against `api/openapi.yaml`.
- Merged `SecurityConfiguration.kt`: the upstream ASYNC dispatcher permit and this task's changes (token paths permitted and CSRF-exempt, `ignoringRequestMatchers(BearerAuthenticationFilter.withBearerHeader)`, bearer filter before `CsrfFilter`) sit side by side without interfering. The ASYNC dispatch that ends an SSE stream follows an original request that was already authenticated, by bearer or by cookie, so bearer access to `/api/v1/events` follows the same rules as any other `/api/**` endpoint.

## New test (dbe082a)

`DeviceTokenEdgeCaseTests` gets `a header that skips CSRF never lets the session cookie write, and another scheme still needs the CSRF token`. It checks the security consequence of the TW-1 fix:
- a valid session cookie plus an empty `Authorization: Bearer ` header on `DELETE /devices/{id}` without a CSRF token gives 401: CSRF is skipped, but the cookie does not authenticate;
- a valid session cookie plus a `Basic` header without a CSRF token gives 403: CSRF stays on;
- `deviceCount() shouldBe 1` afterwards, so neither request revoked anything.

The test asserts behavior (status codes and persisted state), not implementation details. It uses a real write endpoint, and it would fail if a later change let the cookie authenticate next to a bearer header, or widened the CSRF exemption to other schemes. The Basic credential in it is fictitious test data. It fits the style and helpers of the file (`tokens(issue())`, `webLogin()`, `deviceCount()`).

## Verification against acceptance criteria

1. Token issue and rotation, with reuse detection: implemented in `DeviceTokenService` / `DeviceTokenController`. Only SHA-256 hashes are stored. Refresh locks the row, retires the old hash and rotates both tokens. Reuse of a retired token deletes the device, and concurrent refreshes count as a reuse. Covered by the web and edge-case tests that passed above. Met.
2. Bearer alongside the session, with no CSRF for bearer requests: `BearerAuthenticationFilter`. The session filter ignores the cookie whenever a bearer header is present, and CSRF ignores bearer-header requests. Tested, including the new combined CSRF-skip test. Met.
3. Device list and revoke, with revoked tokens answering 401: tested on both reads and writes. Met.
4. `./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` passes with Testcontainers Postgres (run above). Met.
5. `api/openapi.yaml` was regenerated and `OpenApiContractTest` passes without the flag (run above). The generated web client is consistent (`api:check`). Met.

## Focus areas checked without findings

- Secrets: the request, response and token value classes redact themselves in `toString`. Logs contain only device ids. Token responses are `no-store`. A log-capture test exists. No credentials are committed.
- Migration: a new file following the naming convention, in `db/migration/core/`. It has the BaseEntity columns, unique hash indexes and cascading deletes. No applied migration was edited.
- Architecture: the code stays inside `core.auth.{api,domain,infra}` and follows the ADR 0001 pattern of hashed opaque tokens. The web login behavior is unchanged.

## Observations (no action required for this task)

- Strict reuse detection with no grace window: if a refresh response is lost after the commit and the app retries with the same token, or the app refreshes twice in parallel, the device is revoked. This follows AC1 and is tested deliberately. The Android client task should run only one refresh at a time.

## Review conclusion

No material issues found.
