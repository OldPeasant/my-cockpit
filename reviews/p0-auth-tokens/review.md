# Code Review

Date: 2026-10-20

Task: p0-auth-tokens - Device tokens for the Android app

Scope: committed task changes `git diff 49c711e..06ad48e` (excluding `tasks/`): production code in `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/` (DeviceTokenController, AuthDevice, DeviceTokenService, LoginService refactor, AuthDeviceRepository, BearerAuthenticationFilter, SecurityConfiguration, SessionAuthenticationFilter), migration `server/src/main/resources/db/migration/core/V202610201200__core_auth_device.sql`, tests `DeviceToken{Unit,Web,EdgeCase}Tests.kt`, `api/openapi.yaml`, `web/src/app/api/generated/` and the existing reports `reviews/p0-auth-tokens/tests.md` and `fixes.md`.

Requirements:
- `tasks/p0-auth-tokens.md` acceptance criteria and recorded assumptions (opaque access tokens, 90-day refresh lifetime, revoke = row delete, DELETE without If-Match, token endpoints share the login throttle and are CSRF-exempt, any bearer header disables cookie authentication, no passkey on the token endpoint)
- SPEC.md §4.1 (Android login returns a long-lived refresh token and a 15-minute access token; devices listed and revocable; login rate limiting) and §4.2 (UUIDv7, timestamps/version, Problem Details, ISO-8601)
- docs/ARCHITECTURE.md, docs/adr/0001-web-sessions-in-database.md, api/README.md, docs/QUALITY.md
- AGENTS.md project notes (module packages, migration naming, no secrets in logs, generated OpenAPI/clients)

Git range:
- Branch: main
- HEAD: 06ad48e07981703535aefdc3820a67b5083b1a4a
- Base/start: 49c711e584bd255007ea9e9f4f99240efa4e2aad
- Commits reviewed: 3 (72ec614 implementation, 6a213a8 test-writer tests, 06ad48e TW-1 fix)
- Uncommitted changes: the working tree has one uncommitted, out-of-scope addition to `DeviceTokenEdgeCaseTests.kt` (a new test, apparently in-progress helper work). It is not part of the reviewed revision; it was present when the tests below ran.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Commands run

- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*' --tests '*OpenApiContractTest'`: the first attempt aborted with a Gradle `NoSuchFileException` on `build/test-results/test/binary/in-progress-results-generic.bin` (an infrastructure error, likely from a concurrent Gradle run in the same clone, not a test failure); the retry passed: BUILD SUCCESSFUL, 19 result files, 166 tests, 0 failures, 0 errors, 0 skipped (Postgres via Testcontainers). `OpenApiContractTest` passed without `UPDATE_OPENAPI`, and `git status` shows `api/openapi.yaml` unchanged afterwards.
- `cd web && npm run api:check` (drift check of `web/src/app/api/generated/` against `api/openapi.yaml`): could not be run in this review sandbox (permission denied). Instead I checked by hand that the new generated files (`issue-device-token.ts`, `refresh-device-token.ts`, `list-devices.ts`, `revoke-device.ts`, the four models, `functions.ts`, `index.ts`, `models.ts`) carry the ng-openapi-gen header and match the schemas in `api/openapi.yaml`. `npm run check`, part of `.taskcheck`, runs `api:check`, so the parent's `.taskcheck` run is the real evidence for this.

## Verification against acceptance criteria

1. Token issue and rotation: `POST /api/v1/auth/token` checks the credentials through `LoginService.verify` (same throttle, same timing-safe checks, recovery code consumed) and returns a 256-bit opaque access token (15 min) and refresh token. Only the SHA-256 hashes are stored (`auth_device`). `POST /token/refresh` locks the device row (`PESSIMISTIC_WRITE`), puts the old refresh hash in `auth_device_retired_token` and rotates both tokens. A retired token that comes back deletes the device. Under Postgres READ COMMITTED, a concurrent second refresh waits for the lock, then finds no row (the WHERE clause is checked again after the update) and sees the committed retired token, so it counts as a reuse. `concurrently(...)` in `DeviceTokenEdgeCaseTests` covers this, and `DeviceTokenWebTests` covers rotation and reuse revocation. Met.
2. Bearer alongside the session: `BearerAuthenticationFilter` authenticates `Authorization: Bearer`. `SessionAuthenticationFilter` ignores the cookie when any bearer header is present. CSRF ignores requests that carry a bearer header, and that is safe: such a request is never cookie-authenticated, no CORS configuration exists in `server/src/main` (so a browser cannot send the header cross-site), and the token endpoints are exempt. Tests cover these cases: bearer without CSRF, cookie without CSRF gives 403, an invalid, empty or expired bearer token next to a valid cookie gives 401, another auth scheme leaves the cookie and CSRF in charge, and an invalid bearer token on a write gives 401 (TW-1). Met.
3. Device list and revoke: `GET /devices` lists devices that have not expired, `DELETE /devices/{id}` deletes the row (204 or 404), and the tokens of a revoked device get 401. A session-based DELETE needs CSRF. All of this is tested. Met.
4. The auth test package passes with Testcontainers Postgres (run above). Met.
5. `api/openapi.yaml` was regenerated, and the contract test passes without the flag (run above). The web client is regenerated (checked by hand, see above). Met.

## Focus areas checked without findings

- Secrets: `TokenRequest`, `RefreshRequest`, `TokenResponse` and `DeviceTokens` override `toString`. Log lines contain only device UUIDs. `DeviceResponse` exposes no hashes. Token responses are `Cache-Control: no-store`. A log-capture test asserts that no password, token or hash appears. No credentials are committed (test data is fictitious).
- Migration: a new file in `db/migration/core/` named after the convention `V<yyyyMMddHHmm>__core_...`. It has the BaseEntity columns (id, created_at, updated_at, version), unique indexes on the token hashes, `on delete cascade` from user to device and from device to retired token (tested), and no applied migration was edited.
- Architecture: the code stays within `core.auth.{api,domain,infra}`, the same layering as `LoginService`. The pattern of hashed opaque tokens in a table is the one ADR 0001 anticipated. The `LoginService.verify` extraction keeps the web login unchanged (the existing login tests pass).
- Concurrency of revoke against refresh: the bulk delete waits for the refresh's row lock, and a refresh after a revoke finds nothing and returns 401.

## Observations (no action required for this task)

- Strict reuse detection with no grace window: if a refresh response gets lost after the commit and the app retries with the same refresh token, the device is revoked and the user must log in again. Two parallel refreshes from the same app have the same effect. This follows AC1 and is tested deliberately. The Android client task should make sure it runs only one refresh at a time.

## Review conclusion

No material issues found.
