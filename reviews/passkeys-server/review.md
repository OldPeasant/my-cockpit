# Code Review

Date: 2026-10-22

Task: passkeys-server - Passkeys (WebAuthn) backend

Scope: `git diff af1ceb6bf6a4015f5d143a817b1750c7056b17d8 da26f8db` (task code commits 3f6b7f4b and da26f8db; heartbeat/verify commits under `tasks/` ignored; generated `api/openapi.yaml` and `web/src/app/api/generated/` checked for consistency only). This report replaces the review of the 2026-10-20 revision.

Requirements:
- `tasks/passkeys-server.md` acceptance criteria and recorded assumptions, in particular the 2026-10-22 TOTP re-enrollment assumption
- `SPEC.md` §4.1 (auth, passkeys), §4.2 (Problem Details, optimistic locking), §10.2 (restore, TOTP re-enrollment)
- `docs/ARCHITECTURE.md`, `AGENTS.md` project notes, `docs/QUALITY.md`
- Parent instructions: focus on WebAuthn security, re-enrollment handling, the `findFirstForUpdate` lock in `verifyAssertion`, Problem Details, secret logging, migration

Git range:
- Branch: main
- HEAD: da26f8dbab45f6b38e09dd5fc79f77c15b63a3d6
- Base/start: af1ceb6bf6a4015f5d143a817b1750c7056b17d8
- Commits reviewed: 2 (3f6b7f4b implementation, da26f8db test-writer tests); no uncommitted changes

Summary: 0 P0, 1 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | Passkey login loads the account before taking the row lock: a concurrent confirm makes it fail with 409, and the package tests fail**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/passkey/domain/PasskeyService.kt:242-244` (with `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/passkey/infra/PasskeyRepository.kt:11`)
    - Category: Correctness
    - Problem: `verifyAssertion` first calls `passkeys.findByCredentialId(...)`. That query is `join fetch p.user`, so it puts the `UserAccount` into the persistence context without a lock, at its current `version`. Only then does it call `users.findFirstForUpdate()`. Suppose a TOTP re-enrollment confirm (or any other committed update of `app_user`) commits between the two reads. The locked query then returns a newer version of an entity the persistence context already holds. Hibernate rejects that with a stale-state / optimistic-locking error instead of returning the fresh row. The `Passkey` row is also read before the lock, so two concurrent logins with the same passkey hit the same version conflict on `saveAndFlush(passkey)`. The code comment ("Locked like in LoginService.verify, so the grant cannot interleave with a TOTP re-enrollment confirm") and the 2026-10-22 assumption promise that a login waits for the confirm and then sees the cleared flag. `LoginService.verify` keeps that promise because it locks before reading anything. The passkey path does not. The test-writer's `PasskeyTotpReenrollmentTests` tests `a passkey token request that runs while a confirm is not committed yet waits, then gets a device with full access` and `a passkey web login that runs while a confirm is not committed yet waits, then gets a session with full access` encode the expected behavior. Both are committed in da26f8db and fail deterministically (`reviews/passkeys-server/tests.md`, finding TW-1: 67 tests, 65 passed, 2 failed). The password-path equivalent in `TotpEnrollmentWebTests` passes. I confirmed the ordering in the current code. The failure mode matches the JPA rule that a lock on an already-managed versioned entity triggers a version check.
    - Why it matters: Acceptance criterion 2 (`./gradlew test --tests 'ch.sonensei.cockpit.core.auth.passkey.*'` passes) is not met at this revision. In use, a legitimate passkey login that races with a confirm or a concurrent passkey login fails. `ProblemDetailsAdvice` maps the error to a 409, which `/passkeys/login` and `/passkeys/token` do not document. The login also uses up its single-use ceremony and counts as a failed attempt in the shared `LoginThrottle`. The documented locking guarantee does not hold. It fails closed, so there is no access bypass.
    - Suggested fix: Take the account lock before loading the passkey. Call `users.findFirstForUpdate()` first (as `LoginService.verify` does), then load the passkey, compare `passkey.user.id` with the locked account's id, and re-read or lock the passkey after the account lock so its `signCount`/`version` are current. Alternatively, drop `join fetch p.user` and compare the FK id without loading the user before the lock. Keep the two TW-1 tests as the regression tests, and re-run the whole package.

## P2 Medium

None.

## P3 Low

None.

## Verified without findings

- Challenges: 32 random bytes from `SecureRandom`, one per ceremony. `PasskeyCeremonies.finish` removes a ceremony before any check (single use, also on failure), checks expiry (5 min), and has separate bounded stores for registration and authentication. Registration ceremonies are bound to the starting owner's id. An authentication ceremony cannot be finished as a registration or the other way round (type check after `finish`).
- Origin and RP ID: `ServerProperty` is built from the RP stored in the ceremony, not from the finishing request. The rpIdHash, origin and challenge are verified by webauthn4j. Defaults from `COCKPIT_DOMAIN` accept the request's own origin only when its host equals the domain. Explicit `COCKPIT_AUTH_PASSKEY_*` values take precedence. A spoofed `Host` header cannot make an authenticator sign for the real RP.
- User verification and presence: required in `RegistrationParameters` and `AuthenticationParameters` (`true, true`), and requested as `required` in the options. Attestation is `none`, so the non-strict manager is appropriate (recorded assumption).
- Sign counter: the stored value is passed in `CredentialRecordImpl`. webauthn4j's default handler rejects a non-increasing counter (covered by `a sign count that does not increase is rejected`). The new counter and the UV/BS flags are persisted on success.
- User handle: when present, it must equal the owner's 16-byte id handle. The passkey must belong to the active account.
- Re-enrollment: `startRegistration` ignores the TOTP secret while `totpReenrollRequired` is set, and `fullAccess` returns 403 for re-enroll-only sessions. A passkey web login gets a re-enroll-only session because the role is derived per request from the account flag. `issueToken` returns 403 Problem `TotpEnrollmentRequired` without creating a device. Restore truncates all tables (`RestoreService.wipeDatabase`), so `auth_passkey` does not survive a restore, which matches the "users re-register" assumption.
- Throttle: registration TOTP checks and both assertion endpoints share `LoginThrottle`. Success is recorded only after the grant is written.
- Problem Details: controller rejections use `application/problem+json` (401/403/429 with `Retry-After`). Validation and domain exceptions go through the shared advice.
- Logging: only passkey ids, exception class names and fixed reasons, all at INFO. No TOTP codes, challenges, credentials, tokens or user handles are logged. The request DTO with the TOTP code hides it in `toString`.
- Migration: new file `core/V202610221000__core_auth_passkey.sql`, named per the convention, with no version collision in `core/`. It declares the `BaseEntity` columns, a unique index on `credential_id`, and an FK to `app_user` with cascade. No applied migration was edited.
- CSRF and permits: only the passkey authentication options, login and token endpoints are public. The web login still needs the CSRF header. Options and token are exempt, matching the password token endpoint.
- Generated files: the passkey paths, the 403 on `/passkeys/token` and the schemas in `api/openapi.yaml` match the controller and DTOs. `web/src/app/api/generated/` has the matching functions and models.

## Review conclusion

Material issues found; fix before completing the task.
