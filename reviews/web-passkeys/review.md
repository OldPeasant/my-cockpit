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

---

# Follow-up review: revision 587a4cf4f

Date: 2026-10-22

Task: web-passkeys - Web passkeys

Scope: `git diff b29ee70e01c02dd97c06876b3b02f7176240d6b1..587a4cf4f8cd05e3a5f4f329befe2fe6690b8eee` (one code commit, after the first review report). I also scanned the repo for other `Sign in` locators that are still ambiguous.

Requirements:
- tasks/web-passkeys.md (acceptance criteria), web/e2e/README.md, and the sources listed above
- Parent's disposition of CR-1 and its e2e run result

Git range:
- Branch: main
- HEAD: 587a4cf4f8cd05e3a5f4f329befe2fe6690b8eee
- Base/start: b29ee70e01c02dd97c06876b3b02f7176240d6b1
- Commits reviewed: 1 (587a4cf4f, "exact 'Sign in' locators in e2e specs")

Summary: 0 P0, 0 P1, 0 P2, 0 P3 (new findings in this revision)

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Notes (verified, no finding)

- **The delta is correct and minimal.** Four locators now use `getByRole('button', { name: 'Sign in', exact: true })`:
  - `web/e2e/phase0/demo.spec.ts:94` and `:183`;
  - `web/e2e/phase2/demo.spec.ts:372`;
  - `web/e2e/support/smoke.spec.ts:13`.

  The login page has two buttons whose accessible names contain "Sign in": the submit button "Sign in" (`login.html:93-101`) and "Sign in with a passkey" (`login.html:106-115`). Playwright's default substring match resolves both, which causes the strict-mode violation. With `exact: true` only the submit button matches, so the specs click the same control they did before passkeys existed. Test intent is unchanged: no assertions were removed or weakened.
- **No ambiguous `Sign in` locators remain.** I searched `web/e2e` and `web/src` for `Sign in`, `getBy*`, `name:` and `hasText`, and found no other substring button locators.
  - `web/e2e/passkeys/passkeys.spec.ts:75` and `:93` name the full passkey button text, so they match only that button.
  - `web/e2e/passkeys/passkeys.spec.ts:73` uses `getByRole('heading', { name: 'Sign in' })` without `exact`. The login page has only one heading, `<h1>Sign in</h1>`, so this match is unambiguous.
  - The unit specs (`app.spec.ts`, `login.spec.ts`) use DOM queries or exact text, not Playwright locators.
- **E2E evidence:** the parent reports `npm run e2e -- e2e/passkeys/passkeys.spec.ts e2e/support/smoke.spec.ts` on the podman e2e stack (which includes the `phase-demos` project) -> 35 passed. I did not re-run it (read-only review). This run also covers the earlier note about running criterion 3 with `--no-deps`: the phase demos now ran with the passkey button present. The parent should record this command and result with `tasks.py verify`.
- **CR-1 disposition ("won't fix") is accepted.** I checked the parent's reasoning against the code:
  - `loginGuard` (`auth.guards.ts:70`) calls `checkSession(true)`, which gets a 401 on the login page and sets the cached user to `null` (`auth.service.ts:71`).
  - If `PasskeyLogin.login` swallowed a failed refresh, `authGuard` would then call `checkSession()` without refresh (`auth.guards.ts:40`). Because `current()` is `null`, not `undefined`, it would not ask the server again, would return `false`, and would redirect to `/login`.

  So the fix I suggested (simply catching the error) would replace a visible, retryable error with a silent bounce back to the login page. The current behaviour is the safer of the two.

  A cleaner option would be to seed the cache from the `me` that `loginWithPasskey` returns, as `AuthService.login` does at `auth.service.ts:80`. That needs a new `AuthService` method in `core/auth`, outside this task's feature folder. For a rare transient failure it is not worth reopening; it can be left to a later task if it ever matters.

## Review conclusion

No material issues found.
