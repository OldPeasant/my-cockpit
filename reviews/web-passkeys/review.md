# Code Review

Date: 2026-10-22

Task: web-passkeys - Web passkeys

Scope: `git diff 9b6ff39d92db4647633fd686b03aab1a4790efbe..HEAD` (implementation commit 651bef4f8 and test commit 02200f82a)

Requirements:
- tasks/web-passkeys.md (acceptance criteria and recorded assumptions)
- SPEC.md §4.1 (passkeys), AGENTS.md project notes, docs/ARCHITECTURE.md conventions (i18n, a11y, tokens)
- api/openapi.yaml paths `/api/v1/auth/passkeys*` and schemas `CreationOptionsJson`, `RequestOptionsJson`, `PasskeyRegistrationRequest`, `PasskeyLoginRequest`
- server/src/test/.../core/auth/passkey/TestAuthenticator.kt (JSON shape accepted by webauthn4j)
- web/e2e/README.md (e2e harness conventions)

Git range:
- Branch: main
- HEAD: 02200f82a7f3cc91bb5367819b1504eb46ab41a1
- Base/start: 9b6ff39d92db4647633fd686b03aab1a4790efbe
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Passkey login reports an error after a successful login when the session refresh fails**
    - File: `web/src/app/features/auth/passkey/passkey-login.service.ts:40`
    - Category: Correctness
    - Problem: After `loginWithPasskey` succeeds and the server sets the session cookie, `PasskeyLogin.login` awaits `auth.checkSession(true)`. If that `GET /auth/me` fails with anything other than 401 (network blip, 5xx), `checkSession` rethrows. `login()` then rejects even though the user is signed in. `Login.showPasskeyError` shows the error inline and stays on `/login`. The `/auth/me` call does not set `SILENT_ERRORS`, so a toast also appears.
    - Why it matters: This is a rare transient case, but the user sees a failure for a login that worked and must start a second passkey ceremony.
    - Suggested fix: Treat the refresh as best effort. Catch the `checkSession` failure in `PasskeyLogin.login` and still return `me`, so the guard re-checks the session on navigation. Alternatively, add a unit test that pins the intended behaviour.

## Notes (verified, no finding)

- **WebAuthn JSON contract:** `webauthn.ts` decodes `challenge`, `user.id` and descriptor ids from base64url. It encodes `rawId`, `clientDataJSON`, `attestationObject`, `authenticatorData`, `signature` and `userHandle` (null when absent) as unpadded base64url. It sends `transports`, `authenticatorAttachment` and `clientExtensionResults` in the `RegistrationResponseJSON`/`AuthenticationResponseJSON` shape that `TestAuthenticator.kt` produces. The parent's e2e run confirms this with Chromium output against the real server.
- **Error handling:** browser exceptions are mapped by DOMException name. A synchronous throw and invalid base64 input happen inside the `ceremony` closure, so they also come back as `PasskeyError`. Server statuses match the OpenAPI contract:
  - 403 = wrong TOTP code on start;
  - 400 = a response that does not verify;
  - 409 = duplicate;
  - 401 = passkey login not accepted;
  - 429 = backoff with Retry-After, defaulting to 60 s;
  - 404 on delete = already gone.
- **Concurrency:** double submit is guarded on login (`submitting`) and on add (`adding`), and a pending delete disables its button.
- **i18n:** all new strings use `i18n`/`$localize` with ids. Reused ids (`@@security.codes.totp`, `@@security.codes.totpInvalid`, `@@settings.retry`) keep identical text. `messages.xlf` is left to a dedicated extract, as in earlier tasks (commit 80411290d).
- **a11y:** delete buttons carry an aria-label with the passkey name. Alerts use `role="alert"` and status messages use `role="status"`. After a delete, focus moves to the next or previous button or to the heading. The delete dialog focuses Cancel first. The "or" separator is `aria-hidden`.
- **Security:** nothing new is logged. TOTP codes and credentials are only sent to the API. The passkey button and the add form are hidden when WebAuthn is unavailable (insecure context).
- **Cross-feature import:** `settings/security` importing `features/auth/passkey/webauthn` is a recorded assumption, and `features/` already has other cross-feature imports.
- **Tests:** they assert behaviour (requests sent, messages shown, focus, disabled state) across the ceremony error matrix for both login and registration. Task-scoped coverage in `reviews/web-passkeys/tests.md` is 99.44% of statements.
- **Criterion 3 evidence:** the parent ran the spec with `--no-deps`. `web/e2e/README.md` documents that flag for skipping the `phase-demos` project. Nothing has been recorded with `tasks.py verify` yet. The parent should record the exact command and result, and note the `--no-deps` deviation from the command in the criterion.

## Review conclusion

No material issues found.
