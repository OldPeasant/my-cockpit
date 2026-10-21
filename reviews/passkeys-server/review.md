# Code Review

Date: 2026-10-20

Task: passkeys-server - Passkeys (WebAuthn) backend

Scope: `git diff 7547cf44331b638adb5ec6a1a6d3b01807b37aaa..HEAD` (task commits 925af6f, 7577d45, f38df20, 1e5bb33, 2d9eae0). Generated files (`api/openapi.yaml`, `web/src/app/api/generated/`) were checked for consistency with the server annotations only; they were not reviewed line by line.

Requirements:
- `tasks/passkeys-server.md` (acceptance criteria and recorded assumptions)
- `SPEC.md` §4.1, §4.2
- `docs/ARCHITECTURE.md`
- `AGENTS.md` project notes
- Parent agent instructions (WebAuthn security, Problem Details, logging, module boundaries, migrations)

Git range:
- Branch: main
- HEAD: 2d9eae0c95d6e6daf0664be052ae407288d6825b
- Base/start: 7547cf44331b638adb5ec6a1a6d3b01807b37aaa
- Commits reviewed: 5
- Uncommitted changes: none

Summary: 0 P0, 0 P1, 1 P2, 3 P3

Checked and found sound:
- Ceremonies: 32-byte SecureRandom challenges and random ceremony ids. A ceremony can be finished only once (`finish` removes it, failed attempts included) and expires after 5 minutes. Registration and authentication ceremonies cannot be swapped. A registration ceremony is bound to the owner who started it.
- Verification: webauthn4j checks origin, RP ID hash, challenge, type, UP and UV (`userVerificationRequired=true` on both ceremonies), algorithms (ES256/EdDSA/RS256), and that the sign counter is not malicious (a decreasing count is rejected, a count that stays at zero is allowed). A user handle that is present must match the credential's owner. Inactive accounts are rejected.
- Throttling: passkey login and token login call `LoginThrottle.tryBeginAttempt()` before they use up the ceremony. Wrong registration TOTP codes count against the same throttle. A success resets it only after the session or device row is written.
- CSRF: web passkey login and the management endpoints keep CSRF. Only the public authentication options and the token endpoint are exempt, which matches the existing password token endpoint.
- Authorization: management needs a login (session or bearer). Registration also needs TOTP step-up. This is a single-user system, so list and delete without an ownership filter are acceptable.
- Problem Details: 400/401/403/404/409/429 use `application/problem+json` and follow the existing `AuthController` pattern.
- Logging: only ids and exception class names are logged. No TOTP codes, tokens or credential JSON. The request DTO's `toString` hides the TOTP code.
- Module layout is `core.auth.passkey.{api,domain,infra}`. The migration `db/migration/core/V202610201300__core_auth_passkey.sql` follows the naming convention, and its version does not collide with existing migrations. The entity uses `BaseEntity` (UUIDv7, timestamps, version). Account deletion cascades.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Shipped deployment never passes the passkey relying-party config, and the request-based fallback produces the wrong origin behind Caddy**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/passkey/api/PasskeyRelyingParties.kt:28-36` (with `deploy/docker-compose.yml`, `server` service `environment`)
    - Category: Integration
    - Problem: When `cockpit.auth.passkey.origins` is unset, the accepted origin comes from `request.requestURL`.
        - In the production stack, Caddy terminates TLS and forwards plain HTTP to `server:8080`. No `server.forward-headers-strategy` (or equivalent) is configured in `application.properties`, so the server sees scheme `http`.
        - The derived origin is therefore `http://<COCKPIT_DOMAIN>`, while the browser's `clientDataJSON` carries `https://<COCKPIT_DOMAIN>`. webauthn4j rejects the origin, so every passkey registration and login fails in the deployed stack.
        - `docs/ARCHITECTURE.md` tells operators to set `COCKPIT_AUTH_PASSKEY_*` in production. But `deploy/docker-compose.yml` only forwards an explicit list of variables to the server, and `deploy/.env.example` does not mention them, so adding them to `deploy/.env` has no effect.
        - The Android origin (`android:apk-key-hash:...`) cannot come from a request at all, so Android passkeys also need this wiring.
        - No other task (`web-passkeys`, `and-passkeys`) touches `deploy/`.
    - Why it matters: The criterion says the feature "works for web sessions and Android token login", but it does not work in the only shipped deployment path without undocumented manual edits to the compose file.
    - Suggested fix: Pick one of these:
        - Default the RP ID to `COCKPIT_DOMAIN` and the web origin to `https://${COCKPIT_DOMAIN}`. This variable is already passed to the server and is documented as "not yet used".
        - Or add `COCKPIT_AUTH_PASSKEY_RP_ID/ORIGINS/RP_NAME` pass-through to `deploy/docker-compose.yml` and `deploy/.env.example`.

      If this is outside the task's `touches`, record an assumption and add a follow-up task, so the gap is tracked rather than silent.

