---
title: First-run setup: owner account, password hashing, TOTP enrollment
status: in_progress
owner: dell26/agent-4
deps: [p0-db-conventions,p0-api-conventions,p0-crypto]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/auth,server/src/test/kotlin/ch/sonensei/cockpit/core/auth,server/src/main/resources/db/migration/core-auth]
prio: 1
lease_until: 2026-10-20T10:24:09Z
---
## Goal
Single-user setup flow (SPEC.md §4.1): available only while no user exists.

## Acceptance criteria
- [x] `GET /api/v1/setup/status` tells whether setup is needed; `POST /api/v1/setup` creates the user (username, password ≥ 12 chars, Argon2id hash) and returns a TOTP secret + otpauth URI + 10 recovery codes (stored hashed); `POST /api/v1/setup/confirm-totp` activates it with a valid code
  - verified (2026-10-20 12:05+0200 by dell26/agent-4): cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.core.auth.*' (Postgres 16 Testcontainer) -> SetupWebTests/SetupEdgeCaseTests pass: GET /api/v1/setup/status, POST /api/v1/setup (username, password >=12, argon2id hash, base32 secret + otpauth URI + 10 Argon2id-hashed recovery codes), POST /api/v1/setup/confirm-totp activates with a computed code
- [x] After a user exists every setup endpoint returns 409/404 (test); the TOTP secret is stored encrypted (p0-crypto)
  - verified (2026-10-20 12:05+0200 by dell26/agent-4): SetupWebTests 'every setup endpoint answers 409 once the account is active' + SetupEdgeCaseTests (active stays closed after 2 days); 'TOTP secret is stored encrypted' reads app_user.totp_secret via JDBC -> ciphertext, SecretCipher.decrypt == secret -> pass
- [x] Tests compute TOTP codes from the secret (RFC 6238 test vectors pass); `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` passes (Postgres via Testcontainers where persistence is involved)
  - verified (2026-10-20 12:05+0200 by dell26/agent-4): TotpTests 'RFC 6238 test vectors pass' (SHA1/256/512, 8 digits, all 18 vectors); cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.core.auth.*' --tests '*OpenApiContractTest' against Postgres Testcontainer -> BUILD SUCCESSFUL, 50 tests, 0 failures
- [x] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag
  - verified (2026-10-20 12:05+0200 by dell26/agent-4): cd server && UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest' -> api/openapi.yaml regenerated (no diff vs committed); then ./gradlew test --rerun --tests '*OpenApiContractTest' without flag -> pass; web: npm run api:gen + api:check -> up to date

## Notes
- ASSUMPTION (2026-10-20 11:43+0200 by dell26/agent-4): Migration lives in db/migration/core/ (V202610201000__core_auth_user.sql), not core-auth/: MigrationConventionTests requires folder == module name [a-z0-9]+, so a hyphenated folder fails the check.
- ASSUMPTION (2026-10-20 11:43+0200 by dell26/agent-4): After setup, POST /setup and /setup/confirm-totp answer 409; GET /setup/status stays 200 with setupRequired=false so the web client can route to login (informational, not a setup action).
- ASSUMPTION (2026-10-20 11:43+0200 by dell26/agent-4): Setup endpoints are public and exempt from CSRF (no session/XSRF cookie exists yet; only open while no active account). POST /setup again before TOTP confirmation replaces the pending account (lost QR); a unique index on app_user((true)) enforces a single user.
- ASSUMPTION (2026-10-20 11:43+0200 by dell26/agent-4): Added core/auth/infra/SecurityConfiguration (health + setup public, rest authenticated with plain 401, CSRF on) and the Argon2id PasswordEncoder bean (Spring v5.8 defaults); p0-auth-login extends it with sessions and the XSRF cookie.
- ASSUMPTION (2026-10-20 11:43+0200 by dell26/agent-4): Recovery codes: xxxxx-xxxxx from a 31-char alphabet without look-alikes, normalized (lowercase, letters/digits only) and Argon2id-hashed. Username 1-64 chars of letters, digits, . _ @ -; password 12-256 chars.
- ASSUMPTION (2026-10-20 11:58+0200 by dell26/agent-4): Supersedes the earlier replace rule: POST /setup while an account is pending replaces it only with the same username+password, or once the pending account is older than 1 hour (abandoned setup); otherwise 409 'Setup is already in progress' (fix of review finding CR-1).
