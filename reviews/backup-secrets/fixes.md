# Fix Report

Date: 2026-10-21

Task: backup-secrets - Backup secrets with passphrase

Scope: current task; the two open findings CR-1 (P2) and CR-2 (P3) of the re-review `reviews/backup-secrets/review.md`, both approved by the parent worker. Base commit b0762bf56ff8dfdabb3d7fb9894112eb68cb0675.

## Fixed findings

### CR-1 — Restricted re-enrollment sessions get full access when any session completes the enrollment

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/infra/AuthSessionRepository.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/domain/TotpEnrollmentService.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/api/TotpEnrollmentController.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/TotpEnrollmentWebTests.kt`
- `api/openapi.yaml` (regenerated, descriptions only)

Problem:
The restriction of a re-enrollment session comes from the account row on each request. `confirm()` cleared `totp_reenroll_required` and kept all other sessions, so every session opened during the re-enrollment window got full access at that moment. That included password-only sessions and persistent "remember device" sessions. Any restricted session could also replace the pending secret with `start`.

Fix:
- New bulk delete `AuthSessionRepository.deleteOtherSessions(userId, keepTokenHash)`.
- `TotpEnrollmentService.confirm(code, sessionToken)` now ends all other sessions of the account after a successful confirm, in the same transaction. The caller's session (found by the SHA-256 of its cookie token) is kept. Without a token, all sessions of the account end. The log line gives only the number of ended sessions.
- `TotpEnrollmentController.confirm` reads the caller's session cookie with `SessionCookies.read` and passes it in. Device tokens cannot reach this endpoint (403 for `ROLE_TOTP_ENROLLMENT`/bearer, unchanged).
- Secret replacement: I kept the simplest safe behavior and documented it in the service KDoc and the OpenAPI descriptions. `start` (from any session) replaces the pending secret, and `confirm` accepts only a code of the latest pending secret. So a session whose secret was replaced gets 400 and must start again. It never confirms a secret it did not receive, and the owner's authenticator cannot be bound to someone else's secret. Whichever session confirms becomes the only session of the account, so a replacing session never leaves a second full-access session next to the owner's. Binding the pending secret to the session that started it would need a schema change, and it would not add protection beyond the recorded password-only assumption. So it was not done.
- Tests: `a confirm ends the other sessions of the account, also remembered and password-only ones, and keeps its own` sets up a recovery-code session that confirms, a remembered (`rememberDevice: true`) recovery-code session and a password-only session. Before the confirm, the other two get 403 on `/api/v1/settings`. After it, they get 401 on `/api/v1/settings` and on the confirm endpoint, the confirming session gets 200, and one `auth_session` row is left. `a start from another session replaces the secret, the first session's code fails and the other's confirm ends it` checks that the first session's code for its own (replaced) secret gives 400 and the flag stays set, that the second session's confirm gives 204, and that the first session then gets 401 while the second gets 200. The test helper `login` gained an optional `rememberDevice` parameter.

Validation:
- `cd server && ./gradlew test --tests '*TotpEnrollmentWebTests' --tests '*BackupSecretsTest'` — Pass (TotpEnrollmentWebTests 17 tests, 0 failures; BackupSecretsTest 16 tests, 0 failures)

### CR-2 — The OpenAPI schema still requires `minLength: 12` for a backup passphrase that may now be empty

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/backup/api/BackupController.kt`
- `api/openapi.yaml` (regenerated)

Problem:
The server accepts `{"passphrase":""}` (normalized to none), but the published schema had `minLength: 12`, so the empty string the server supports was invalid under the contract.

Fix:
Only removing `minLength` from `@Schema` was not enough: springdoc also derives `minLength: 12` from the `@Size` Bean Validation annotation (verified by regenerating). So `@Size(min = 12, max = 256)` became `@Pattern(regexp = BACKUP_PASSPHRASE_PATTERN)`, with the new constant `BACKUP_PASSPHRASE_PATTERN = "^$|^[\\s\\S]{12,256}$"`, used for both validation and `@Schema(pattern = ...)`. `maxLength: 256` stays. `[\s\S]` instead of `.` keeps line breaks allowed, as `@Size` did. Server behavior is unchanged: `""` is still normalized to `null` before validation, and 11 or 257 characters still give 400 (existing test `a backup passphrase shorter than 12 or longer than 256 characters is a 400 and starts nothing` passes). The published schema now has `pattern: "^$|^[\\s\\S]{12,256}$"`, `maxLength: 256` and no `minLength`. `api/openapi.yaml` was regenerated with `UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`. `cd web && npm run api:gen` was run, and the generated web client did not change (it holds no descriptions or patterns for this field).

Validation:
- `cd server && UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'` — Pass (file regenerated)
- `cd web && npm run api:gen` — Pass, no change under `web/src/app/api/generated/`
- `cd server && ./gradlew test --tests '*BackupSecretsTest'` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat` — Pass, no remaining changes
- `cd server && ./gradlew test --tests '*TotpEnrollmentWebTests' --tests '*BackupSecretsTest'` — Pass (17 + 16 tests, 0 failures)

Full test suite:
- `cd server && ./gradlew check --console=plain` — BUILD SUCCESSFUL (ktlint, unit and Testcontainers tests including `ch.sonensei.cockpit.backup.*` and `OpenApiContractTest` against the regenerated file)

Lint/type-check/build:
- Server lint is part of `./gradlew check` — Pass. `cd web && npm run check` — Not run: the generated web client did not change and no web file was modified.

`.taskcheck`:
- Not run by the fixer (`scripts/check.sh`); the server part of it passed via `./gradlew check`. The parent worker runs it before `done`.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