## P3 Low

- [ ] **CR-2 | ARCHITECTURE.md claims the unconfigured default is "fine for e2e", but the e2e stack runs on an IP address**
    - File: `docs/ARCHITECTURE.md:109`
    - Category: Integration
    - Problem: `web/e2e/support/run-e2e.mjs:63` sets the e2e base URL to `http://127.0.0.1:<port>`. With no passkey config, the RP ID becomes `127.0.0.1`. WebAuthn clients reject an RP ID that is not a valid domain (IP addresses are not), so `navigator.credentials.create()/get()` fails on that stack. The documented "fine for dev/e2e" holds for `npm start` (the proxy keeps the `localhost:4200` Host) but not for the e2e harness.
    - Why it matters: The `web-passkeys` criterion requires Playwright passkey tests against this e2e stack. That task would discover this late, and the fix sits outside its `touches` (`web/e2e/support`, `deploy/docker-compose.e2e.yml`).
    - Suggested fix: Correct the doc note to say the e2e stack must be reached via `localhost` (or must set `COCKPIT_AUTH_PASSKEY_RP_ID`/`ORIGINS` accordingly). Optionally record this as an assumption or note on `web-passkeys`.

- [ ] **CR-3 | Unauthenticated option requests can evict in-progress registration ceremonies**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/passkey/domain/PasskeyCeremonies.kt:64`
    - Category: Security
    - Problem: `POST /auth/passkeys/authentication/options` is public, needs no CSRF token and is not throttled. Each call adds a ceremony to the single shared pool of 256, and the oldest is evicted, whatever its kind. About one request per second is enough to keep evicting the owner's pending ceremonies, including authenticated registration ceremonies.
    - Why it matters: It is a cheap denial of service for passkey login and registration. Memory stays bounded, as the assumption intends, but the authenticated flow should not be evictable by anonymous traffic.
    - Suggested fix: Pick one of these:
        - Keep registration ceremonies in a separate bucket, or exempt them from eviction (there is at most a handful, owner-only).
        - Or record the accepted DoS trade-off explicitly in the existing ceremony-store assumption.

- [ ] **CR-4 | OpenAPI advertises `application/problem+json` with a `PasskeyResponse` body for the 201 of `finishPasskeyRegistration`**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/passkey/api/PasskeyController.kt:113` (rendered in `api/openapi.yaml:223-231`)
    - Category: Integration
    - Problem: The mapping produces both `application/json` and `application/problem+json`, and the 201 `@ApiResponse` has no explicit content. springdoc therefore lists the success schema under both media types. No other operation in the contract pairs `problem+json` with a non-`ProblemDetail` schema.
    - Why it matters: The contract is inaccurate for generated clients. It is a minor inconsistency with the rest of the API.
    - Suggested fix: Give the 201 response explicit `content = [Content(mediaType = APPLICATION_JSON_VALUE, schema = Schema(implementation = PasskeyResponse::class))]`. Then regenerate `api/openapi.yaml` and the web client.

## Review conclusion

Material issues found; fix before completing the task.

CR-1 does not break the code paths that are tested, but the passkey feature does not work in the shipped production stack. Fix it, or explicitly hand it off with a tracked follow-up task and a recorded assumption. CR-2 to CR-4 are low-priority cleanups.
